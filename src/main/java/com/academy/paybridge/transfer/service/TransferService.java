package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerPosting;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.TransferApi;
import com.academy.paybridge.transfer.api.TransferPage;
import com.academy.paybridge.transfer.api.TransferRequest;
import com.academy.paybridge.transfer.api.TransferView;
import com.academy.paybridge.transfer.client.GatewayResult;
import com.academy.paybridge.transfer.client.GatewayStatus;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.TransferInstruction;
import com.academy.paybridge.transfer.domain.SystemAccounts;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService implements TransferApi {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transfers;
    private final AccountApi accounts;
    private final LedgerApi ledger;
    private final TransferGateway gateway;
    private final FeeCalculator feeCalculator;

    public TransferService(TransferRepository transfers, AccountApi accounts, LedgerApi ledger,
                           TransferGateway gateway, FeeCalculator feeCalculator) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
        this.gateway = gateway;
        this.feeCalculator = feeCalculator;
    }

    // NOTE: intentionally NOT @Transactional
    @Override
    public TransferView initiate(TransferRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        Optional<Transfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        log.info("Transfer requested: {} -> {} amountMinor={} external={}",
                request.sourceAccount(), request.destinationAccount(), request.amountMinor(),
                request.bankCode() != null && !request.bankCode().isBlank());

        String bankCode = normalize(request.bankCode());
        boolean external = bankCode != null;

        requireAccount(request.sourceAccount());
        String recipientName = null;
        if (external) {
            recipientName = gateway.resolveAccount(request.destinationAccount(), bankCode).accountName();
        } else {
            requireAccount(request.destinationAccount());
        }

        // Fee and tax apply to external transfers only. Tax is charged on the fee.
        FeeCalculator.Fees fees = external
                ? feeCalculator.forExternal(request.amountMinor())
                : FeeCalculator.Fees.NONE;

        Transfer transfer = new Transfer(
                "trf-" + UUID.randomUUID(), idempotencyKey,
                request.sourceAccount(), request.destinationAccount(),
                request.amountMinor(), request.currency(), bankCode,
                fees.feeMinor(), fees.taxMinor());
        try {
            transfer = transfers.saveAndFlush(transfer);
        } catch (DataIntegrityViolationException e) {
            Transfer winner = transfers.findByIdempotencyKey(idempotencyKey).orElseThrow();
            return replay(winner, request);
        }

        Money amount = Money.ofMinor(request.amountMinor(), request.currency());
        if (external) {
            processExternal(transfer, amount, recipientName);
        } else {
            processInternal(transfer, amount);
        }
        return toView(transfers.save(transfer));
    }

    private void processInternal(Transfer t, Money amount) {
        try {
            ledger.post(t.getSourceAccount(), t.getDestinationAccount(), amount, t.getReference());
            t.markSuccess(null);
        } catch (RuntimeException e) {
            t.markFailed(e.getMessage());
        }
    }

    private void processExternal(Transfer t, Money amount, String recipientName) {
        // 1. Hold principal, fee and tax in one atomic batch. If the sender cannot cover all
        //    three, nothing moves.
        try {
            ledger.postAll(holdPostings(t));
        } catch (RuntimeException e) {
            t.markFailed(e.getMessage());
            return;
        }

        // 2. Ask the provider to send the principal
        GatewayResult result;
        try {
            result = gateway.send(new TransferInstruction(
                    t.getReference(), t.getDestinationAccount(), t.getBankCode(), recipientName, amount));
        } catch (RuntimeException e) {
            // Unknown outcome: the money may have left. Do NOT reverse. Wait for confirmation.
            t.markPending(null, "Provider did not confirm: " + e.getMessage());
            return;
        }

        // 3. Act on the provider's answer
        switch (result.status()) {
            case SUCCESS -> t.markSuccess(result.providerReference());
            case PENDING -> t.markPending(result.providerReference(), result.message());
            case FAILED -> {
                // The provider confirms that the transfer was not sent.
                try {
                    ledger.postAll(reversalPostings(t));
                    t.markFailed(result.message());
                } catch (RuntimeException e) {
                    log.warn("Refund failed for transfer {}: {}",
                            t.getReference(), e.getMessage());

                    t.markPending(
                            result.providerReference(),
                            "Refund failed: " + e.getMessage()
                    );
                }
            }
        }
    }

    @Override
    public TransferView getByReference(String reference) {
        return transfers.findByReference(reference)
                .map(this::toView)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + reference));
    }

    @Transactional
    public void settleFromProvider(String reference, GatewayStatus outcome, String message) {
        Optional<Transfer> found = transfers.findForUpdateByReference(reference);
        if (found.isEmpty()) {
            return;                                  // not ours: acknowledge and ignore
        }
        Transfer t = found.get();
        if (t.getStatus() != TransferStatus.PENDING || t.getBankCode() == null) {
            return;                                  // already concluded: duplicate delivery is a no-op
        }
        switch (outcome) {
            case SUCCESS -> t.markSuccess(t.getProviderReference());
            case FAILED -> {
                try {
                    ledger.postAll(reversalPostings(t));
                    t.markFailed(message);
                } catch (RuntimeException e) {
                    log.warn("Reconciliation refund failed for transfer {}: {}",
                            t.getReference(), e.getMessage());

                    t.markPending(
                            t.getProviderReference(),
                            "Refund failed: " + e.getMessage()
                    );
                }
            }
            case PENDING -> { }                      // still not conclusive
        }
    }

    /** Customer pays principal to settlement, fee to fee income, tax to tax payable. */
    private List<LedgerPosting> holdPostings(Transfer t) {
        List<LedgerPosting> postings = new ArrayList<>();
        postings.add(new LedgerPosting(t.getSourceAccount(), SystemAccounts.SETTLEMENT,
                Money.ofMinor(t.getAmountMinor(), t.getCurrency()), t.getReference()));
        if (t.getFeeMinor() > 0) {
            postings.add(new LedgerPosting(t.getSourceAccount(), SystemAccounts.FEE_INCOME,
                    Money.ofMinor(t.getFeeMinor(), t.getCurrency()), t.getReference() + "-FEE"));
        }
        if (t.getTaxMinor() > 0) {
            postings.add(new LedgerPosting(t.getSourceAccount(), SystemAccounts.TAX_PAYABLE,
                    Money.ofMinor(t.getTaxMinor(), t.getCurrency()), t.getReference() + "-TAX"));
        }
        return postings;
    }

    /** The exact mirror of the hold, returning everything to the customer. */
    private List<LedgerPosting> reversalPostings(Transfer t) {
        List<LedgerPosting> postings = new ArrayList<>();
        postings.add(new LedgerPosting(SystemAccounts.SETTLEMENT, t.getSourceAccount(),
                Money.ofMinor(t.getAmountMinor(), t.getCurrency()), t.getReference() + "-REV"));
        if (t.getFeeMinor() > 0) {
            postings.add(new LedgerPosting(SystemAccounts.FEE_INCOME, t.getSourceAccount(),
                    Money.ofMinor(t.getFeeMinor(), t.getCurrency()), t.getReference() + "-FEE-REV"));
        }
        if (t.getTaxMinor() > 0) {
            postings.add(new LedgerPosting(SystemAccounts.TAX_PAYABLE, t.getSourceAccount(),
                    Money.ofMinor(t.getTaxMinor(), t.getCurrency()), t.getReference() + "-TAX-REV"));
        }
        return postings;
    }

    private TransferView replay(Transfer existing, TransferRequest request) {
        boolean sameRequest = existing.getSourceAccount().equals(request.sourceAccount())
                && existing.getDestinationAccount().equals(request.destinationAccount())
                && existing.getAmountMinor() == request.amountMinor()
                && existing.getCurrency() == request.currency()
                && Objects.equals(existing.getBankCode(), normalize(request.bankCode()));
        if (!sameRequest) {
            throw new IllegalStateException("Idempotency-Key was already used with a different request");
        }
        return toView(existing);
    }

    private static String normalize(String bankCode) {
        return (bankCode == null || bankCode.isBlank()) ? null : bankCode.trim();
    }

    private void requireAccount(String accountNumber) {
        if (!accounts.exists(accountNumber)) {
            throw new ResourceNotFoundException("Account not found: " + accountNumber);
        }
    }

    private TransferView toView(Transfer t) {
        return new TransferView(t.getReference(), t.getStatus().name(), t.getSourceAccount(),
                t.getDestinationAccount(), t.getAmountMinor(), t.getCurrency(), t.getFailureReason(),
                t.getFeeMinor(), t.getTaxMinor());
    }


    @Override
    public TransferPage list(String account, String status, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        requireAccount(account);

        TransferStatus wanted = null;
        if (status != null && !status.isBlank()) {
            try {
                wanted = TransferStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("status must be PENDING, SUCCESS or FAILED");
            }
        }

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Transfer> result = wanted == null
                ? transfers.findInvolving(account, pageable)
                : transfers.findInvolvingWithStatus(account, wanted, pageable);

        return new TransferPage(result.getContent().stream().map(this::toView).toList(),
                page, size, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }
}