package com.academy.paybridge.remittance.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "outbound_remittances")
public class OutboundRemittance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    /** Unique: a quote is sent at most once. */
    @Column(name = "quote_id", nullable = false, unique = true)
    private String quoteId;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "source_account", nullable = false)
    private String sourceAccount;

    @Column(name = "source_amount_minor", nullable = false)
    private long sourceAmountMinor;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_currency", nullable = false)
    private Currency destinationCurrency;

    @Column(name = "destination_amount_minor", nullable = false)
    private long destinationAmountMinor;

    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "beneficiary_name", nullable = false)
    private String beneficiaryName;

    @Column(name = "beneficiary_account", nullable = false)
    private String beneficiaryAccount;

    @Column(name = "beneficiary_bank")
    private String beneficiaryBank;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboundRemittanceStatus status = OutboundRemittanceStatus.PENDING;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected OutboundRemittance() {}

    public OutboundRemittance(String reference, String idempotencyKey, ProviderQuote quote,
                              String beneficiaryName, String beneficiaryAccount, String beneficiaryBank) {
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.quoteId = quote.getQuoteId();
        this.providerId = quote.getProviderId();
        this.sourceAccount = quote.getSourceAccount();
        this.sourceAmountMinor = quote.getSourceAmountMinor();
        this.feeMinor = quote.getFeeMinor();
        this.destinationCurrency = quote.getDestinationCurrency();
        this.destinationAmountMinor = quote.getDestinationAmountMinor();
        this.fxRate = quote.getFxRate();
        this.beneficiaryName = beneficiaryName;
        this.beneficiaryAccount = beneficiaryAccount;
        this.beneficiaryBank = beneficiaryBank;
    }

    public Long getId() { return id; }

    public long totalDebitMinor() { return Math.addExact(sourceAmountMinor, feeMinor); }

    public String getReference() { return reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getQuoteId() { return quoteId; }
    public String getProviderId() { return providerId; }
    public String getProviderReference() { return providerReference; }
    public String getSourceAccount() { return sourceAccount; }
    public long getSourceAmountMinor() { return sourceAmountMinor; }
    public long getFeeMinor() { return feeMinor; }
    public Currency getDestinationCurrency() { return destinationCurrency; }
    public long getDestinationAmountMinor() { return destinationAmountMinor; }
    public BigDecimal getFxRate() { return fxRate; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getBeneficiaryAccount() { return beneficiaryAccount; }
    public String getBeneficiaryBank() { return beneficiaryBank; }
    public OutboundRemittanceStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
