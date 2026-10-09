package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String sourceAccount;

    @Column(nullable = false)
    private String destinationAccount;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "fee_minor", nullable = false)
    @ColumnDefault("0")
    private long feeMinor;

    @Column(name = "tax_minor", nullable = false)
    @ColumnDefault("0")
    private long taxMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status = TransferStatus.PENDING;

    @Column(length = 500)
    private String failureReason;

    @Column
    private String bankCode;

    @Column
    private String providerReference;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;

    protected Transfer() {}

    public Transfer(
            String reference,
            String idempotencyKey,
            String sourceAccount,
            String destinationAccount,
            long amountMinor,
            Currency currency,
            String bankCode,
            long feeMinor,
            long taxMinor
    ) {
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
        this.status = TransferStatus.SUCCESS;
        this.providerReference = providerReference;
        this.failureReason = null;
    }

    public void markPending(String providerReference, String note) {
        this.status = TransferStatus.PENDING;
        this.providerReference = providerReference;
        this.failureReason = note;
    }

    public void markFailed(String reason) {
        this.status = TransferStatus.FAILED;
        this.failureReason = reason == null
                ? "Unknown error"
                : reason.substring(0, Math.min(reason.length(), 500));
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

    public long getFeeMinor() {
        return feeMinor;
    }

    public long getTaxMinor() {
        return taxMinor;
    }

    public Currency getCurrency() {
        return currency;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getBankCode() {
        return bankCode;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}