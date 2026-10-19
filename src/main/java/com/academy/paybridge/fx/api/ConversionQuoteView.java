package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;
import java.time.Instant;

public record ConversionQuoteView(
        String quoteId,
        String accountNumber,
        Currency sourceCurrency,
        Currency destinationCurrency,
        long sourceAmountMinor,
        long destinationAmountMinor,
        BigDecimal rate,
        String rateType,
        Instant expiresAt) {}
