package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountRef;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittancePage;
import com.academy.paybridge.remittance.api.OutboundRemittanceRequest;
import com.academy.paybridge.remittance.api.OutboundRemittanceView;
import com.academy.paybridge.remittance.api.ProviderQuoteView;
import com.academy.paybridge.remittance.client.GatewayQuote;
import com.academy.paybridge.remittance.client.RemittanceGateway;
import com.academy.paybridge.remittance.client.SendInstruction;
import com.academy.paybridge.remittance.client.SendResult;
import com.academy.paybridge.remittance.client.SendStatus;
import com.academy.paybridge.remittance.domain.OutboundRemittance;
import com.academy.paybridge.remittance.domain.OutboundRemittanceStatus;
import com.academy.paybridge.remittance.domain.ProviderQuote;
import com.academy.paybridge.remittance.repository.OutboundRemittanceRepository;
import com.academy.paybridge.remittance.repository.ProviderQuoteRepository;
import com.academy.paybridge.shared.accounts.SystemAccounts;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Money rules, in order:
 * <ol>
 *   <li>HOLD: the customer's NGN (amount plus fee) moves to the remittance clearing account.</li>
 *   <li>The provider is called. SUCCESS completes. FAILED (provider confirms nothing was sent)
 *       refunds. PENDING, or an exception (outcome unknown), changes nothing: the money stays held
 *       until {@link #reconcile} gets a definite answer.</li>
 * </ol>
 * Completing or refunding goes through a conditional PENDING-only update, so it happens exactly once
 * even if two reconcilers race, and a refund is posted in the same transaction as the status change.
 */
@Service
public class OutboundRemittanceService implements OutboundRemittanceApi {

    private static final Logger log = LoggerFactory.getLogger(OutboundRemittanceService.class);

    private final Map<String, RemittanceGateway> gateways = new HashMap<>();
    private final AccountApi accounts;
    private final LedgerApi ledger;
    private final ProviderQuoteRepository quotes;
    private final OutboundRemittanceRepository remittances;
    private final TransactionTemplate tx;
    private final RemittanceLimits limits;
    private final long quoteTtlSeconds;

    public OutboundRemittanceService(List<RemittanceGateway> gateways, AccountApi accounts, LedgerApi ledger,
                                     ProviderQuoteRepository quotes, OutboundRemittanceRepository remittances,
                                     PlatformTransactionManager transactionManager, RemittanceLimits limits,
                                     @Value("${paybridge.remittance.quote-ttl-seconds:120}") long quoteTtlSeconds) {
        if (quoteTtlSeconds <= 0) {
            throw new IllegalStateException("paybridge.remittance.quote-ttl-seconds must be greater than zero");
        }
        for (RemittanceGateway g : gateways) {
            this.gateways.put(g.providerId(), g);
        }
        this.accounts = accounts;
        this.ledger = ledger;
        this.quotes = quotes;
        this.remittances = remittances;
        this.tx = new TransactionTemplate(transactionManager);
        this.limits = limits;
        this.quoteTtlSeconds = quoteTtlSeconds;
    }

    // ---------------------------------------------------------------- quotes

    @Override
    public List<ProviderQuoteView> getQuotes(String sourceAccount, long sourceAmountMinor,
                                             Currency destinationCurrency) {
        if (sourceAccount == null || sourceAccount.isBlank()) {
            throw new IllegalArgumentException("sourceAccount is required");
        }
        if (destinationCurrency != Currency.USD) {
            throw new IllegalArgumentException("Only NGN to USD is supported");
        }
        if (sourceAmountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        limits.checkPerTransaction(sourceAmountMinor);
        if (accounts.findByNumber(sourceAccount, Currency.NGN).isEmpty()) {
            if (!accounts.exists(sourceAccount)) {
                throw new ResourceNotFoundException("Account not found: " + sourceAccount);
            }
            throw new IllegalArgumentException("Account " + sourceAccount + " has no NGN balance");
        }

        List<ProviderQuote> stored = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        IllegalArgumentException rejected = null;
        Instant expiresAt = Instant.now().plusSeconds(quoteTtlSeconds);
        for (RemittanceGateway gateway : gateways.values()) {
            try {
                GatewayQuote q = gateway.quote(sourceAmountMinor, Currency.NGN, destinationCurrency);
                stored.add(quotes.save(new ProviderQuote("pq-" + UUID.randomUUID(), gateway.providerId(),
                        sourceAccount, sourceAmountMinor, q.feeMinor(), destinationCurrency,
                        q.destinationAmountMinor(), q.rate(), q.etaMinutes(), expiresAt)));
                names.put(gateway.providerId(), gateway.displayName());
            } catch (IllegalArgumentException e) {
                rejected = rejected == null ? e : rejected;
                log.info("{} declined to quote: {}", gateway.providerId(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("{} could not quote: {}", gateway.providerId(), e.getMessage());
            }
        }
        if (stored.isEmpty()) {
            if (rejected != null) {
                throw rejected;
            }
            throw new IllegalStateException("No provider could quote right now");
        }

        long bestDestination = stored.stream().mapToLong(ProviderQuote::getDestinationAmountMinor).max().orElseThrow();
        int fastest = stored.stream().mapToInt(ProviderQuote::getEtaMinutes).min().orElseThrow();
        long lowestFee = stored.stream().mapToLong(ProviderQuote::getFeeMinor).min().orElseThrow();

        return stored.stream()
                .sorted(Comparator.comparingLong(ProviderQuote::getDestinationAmountMinor).reversed()
                        .thenComparingLong(ProviderQuote::getFeeMinor)
                        .thenComparing(ProviderQuote::getProviderId))
                .map(q -> {
                    List<String> tags = new ArrayList<>();
                    if (q.getDestinationAmountMinor() == bestDestination) tags.add("BEST_RATE");
                    if (q.getEtaMinutes() == fastest) tags.add("FASTEST");
                    if (q.getFeeMinor() == lowestFee) tags.add("LOWEST_FEE");
                    return toView(q, names.get(q.getProviderId()), tags);
                })
                .toList();
    }

    // ---------------------------------------------------------------- send

    // NOTE: intentionally NOT @Transactional, like the other money-moving services. The remittance is
    // committed (PENDING) before any money moves, so every later outcome is recorded and recoverable.
    @Override
    public OutboundRemittanceView send(OutboundRemittanceRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.quoteId() == null || request.quoteId().isBlank()) {
            throw new IllegalArgumentException("quoteId is required");
        }
        if (request.beneficiaryName() == null || request.beneficiaryName().isBlank()) {
            throw new IllegalArgumentException("beneficiaryName is required");
        }
        if (request.beneficiaryAccount() == null || request.beneficiaryAccount().isBlank()) {
            throw new IllegalArgumentException("beneficiaryAccount is required");
        }
        var replayed = remittances.findByIdempotencyKey(idempotencyKey);
        if (replayed.isPresent()) {
            return replay(replayed.get(), request);
        }

        ProviderQuote quote = quotes.findByQuoteId(request.quoteId())
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + request.quoteId()));
        if (remittances.existsByQuoteId(quote.getQuoteId())) {
            throw new IllegalStateException("Quote has already been used: " + quote.getQuoteId());
        }
        if (quote.isExpired(Instant.now())) {
            throw new IllegalStateException("Quote has expired: " + quote.getQuoteId());
        }
        limits.checkPerTransaction(quote.getSourceAmountMinor());

        OutboundRemittance remittance = new OutboundRemittance("out-" + UUID.randomUUID(), idempotencyKey, quote,
                request.beneficiaryName().trim(), request.beneficiaryAccount().trim(),
                request.beneficiaryBank() == null || request.beneficiaryBank().isBlank()
                        ? null : request.beneficiaryBank().trim());
        try {
            remittance = remittances.saveAndFlush(remittance);
        } catch (DataIntegrityViolationException e) {
            var winner = remittances.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return replay(winner.get(), request);
            }
            throw new IllegalStateException("Quote has already been used: " + quote.getQuoteId());
        }
        String reference = remittance.getReference();

        // Daily limit. The count and the rejection happen in one transaction that holds the sender's NGN
        // balance row lock, so two simultaneous sends from one account are checked one after the other and
        // can never both slip under the limit. This payment (already stored as PENDING) is part of the total.
        String sourceAccount = remittance.getSourceAccount();
        boolean overLimit = Boolean.TRUE.equals(tx.execute(s -> {
            accounts.lockInOrder(List.of(new AccountRef(sourceAccount, Currency.NGN)));
            long sent = remittances.sumSince(sourceAccount, Instant.now().minus(Duration.ofHours(24)),
                    List.of(OutboundRemittanceStatus.FAILED, OutboundRemittanceStatus.RETURNED));
            if (sent <= limits.dailyMinor()) {
                return false;
            }
            remittances.transitionFromPending(reference, OutboundRemittanceStatus.FAILED,
                    "Daily limit of " + limits.dailyMinor() + " minor units exceeded", Instant.now());
            return true;
        }));
        if (overLimit) {
            log.warn("Outbound remittance {} rejected: daily limit exceeded", reference);
            return reload(reference);
        }

        // 1. HOLD the money. If this fails nothing moved and the provider is never called.
        try {
            ledger.post(remittance.getSourceAccount(), SystemAccounts.REMITTANCE_CLEARING,
                    Money.ofMinor(remittance.totalDebitMinor(), Currency.NGN), reference + "-HOLD");
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> remittances.transitionFromPending(reference,
                    OutboundRemittanceStatus.FAILED, "Could not hold funds: " + e.getMessage(), Instant.now()));
            log.warn("Outbound remittance {} failed at hold, nothing moved: {}", reference, e.getMessage());
            return reload(reference);
        }

        // 2. Call the provider.
        RemittanceGateway gateway = gateways.get(remittance.getProviderId());
        if (gateway == null) {
            failAndRefund(reference, "Provider " + remittance.getProviderId() + " is not available");
            return reload(reference);
        }
        SendResult result;
        try {
            result = gateway.send(new SendInstruction(reference, remittance.getSourceAmountMinor(),
                    remittance.getDestinationCurrency(), remittance.getDestinationAmountMinor(),
                    remittance.getBeneficiaryName(), remittance.getBeneficiaryAccount(),
                    remittance.getBeneficiaryBank()));
        } catch (RuntimeException e) {
            // Unknown outcome: the provider may have sent it. Keep the money held; reconcile decides.
            remittances.noteWhilePending(reference, "Provider outcome unknown: " + e.getMessage());
            log.warn("Outbound remittance {} outcome unknown, money stays held: {}", reference, e.getMessage());
            return reload(reference);
        }

        switch (result.status()) {
            case SUCCESS -> complete(reference, result.providerReference());
            case FAILED -> failAndRefund(reference, result.message());
            default -> {
                // PENDING (or an unexpected NOT_FOUND on send): keep holding.
                if (result.providerReference() != null) {
                    remittances.setProviderReference(reference, result.providerReference());
                }
                remittances.noteWhilePending(reference, "Awaiting provider confirmation");
            }
        }
        return reload(reference);
    }

    // ---------------------------------------------------------------- reads and reconciliation

    @Override
    public String getQuoteAccountNumber(String quoteId) {
        return quotes.findByQuoteId(quoteId)
                .map(ProviderQuote::getSourceAccount)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteId));
    }

    @Override
    public OutboundRemittanceView getByReference(String reference) {
        return reload(reference);
    }

    @Override
    public OutboundRemittanceView reconcile(String reference) {
        OutboundRemittance r = remittances.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Outbound remittance not found: " + reference));
        if (r.getStatus() != OutboundRemittanceStatus.PENDING) {
            return toView(r);
        }
        RemittanceGateway gateway = gateways.get(r.getProviderId());
        if (gateway == null) {
            return toView(r);
        }
        SendStatus answer;
        try {
            answer = gateway.verify(reference);
        } catch (RuntimeException e) {
            log.warn("Could not verify outbound remittance {}: {}", reference, e.getMessage());
            return toView(r);
        }
        switch (answer) {
            case SUCCESS -> complete(reference, null);
            case FAILED -> failAndRefund(reference, "Provider confirmed nothing was sent");
            default -> { /* PENDING or NOT_FOUND: still not definite, keep holding */ }
        }
        return reload(reference);
    }

    @Override
    public OutboundRemittanceView markReturned(String reference, String reason) {
        OutboundRemittance r = remittances.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Outbound remittance not found: " + reference));
        switch (r.getStatus()) {
            case RETURNED -> throw new IllegalStateException(
                    "Remittance " + reference + " has already been returned");
            case PENDING -> throw new IllegalStateException(
                    "Remittance " + reference + " is still PENDING; reconcile it before reporting a return");
            case FAILED -> throw new IllegalStateException(
                    "Remittance " + reference + " already failed and was refunded");
            default -> { /* COMPLETED: continue */ }
        }
        String note = "Returned by provider" + (reason == null || reason.isBlank() ? "" : ": " + reason.trim());
        // Status change and refund in one transaction, so it happens once even if the report is repeated or races.
        tx.executeWithoutResult(s -> {
            int won = remittances.transition(
                    reference,
                    OutboundRemittanceStatus.COMPLETED,
                    OutboundRemittanceStatus.RETURNED,
                    note
            );

            if (won != 1) {
                throw new IllegalStateException(
                        "Remittance " + reference
                                + " is no longer COMPLETED; return was not processed");
            }

            ledger.post(
                    SystemAccounts.REMITTANCE_CLEARING,
                    r.getSourceAccount(),
                    Money.ofMinor(r.getSourceAmountMinor(), Currency.NGN),
                    reference + "-REV"
            );
        });
        log.info("Outbound remittance {} returned by provider; {} refunded", reference, r.getSourceAmountMinor());
        return reload(reference);
    }

    @Override
    public List<OutboundRemittanceView> list(String status) {
        PageRequest page = PageRequest.of(0, 100);
        List<OutboundRemittance> rows;
        if (status == null || status.isBlank()) {
            rows = remittances.findAllByOrderByIdDesc(page);
        } else {
            OutboundRemittanceStatus parsed;
            try {
                parsed = OutboundRemittanceStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("status must be one of PENDING, COMPLETED, FAILED, RETURNED");
            }
            rows = remittances.findByStatusOrderByIdDesc(parsed, page);
        }
        return rows.stream().map(OutboundRemittanceService::toView).toList();
    }

    @Override
    public OutboundRemittancePage listForAccount(String accountNumber, int page, int size) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException("accountNumber is required");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        var result = remittances.findBySourceAccount(accountNumber, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new OutboundRemittancePage(result.getContent().stream()
                .map(OutboundRemittanceService::toView).toList(), page, size,
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    @Override
    public int reconcilePending(long olderThanSeconds, int limit) {
        Instant before = Instant.now().minus(Duration.ofSeconds(Math.max(0, olderThanSeconds)));
        List<OutboundRemittance> pending = remittances.findByStatusAndCreatedAtBeforeOrderByIdAsc(
                OutboundRemittanceStatus.PENDING, before, PageRequest.of(0, Math.max(1, limit)));
        int settled = 0;
        for (OutboundRemittance r : pending) {
            try {
                if (!"PENDING".equals(reconcile(r.getReference()).status())) {
                    settled++;
                }
            } catch (RuntimeException e) {
                log.warn("Reconciling outbound remittance {} failed: {}", r.getReference(), e.getMessage());
            }
        }
        return settled;
    }

    // ---------------------------------------------------------------- settling a remittance

    /** PENDING to COMPLETED, once. The held NGN stays in the clearing account, owed to the provider. */
    private void complete(String reference, String providerReference) {
        tx.executeWithoutResult(s -> {
            int won = remittances.transitionFromPending(reference, OutboundRemittanceStatus.COMPLETED,
                    null, Instant.now());
            if (won == 1 && providerReference != null) {
                remittances.setProviderReference(reference, providerReference);
            }
        });
        log.info("Outbound remittance {} completed", reference);
    }

    /**
     * PENDING to FAILED plus the refund, in ONE transaction. Whoever wins the PENDING-only update posts the
     * refund; a loser sees zero rows and does nothing. If the refund itself fails, the whole transaction
     * rolls back, the remittance stays PENDING, and a note says the refund needs attention.
     */
    private void failAndRefund(String reference, String reason) {
        try {
            tx.executeWithoutResult(s -> {
                OutboundRemittance r = remittances.findByReference(reference).orElseThrow();
                int won = remittances.transitionFromPending(reference, OutboundRemittanceStatus.FAILED,
                        reason, Instant.now());
                if (won == 1) {
                    ledger.post(SystemAccounts.REMITTANCE_CLEARING, r.getSourceAccount(),
                            Money.ofMinor(r.totalDebitMinor(), Currency.NGN), reference + "-REV");
                }
            });
            log.info("Outbound remittance {} failed and was refunded: {}", reference, reason);
        } catch (RuntimeException e) {
            remittances.noteWhilePending(reference,
                    "Provider confirmed nothing was sent but the refund failed and needs attention: " + e.getMessage());
            log.error("Refund for outbound remittance {} failed; it stays PENDING: {}", reference, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- mapping

    private OutboundRemittanceView reload(String reference) {
        return remittances.findByReference(reference)
                .map(OutboundRemittanceService::toView)
                .orElseThrow(() -> new ResourceNotFoundException("Outbound remittance not found: " + reference));
    }

    private OutboundRemittanceView replay(OutboundRemittance existing, OutboundRemittanceRequest request) {
        boolean same = existing.getQuoteId().equals(request.quoteId())
                && existing.getBeneficiaryAccount().equals(request.beneficiaryAccount().trim());
        if (!same) {
            throw new IllegalStateException("Idempotency-Key was already used with a different request");
        }
        return toView(existing);
    }

    private static ProviderQuoteView toView(ProviderQuote q, String providerName, List<String> tags) {
        return new ProviderQuoteView(q.getQuoteId(), q.getProviderId(), providerName, q.getSourceAccount(),
                q.getSourceAmountMinor(), q.getFeeMinor(), q.totalDebitMinor(), q.getDestinationCurrency(),
                q.getDestinationAmountMinor(), q.getFxRate(), q.getEtaMinutes(), tags, q.getExpiresAt());
    }

    private static OutboundRemittanceView toView(OutboundRemittance r) {
        return new OutboundRemittanceView(r.getReference(), r.getStatus().name(), r.getQuoteId(),
                r.getProviderId(), r.getProviderReference(), r.getSourceAccount(), r.getSourceAmountMinor(),
                r.getFeeMinor(), r.getDestinationCurrency(), r.getDestinationAmountMinor(), r.getFxRate(),
                r.getBeneficiaryName(), r.getBeneficiaryAccount(), r.getBeneficiaryBank(),
                r.getFailureReason(), r.getCreatedAt());
    }
}
