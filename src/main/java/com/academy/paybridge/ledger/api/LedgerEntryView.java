package com.academy.paybridge.ledger.api;

import com.academy.paybridge.shared.money.Currency;

import java.time.Instant;

public record LedgerEntryView(
        long id,
        String type,                 // DEBIT or CREDIT, from this account's point of view
        EntryKind kind,
        long amountMinor,
        Currency currency,
        String reference,
        String transferReference,    // the transfer this entry belongs to
        Instant createdAt,
        Long balanceAfterMinor) {}   // null when the statement is filtered