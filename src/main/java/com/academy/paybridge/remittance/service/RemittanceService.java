package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.FxQuote;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerPosting;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceRequest;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.accounts.SystemAccounts;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * A remittance is two balanced ledger legs, posted in one atomic batch:
 * <pre>
 *   NGN leg:  sender        -> FX NGN pool     (source amount)
 *   USD leg:  FX USD pool   -> beneficiary     (destination amount)
 * </pre>
 * Each currency balances on its own. If either leg fails, neither happens.
 */
@Service
public class RemittanceService implements RemittanceApi {

    private static final Logger log = LoggerFactory.getLogger(RemittanceService.class);

    private final FxApi fx;
    private final AccountApi accounts;
    private final LedgerApi ledger;
    private final RemittanceRepository remittances;

    public RemittanceService(FxApi fx, AccountApi accounts, LedgerApi ledger, RemittanceRepository remittances) {
        this.fx = fx;
        this.accounts = accounts;
        this.ledger = ledger;
        this.remittances = remittances;
    }

    // NOTE: intentionally NOT @Transactional, like TransferService. The remittance record must be
    // committed before money moves, so a failed payment is recorded as FAILED instead of rolled away.
    @Override
    public RemittanceView initiate(RemittanceRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }

        var existing = remittances.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        // Validate and price before anything is stored: an invalid request leaves no record.
        if (request.sourceCurrency() != com.academy.paybridge.shared.money.Currency.NGN
                || request.destinationCurrency() != com.academy.paybridge.shared.money.Currency.USD) {
            throw new IllegalArgumentException(
                    "Remittances support NGN to USD only. To turn USD into NGN, use the conversion tool");
        }
        FxQuote quote = fx.quote(request.sourceCurrency(), request.destinationCurrency(),
                request.sourceAmountMinor());
        requireAccountIn(request.sourceAccount(), request.sourceCurrency());
        requireAccountIn(request.beneficiaryAccount(), request.destinationCurrency());

        Remittance remittance = new Remittance(
                "rem-" + UUID.randomUUID(), idempotencyKey,
                request.sourceAccount(), request.beneficiaryAccount(),
                quote.sourceCurrency(), quote.destinationCurrency(),
                quote.sourceAmountMinor(), quote.destinationAmountMinor(),
                quote.rate(), quote.rateType());
        try {
            remittance = remittances.saveAndFlush(remittance);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key won the race; return its remittance.
            return replay(remittances.findByIdempotencyKey(idempotencyKey).orElseThrow(), request);
        }

        try {
            ledger.postAll(legs(remittance));
            remittance.markCompleted();
            log.info("Remittance {} completed: {} {} -> {} {} at rate {} ({})", remittance.getReference(),
                    remittance.getSourceAmountMinor(), remittance.getSourceCurrency(),
                    remittance.getDestinationAmountMinor(), remittance.getDestinationCurrency(),
                    remittance.getFxRate(), remittance.getRateType());
        } catch (RuntimeException e) {
            // postAll is atomic, so nothing moved.
            remittance.markFailed(e.getMessage());
            log.warn("Remittance {} failed, nothing was moved: {}", remittance.getReference(), e.getMessage());
        }
        return toView(remittances.save(remittance));
    }

    @Override
    public RemittanceView getByReference(String reference) {
        return remittances.findByReference(reference)
                .map(RemittanceService::toView)
                .orElseThrow(() -> new ResourceNotFoundException("Remittance not found: " + reference));
    }

    private static List<LedgerPosting> legs(Remittance r) {
        return List.of(
                new LedgerPosting(r.getSourceAccount(), SystemAccounts.FX_NGN_POOL,
                        Money.ofMinor(r.getSourceAmountMinor(), r.getSourceCurrency()),
                        r.getReference() + "-NGN"),
                new LedgerPosting(SystemAccounts.FX_USD_POOL, r.getBeneficiaryAccount(),
                        Money.ofMinor(r.getDestinationAmountMinor(), r.getDestinationCurrency()),
                        r.getReference() + "-USD"));
    }

    private void requireAccountIn(String accountNumber, com.academy.paybridge.shared.money.Currency currency) {
        if (accounts.findByNumber(accountNumber, currency).isPresent()) {
            return;
        }
        if (!accounts.exists(accountNumber)) {
            throw new ResourceNotFoundException("Account not found: " + accountNumber);
        }
        throw new IllegalArgumentException("Account " + accountNumber + " has no " + currency + " balance");
    }

    private RemittanceView replay(Remittance existing, RemittanceRequest request) {
        boolean sameRequest = existing.getSourceAccount().equals(request.sourceAccount())
                && existing.getBeneficiaryAccount().equals(request.beneficiaryAccount())
                && existing.getSourceAmountMinor() == request.sourceAmountMinor()
                && existing.getSourceCurrency() == request.sourceCurrency()
                && existing.getDestinationCurrency() == request.destinationCurrency();
        if (!sameRequest) {
            throw new IllegalStateException("Idempotency-Key was already used with a different request");
        }
        return toView(existing);
    }

    private static RemittanceView toView(Remittance r) {
        return new RemittanceView(r.getReference(), r.getStatus().name(), r.getSourceAccount(),
                r.getBeneficiaryAccount(), r.getSourceCurrency(), r.getDestinationCurrency(),
                r.getSourceAmountMinor(), r.getDestinationAmountMinor(), r.getFxRate(), r.getRateType(),
                r.getFailureReason(), r.getCreatedAt());
    }
}
