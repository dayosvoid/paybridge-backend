package com.academy.paybridge.ledger.api;

import java.util.List;

public record StatementPage(
        String accountNumber,
        long currentBalanceMinor,
        List<LedgerEntryView> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext) {}