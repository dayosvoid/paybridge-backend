package com.academy.paybridge.fx.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "conversions")
public class Conversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    /** Unique: a quote converts at most once. */
    @Column(name = "quote_id", nullable = false, unique = true)
    private String quoteId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

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
    private ConversionStatus status = ConversionStatus.PENDING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Conversion() {}

    public Conversion(String reference, String idempotencyKey, ConversionQuote quote) {
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.quoteId = quote.getQuoteId();
        this.accountNumber = quote.getAccountNumber();
        this.sourceCurrency = quote.getSourceCurrency();
        this.destinationCurrency = quote.getDestinationCurrency();
        this.sourceAmountMinor = quote.getSourceAmountMinor();
        this.destinationAmountMinor = quote.getDestinationAmountMinor();
        this.fxRate = quote.getFxRate();
        this.rateType = quote.getRateType();
    }

    public void markCompleted() {
        this.status = ConversionStatus.COMPLETED;
        this.failureReason = null;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = ConversionStatus.FAILED;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    }

    public String getReference() { return reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getQuoteId() { return quoteId; }
    public String getAccountNumber() { return accountNumber; }
    public Currency getSourceCurrency() { return sourceCurrency; }
    public Currency getDestinationCurrency() { return destinationCurrency; }
    public long getSourceAmountMinor() { return sourceAmountMinor; }
    public long getDestinationAmountMinor() { return destinationAmountMinor; }
    public BigDecimal getFxRate() { return fxRate; }
    public String getRateType() { return rateType; }
    public ConversionStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
