package com.academy.paybridge.remittance.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "provider_quotes")
public class ProviderQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quote_id", nullable = false, unique = true)
    private String quoteId;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

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

    @Column(name = "eta_minutes", nullable = false)
    private int etaMinutes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ProviderQuote() {}

    public ProviderQuote(String quoteId, String providerId, String sourceAccount, long sourceAmountMinor,
                         long feeMinor, Currency destinationCurrency, long destinationAmountMinor,
                         BigDecimal fxRate, int etaMinutes, Instant expiresAt) {
        this.quoteId = quoteId;
        this.providerId = providerId;
        this.sourceAccount = sourceAccount;
        this.sourceAmountMinor = sourceAmountMinor;
        this.feeMinor = feeMinor;
        this.destinationCurrency = destinationCurrency;
        this.destinationAmountMinor = destinationAmountMinor;
        this.fxRate = fxRate;
        this.etaMinutes = etaMinutes;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public long totalDebitMinor() { return Math.addExact(sourceAmountMinor, feeMinor); }

    public String getQuoteId() { return quoteId; }
    public String getProviderId() { return providerId; }
    public String getSourceAccount() { return sourceAccount; }
    public long getSourceAmountMinor() { return sourceAmountMinor; }
    public long getFeeMinor() { return feeMinor; }
    public Currency getDestinationCurrency() { return destinationCurrency; }
    public long getDestinationAmountMinor() { return destinationAmountMinor; }
    public BigDecimal getFxRate() { return fxRate; }
    public int getEtaMinutes() { return etaMinutes; }
    public Instant getExpiresAt() { return expiresAt; }
}
