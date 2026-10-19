package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.remittance.api.InboundPaymentCommand;
import com.academy.paybridge.remittance.api.InboundPaymentView;
import com.academy.paybridge.remittance.api.InboundRemittanceApi;
import com.academy.paybridge.remittance.domain.InboundPayment;
import com.academy.paybridge.remittance.domain.InboundPaymentStatus;
import com.academy.paybridge.remittance.repository.InboundPaymentRepository;
import com.academy.paybridge.shared.accounts.SystemAccounts;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Credits incoming USD to the customer's own USD balance. The USD comes out of the FX USD pool, which
 * stands for PayBridge's USD funds held at the provider, so one ledger leg is enough:
 * <pre>
 *   FX USD pool  -> customer USD balance      (reference "&lt;rin-ref&gt;-IN")
 * </pre>
 */
@Service
public class InboundRemittanceService implements InboundRemittanceApi {

    private static final Logger log = LoggerFactory.getLogger(InboundRemittanceService.class);
    private static final int LIST_LIMIT = 100;

    private final AccountApi accounts;
    private final LedgerApi ledger;
    private final InboundPaymentRepository payments;
    private final TransactionTemplate tx;

    public InboundRemittanceService(AccountApi accounts, LedgerApi ledger, InboundPaymentRepository payments,
                                    PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        this.accounts = accounts;
        this.ledger = ledger;
        this.payments = payments;
    }

    // NOTE: intentionally NOT @Transactional, like RemittanceService. The payment record is committed
    // before money moves, so an uncreditable payment stays visible instead of being rolled away.
    @Override
    public InboundPaymentView receive(InboundPaymentCommand c) {
        if (c == null) {
            throw new IllegalArgumentException("payment is required");
        }
        if (c.providerReference() == null || c.providerReference().isBlank()) {
            throw new IllegalArgumentException("providerReference is required");
        }
        if (c.accountNumber() == null || c.accountNumber().isBlank()) {
            throw new IllegalArgumentException("accountNumber is required");
        }
        if (c.currency() == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (c.amountMinor() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }

        var existing = payments.findByProviderReference(c.providerReference());
        if (existing.isPresent()) {
            return replay(existing.get(), c);
        }

        InboundPayment payment = new InboundPayment("rin-" + UUID.randomUUID(), c.providerReference(),
                c.accountNumber(), c.currency(), c.amountMinor(), c.senderName());
        try {
            payment = payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // The provider delivered twice at once; the other delivery won.
            return replay(payments.findByProviderReference(c.providerReference()).orElseThrow(), c);
        }

        String problem = matchProblem(c);
        if (problem != null) {
            payment.markUnmatched(problem);
            log.warn("Inbound payment {} (provider {}) is unmatched: {}", payment.getReference(),
                    c.providerReference(), problem);
            return toView(payments.save(payment));
        }

        try {
            ledger.post(SystemAccounts.FX_USD_POOL, c.accountNumber(),
                    Money.ofMinor(c.amountMinor(), Currency.USD), payment.getReference() + "-IN");
            payment.markCredited();
            log.info("Inbound payment {} credited: {} USD minor to {}", payment.getReference(),
                    c.amountMinor(), c.accountNumber());
        } catch (RuntimeException e) {
            // post is atomic, so nothing moved.
            payment.markFailed(e.getMessage());
            log.warn("Inbound payment {} could not be credited: {}", payment.getReference(), e.getMessage());
        }
        return toView(payments.save(payment));
    }

    @Override
    public InboundPaymentView reverse(String providerReference, String reason) {
        if (providerReference == null || providerReference.isBlank()) {
            throw new IllegalArgumentException("providerReference is required");
        }
        InboundPayment p = payments.findByProviderReference(providerReference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inbound payment not found for provider reference: " + providerReference));
        String why = "Reversed by provider" + (reason == null || reason.isBlank() ? "" : ": " + reason.trim());
        String reference = p.getReference();

        switch (p.getStatus()) {
            case REVERSED -> { return toView(p); }
            case RECEIVED -> throw new IllegalStateException(
                    "Payment " + providerReference + " is still being processed; retry shortly");
            case UNMATCHED, FAILED -> {
                // Never credited, so there is nothing to take back: just cancel it.
                payments.transition(reference, List.of(InboundPaymentStatus.UNMATCHED, InboundPaymentStatus.FAILED),
                        InboundPaymentStatus.REVERSED, why + " (it had not been credited)");
            }
            default -> {
                // CREDITED or REVERSAL_FAILED: take the money back. The status change and the ledger entry are
                // one transaction, so it happens once; whoever loses a race sees zero rows and does nothing.
                try {
                    tx.executeWithoutResult(s -> {
                        int won = payments.transition(reference,
                                List.of(InboundPaymentStatus.CREDITED, InboundPaymentStatus.REVERSAL_FAILED),
                                InboundPaymentStatus.REVERSED, why);
                        if (won == 1) {
                            ledger.post(p.getAccountNumber(), SystemAccounts.FX_USD_POOL,
                                    Money.ofMinor(p.getAmountMinor(), Currency.USD), reference + "-REV");
                        }
                    });
                    log.info("Inbound payment {} reversed: {} USD minor taken back from {}", reference,
                            p.getAmountMinor(), p.getAccountNumber());
                } catch (RuntimeException e) {
                    payments.transition(reference,
                            List.of(InboundPaymentStatus.CREDITED, InboundPaymentStatus.REVERSAL_FAILED),
                            InboundPaymentStatus.REVERSAL_FAILED,
                            why + ". Could not take the money back: " + e.getMessage());
                    log.error("Inbound payment {} was reversed by the provider but the customer's USD could not "
                            + "be taken back: {}", reference, e.getMessage());
                }
            }
        }
        return getByReference(reference);
    }

    @Override
    public InboundPaymentView getByReference(String reference) {
        return payments.findByReference(reference)
                .map(InboundRemittanceService::toView)
                .orElseThrow(() -> new ResourceNotFoundException("Inbound payment not found: " + reference));
    }

    @Override
    public List<InboundPaymentView> list(String status) {
        PageRequest page = PageRequest.of(0, LIST_LIMIT);
        List<InboundPayment> rows;
        if (status == null || status.isBlank()) {
            rows = payments.findAllByOrderByIdDesc(page);
        } else {
            InboundPaymentStatus parsed;
            try {
                parsed = InboundPaymentStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "status must be one of RECEIVED, CREDITED, UNMATCHED, FAILED, REVERSED, REVERSAL_FAILED");
            }
            rows = payments.findByStatusOrderByIdDesc(parsed, page);
        }
        return rows.stream().map(InboundRemittanceService::toView).toList();
    }

    /** Null when the payment can be credited; otherwise why it cannot be matched to an account. */
    private String matchProblem(InboundPaymentCommand c) {
        if (c.currency() != Currency.USD) {
            return "Only USD inbound payments are supported, got " + c.currency();
        }
        if (accounts.findByNumber(c.accountNumber(), Currency.USD).isPresent()) {
            return null;
        }
        return accounts.exists(c.accountNumber())
                ? "Account " + c.accountNumber() + " has no USD balance"
                : "Account not found: " + c.accountNumber();
    }

    private static InboundPaymentView replay(InboundPayment existing, InboundPaymentCommand c) {
        boolean same = existing.getAccountNumber().equals(c.accountNumber())
                && existing.getAmountMinor() == c.amountMinor()
                && existing.getCurrency() == c.currency();
        if (!same) {
            throw new IllegalStateException(
                    "Provider reference " + c.providerReference() + " was already used for a different payment");
        }
        return toView(existing);
    }

    private static InboundPaymentView toView(InboundPayment p) {
        return new InboundPaymentView(p.getReference(), p.getProviderReference(), p.getStatus().name(),
                p.getAccountNumber(), p.getCurrency(), p.getAmountMinor(), p.getSenderName(),
                p.getFailureReason(), p.getReceivedAt(), p.getCreditedAt());
    }
}
