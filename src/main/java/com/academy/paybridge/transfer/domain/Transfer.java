package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "transfers", uniqueConstraints = {
        @UniqueConstraint(name = "uq_transfers_reference", columnNames = "reference"),
        @UniqueConstraint(name = "uq_transfers_idempotency_key", columnNames = "idempotency_key")
})
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "source_account", nullable = false, updatable = false, length = 10)
    private String sourceAccount;

    @Column(name = "destination_account", nullable = false, updatable = false, length = 10)
    private String destinationAccount;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Currency currency;

    @Column(name = "bank_code", updatable = false, length = 10)
    private String bankCode;

    @Column(name = "fee_minor", nullable = false, updatable = false)
    private long feeMinor;

    @Column(name = "tax_minor", nullable = false, updatable = false)
    private long taxMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status = TransferStatus.PENDING;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Column(name = "settlement_batch_reference")
    private String settlementBatchReference;

    @Version
    private Long version;

    protected Transfer() {}

    public Transfer(String reference, String idempotencyKey, String sourceAccount,
                    String destinationAccount, long amountMinor, Currency currency,
                    String bankCode, long feeMinor, long taxMinor) {
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.bankCode = bankCode;
        this.feeMinor = feeMinor;
        this.taxMinor = taxMinor;
    }

    public void markSuccess(String providerReference) {
        if (this.status != TransferStatus.SUCCESS) {
            this.succeededAt = Instant.now();
        }
        this.status = TransferStatus.SUCCESS;
        this.providerReference = providerReference;
        this.failureReason = null;
    }

    public void markPending(String providerReference, String message) {
        this.status = TransferStatus.PENDING;
        this.providerReference = providerReference;
        this.failureReason = message;
    }

    public void markFailed(String reason) {
        this.status = TransferStatus.FAILED;
        this.failureReason = reason == null ? "Unknown failure"
                : reason.substring(0, Math.min(reason.length(), 500));
    }

    public boolean isSettlementEligible(Currency currency, Instant succeededBefore) {
        return status == TransferStatus.SUCCESS
                && bankCode != null
                && this.currency == currency
                && settlementBatchReference == null
                && succeededAt != null
                && !succeededAt.isAfter(succeededBefore);
    }

    public void markSettled(String batchReference) {
        if (settlementBatchReference != null) {
            throw new IllegalStateException(
                    "Transfer " + reference + " is already in settlement batch " + settlementBatchReference);
        }
        if (status != TransferStatus.SUCCESS || bankCode == null) {
            throw new IllegalStateException(
                    "Only successful external transfers can be settled: " + reference);
        }
        this.settlementBatchReference = batchReference;
    }

    public String getReference() {
        return reference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getSourceAccount() {
        return sourceAccount;
    }

    public String getDestinationAccount() {
        return destinationAccount;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getBankCode() {
        return bankCode;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public long getTaxMinor() {
        return taxMinor;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSucceededAt() {
        return succeededAt;
    }

    public String getSettlementBatchReference() {
        return settlementBatchReference;
    }
}