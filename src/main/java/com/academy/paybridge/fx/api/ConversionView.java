package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;
import java.time.Instant;

public record ConversionView(
        String reference,
        String status,
        String quoteId,
        String accountNumber,
        Currency sourceCurrency,
        Currency destinationCurrency,
        long sourceAmountMinor,
        long destinationAmountMinor,
        BigDecimal rate,
        String rateType,
        String failureReason,
        Instant createdAt) {}
