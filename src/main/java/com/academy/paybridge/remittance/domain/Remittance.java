package com.academy.paybridge.remittance.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "remittances")
public class Remittance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "source_account", nullable = false)
    private String sourceAccount;

    @Column(name = "beneficiary_account", nullable = false)
    private String beneficiaryAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_currency", nullable = false)
    private Currency sourceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_currency", nullable = false)
    private Currency destinationCurrency;

    @Column(name = "source_amount_minor", nullable = false)
    private long sourceAmountMinor;

    @Column(name = "destination_amount_minor", nullable = false)
    private long destinationAmountMinor;

    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "rate_type", nullable = false, length = 50)
    private String rateType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RemittanceStatus status = RemittanceStatus.PENDING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Remittance() {}

    public Remittance(String reference, String idempotencyKey, String sourceAccount, String beneficiaryAccount,
                      Currency sourceCurrency, Currency destinationCurrency,
                      long sourceAmountMinor, long destinationAmountMinor,
                      BigDecimal fxRate, String rateType) {
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.sourceAccount = sourceAccount;
        this.beneficiaryAccount = beneficiaryAccount;
        this.sourceCurrency = sourceCurrency;
        this.destinationCurrency = destinationCurrency;
        this.sourceAmountMinor = sourceAmountMinor;
        this.destinationAmountMinor = destinationAmountMinor;
        this.fxRate = fxRate;
        this.rateType = rateType;
    }

    public void markCompleted() {
        this.status = RemittanceStatus.COMPLETED;
        this.failureReason = null;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = RemittanceStatus.FAILED;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    public String getReference() { return reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSourceAccount() { return sourceAccount; }
    public String getBeneficiaryAccount() { return beneficiaryAccount; }
    public Currency getSourceCurrency() { return sourceCurrency; }
    public Currency getDestinationCurrency() { return destinationCurrency; }
    public long getSourceAmountMinor() { return sourceAmountMinor; }
    public long getDestinationAmountMinor() { return destinationAmountMinor; }
    public BigDecimal getFxRate() { return fxRate; }
    public String getRateType() { return rateType; }
    public RemittanceStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
