package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;

/**
 * A price for converting money. The rate is a configured demo rate, not a live market rate:
 * {@code rateType} says so on every quote.
 */
public record FxQuote(
        Currency sourceCurrency,
        Currency destinationCurrency,
        long sourceAmountMinor,
        long destinationAmountMinor,
        BigDecimal rate,
        String rateType) {}
