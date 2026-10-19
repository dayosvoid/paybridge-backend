package com.academy.paybridge.settlement.domain;

import com.academy.paybridge.settlement.api.BatchStatus;
import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "settlement_batches")
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(nullable = false, length = 50)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchStatus status = BatchStatus.POSTED;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;

    protected SettlementBatch() {}

    public SettlementBatch(String reference, String provider, Currency currency,
                           long totalMinor, int itemCount) {
        this.reference = reference;
        this.provider = provider;
        this.currency = currency;
        this.totalMinor = totalMinor;
        this.itemCount = itemCount;
    }

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public String getProvider() { return provider; }
    public Currency getCurrency() { return currency; }
    public BatchStatus getStatus() { return status; }
    public long getTotalMinor() { return totalMinor; }
    public int getItemCount() { return itemCount; }
    public Instant getCreatedAt() { return createdAt; }
}