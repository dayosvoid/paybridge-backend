package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** The sender chooses the source amount. The destination amount is always computed by PayBridge. */
public record RemittanceRequest(
        @NotBlank String sourceAccount,
        @NotBlank String beneficiaryAccount,
        @Positive long sourceAmountMinor,
        @NotNull Currency sourceCurrency,
        @NotNull Currency destinationCurrency) {}
