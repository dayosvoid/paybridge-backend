package com.academy.paybridge.transfer.api;

import com.academy.paybridge.shared.money.Currency;

public record TransferView(
        String reference, String status, String sourceAccount, String destinationAccount,
        long amountMinor, Currency currency, String failureReason,
        long feeMinor, long taxMinor) {}