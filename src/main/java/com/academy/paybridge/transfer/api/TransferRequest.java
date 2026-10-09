package com.academy.paybridge.transfer.api;

import com.academy.paybridge.shared.money.Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotBlank String sourceAccount,
        @NotBlank String destinationAccount,
        @Positive long amountMinor,
        @NotNull Currency currency,
        String bankCode) {          // null = internal transfer

    public TransferRequest(String sourceAccount, String destinationAccount,
                           long amountMinor, Currency currency) {
        this(sourceAccount, destinationAccount, amountMinor, currency, null);
    }
}
