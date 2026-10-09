package com.academy.paybridge.ledger.domain;

import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType type;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(nullable = false)
    private String reference;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected LedgerEntry() {}

    public LedgerEntry(String accountNumber, EntryType type, Money amount, String reference) {
        this.accountNumber = accountNumber;
        this.type = type;
        this.amountMinor = amount.minorUnits();
        this.currency = amount.currency();
        this.reference = reference;
    }

    public String getAccountNumber() { return accountNumber; }
    public EntryType getType() { return type; }
    public long getAmountMinor() { return amountMinor; }
    public String getReference() { return reference; }
    public Long getId() { return id; }
    public Currency getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
