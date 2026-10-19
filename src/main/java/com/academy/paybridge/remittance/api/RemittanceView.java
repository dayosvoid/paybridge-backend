package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;
import java.time.Instant;

public record RemittanceView(
        String reference,
        String status,
        String sourceAccount,
        String beneficiaryAccount,
        Currency sourceCurrency,
        Currency destinationCurrency,
        long sourceAmountMinor,
        long destinationAmountMinor,
        BigDecimal rate,
        String rateType,
        String failureReason,
        Instant createdAt) {}
