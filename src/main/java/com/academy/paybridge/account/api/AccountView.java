package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.money.Currency;

public record AccountView(
        String accountNumber,
        Currency currency,
        long balanceMinor,
        Long customerId,
        AccountStatus status,
        String bankCode,
        String bankName,
        String accountName) {}