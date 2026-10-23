package com.academy.paybridge.fx.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.fx.api.ConversionApi;
import com.academy.paybridge.fx.api.ConversionQuoteView;
import com.academy.paybridge.fx.api.ConversionView;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.FxQuote;
import com.academy.paybridge.fx.domain.Conversion;
import com.academy.paybridge.fx.domain.ConversionQuote;
import com.academy.paybridge.fx.repository.ConversionQuoteRepository;
import com.academy.paybridge.fx.repository.ConversionRepository;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerPosting;
import com.academy.paybridge.shared.accounts.SystemAccounts;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Converts between a customer's own NGN and USD balances (one account number, two balances).
 * A conversion is two balanced ledger legs, posted in one atomic batch, through the FX pools:
 * <pre>
 *   source leg:       customer       -> pool of the source currency      (source amount)
 *   destination leg:  pool of the destination currency -> customer       (destination amount)
 * </pre>
 * Each currency balances on its own. If either leg fails, neither happens.
 */
@Service
public class ConversionService implements ConversionApi {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

    private final FxApi fx;
    private final AccountApi accounts;
    private final LedgerApi ledger;
    private final ConversionQuoteRepository quotes;
    private final ConversionRepository conversions;
    private final long quoteTtlSeconds;

    public ConversionService(FxApi fx, AccountApi accounts, LedgerApi ledger,
                             ConversionQuoteRepository quotes, ConversionRepository conversions,
                             @Value("${paybridge.fx.quote-ttl-seconds:60}") long quoteTtlSeconds) {
        if (quoteTtlSeconds <= 0) {
            throw new IllegalStateException("paybridge.fx.quote-ttl-seconds must be greater than zero");
        }
        this.fx = fx;
        this.accounts = accounts;
        this.ledger = ledger;
        this.quotes = quotes;
        this.conversions = conversions;
        this.quoteTtlSeconds = quoteTtlSeconds;
    }

    @Override
    public ConversionQuoteView createQuote(String accountNumber, Currency source, Currency destination,
                                           long sourceAmountMinor) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("accountNumber is required");
        }
        FxQuote priced = fx.quote(source, destination, sourceAmountMinor);
        requireBalance(accountNumber, source);
        requireBalance(accountNumber, destination);

        ConversionQuote quote = quotes.save(new ConversionQuote(
                "cq-" + UUID.randomUUID(), accountNumber,
                priced.sourceCurrency(), priced.destinationCurrency(),
                priced.sourceAmountMinor(), priced.destinationAmountMinor(),
                priced.rate(), priced.rateType(), Instant.now().plusSeconds(quoteTtlSeconds)));
        return toView(quote);
    }

    // NOTE: intentionally NOT @Transactional, like RemittanceService. The conversion record must be
    // committed before money moves, so a failed conversion is recorded as FAILED instead of rolled away.
    @Override
    public ConversionView convert(String quoteId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (quoteId == null || quoteId.isBlank()) {
            throw new IllegalArgumentException("quoteId is required");
        }
        var replayed = conversions.findByIdempotencyKey(idempotencyKey);
        if (replayed.isPresent()) {
            return replay(replayed.get(), quoteId);
        }

        ConversionQuote quote = quotes.findByQuoteId(quoteId)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteId));
        if (conversions.existsByQuoteId(quoteId)) {
            // Another request may have consumed this quote using the same key.
            // Check idempotency again before treating it as a different request.
            var winner = conversions.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return replay(winner.get(), quoteId);
            }

            throw new IllegalStateException("Quote has already been used: " + quoteId);
        }
        if (quote.isExpired(Instant.now())) {
            throw new IllegalStateException("Quote has expired: " + quoteId);
        }

        Conversion conversion = new Conversion("cnv-" + UUID.randomUUID(), idempotencyKey, quote);
        try {
            conversion = conversions.saveAndFlush(conversion);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request won the race: either the same key (replay it) or the same quote.
            var winner = conversions.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return replay(winner.get(), quoteId);
            }
            throw new IllegalStateException("Quote has already been used: " + quoteId);
        }

        try {
            ledger.postAll(legs(conversion));
            conversion.markCompleted();
            log.info("Conversion {} completed: {} {} -> {} {} at rate {} ({})", conversion.getReference(),
                    conversion.getSourceAmountMinor(), conversion.getSourceCurrency(),
                    conversion.getDestinationAmountMinor(), conversion.getDestinationCurrency(),
                    conversion.getFxRate(), conversion.getRateType());
        } catch (RuntimeException e) {
            // postAll is atomic, so nothing moved. The quote is spent; the customer asks for a new one.
            conversion.markFailed(e.getMessage());
            log.warn("Conversion {} failed, nothing was moved: {}", conversion.getReference(), e.getMessage());
        }
        return toView(conversions.save(conversion));
    }

    @Override
    public String getQuoteAccountNumber(String quoteId) {
        return quotes.findByQuoteId(quoteId)
                .map(ConversionQuote::getAccountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteId));
    }

    @Override
    public ConversionView getByReference(String reference) {
        return conversions.findByReference(reference)
                .map(ConversionService::toView)
                .orElseThrow(() -> new ResourceNotFoundException("Conversion not found: " + reference));
    }

    private static List<LedgerPosting> legs(Conversion c) {
        return List.of(
                new LedgerPosting(c.getAccountNumber(), pool(c.getSourceCurrency()),
                        Money.ofMinor(c.getSourceAmountMinor(), c.getSourceCurrency()),
                        c.getReference() + "-" + c.getSourceCurrency().name()),
                new LedgerPosting(pool(c.getDestinationCurrency()), c.getAccountNumber(),
                        Money.ofMinor(c.getDestinationAmountMinor(), c.getDestinationCurrency()),
                        c.getReference() + "-" + c.getDestinationCurrency().name()));
    }

    private static String pool(Currency currency) {
        return currency == Currency.NGN ? SystemAccounts.FX_NGN_POOL : SystemAccounts.FX_USD_POOL;
    }

    private void requireBalance(String accountNumber, Currency currency) {
        if (accounts.findByNumber(accountNumber, currency).isPresent()) {
            return;
        }
        if (!accounts.exists(accountNumber)) {
            throw new ResourceNotFoundException("Account not found: " + accountNumber);
        }
        throw new IllegalArgumentException("Account " + accountNumber + " has no " + currency + " balance");
    }

    private ConversionView replay(Conversion existing, String quoteId) {
        if (!existing.getQuoteId().equals(quoteId)) {
            throw new IllegalStateException("Idempotency-Key was already used with a different quote");
        }
        return toView(existing);
    }

    private static ConversionQuoteView toView(ConversionQuote q) {
        return new ConversionQuoteView(q.getQuoteId(), q.getAccountNumber(), q.getSourceCurrency(),
                q.getDestinationCurrency(), q.getSourceAmountMinor(), q.getDestinationAmountMinor(),
                q.getFxRate(), q.getRateType(), q.getExpiresAt());
    }

    private static ConversionView toView(Conversion c) {
        return new ConversionView(c.getReference(), c.getStatus().name(), c.getQuoteId(), c.getAccountNumber(),
                c.getSourceCurrency(), c.getDestinationCurrency(), c.getSourceAmountMinor(),
                c.getDestinationAmountMinor(), c.getFxRate(), c.getRateType(), c.getFailureReason(),
                c.getCreatedAt());
    }
}
