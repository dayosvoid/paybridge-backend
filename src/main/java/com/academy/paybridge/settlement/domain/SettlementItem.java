package com.academy.paybridge.settlement.domain;

import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;

import java.time.Instant;

/** One transfer inside one batch. The unique constraint means a transfer can be settled only once. */
@Entity
@Table(name = "settlement_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_settlement_items_transfer_reference",
                columnNames = "transfer_reference"))
public class SettlementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "transfer_reference", nullable = false)
    private String transferReference;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SettlementItem() {}

    public SettlementItem(Long batchId, String transferReference, long amountMinor, Currency currency) {
        this.batchId = batchId;
        this.transferReference = transferReference;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public Long getId() { return id; }
    public Long getBatchId() { return batchId; }
    public String getTransferReference() { return transferReference; }
    public long getAmountMinor() { return amountMinor; }
    public Currency getCurrency() { return currency; }
}
