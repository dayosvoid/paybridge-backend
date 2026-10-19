package com.academy.paybridge.ledger.api;


import com.academy.paybridge.ledger.api.LedgerEntryView;
import com.academy.paybridge.shared.money.Currency;

import java.util.List;

public record StatementPage(
        String accountNumber,
        Currency currency,
        long currentBalanceMinor,
        List<LedgerEntryView> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext) {}