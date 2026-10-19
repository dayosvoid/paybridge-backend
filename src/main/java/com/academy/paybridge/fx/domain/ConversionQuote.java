package com.academy.paybridge.fx.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "conversion_quotes")
public class ConversionQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ConversionQuote() {}

    public ConversionQuote(String quoteId, String accountNumber, Currency sourceCurrency,
                           Currency destinationCurrency, long sourceAmountMinor, long destinationAmountMinor,
                           BigDecimal fxRate, String rateType, Instant expiresAt) {
        this.quoteId = quoteId;
        this.accountNumber = accountNumber;
        this.sourceCurrency = sourceCurrency;
        this.destinationCurrency = destinationCurrency;
        this.sourceAmountMinor = sourceAmountMinor;
        this.destinationAmountMinor = destinationAmountMinor;
        this.fxRate = fxRate;
        this.rateType = rateType;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }

    public String getQuoteId() { return quoteId; }
    public String getAccountNumber() { return accountNumber; }
    public Currency getSourceCurrency() { return sourceCurrency; }
    public Currency getDestinationCurrency() { return destinationCurrency; }
    public long getSourceAmountMinor() { return sourceAmountMinor; }
    public long getDestinationAmountMinor() { return destinationAmountMinor; }
    public BigDecimal getFxRate() { return fxRate; }
    public String getRateType() { return rateType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
