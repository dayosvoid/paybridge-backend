package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One provider's offer. The customer pays {@code sourceAmountMinor + feeMinor} in NGN and the
 * recipient receives {@code destinationAmountMinor}. Tags: BEST_RATE (recipient gets the most),
 * FASTEST, LOWEST_FEE.
 */
public record ProviderQuoteView(
        String quoteId,
        String providerId,
        String providerName,
        String sourceAccount,
        long sourceAmountMinor,
        long feeMinor,
        long totalDebitMinor,
        Currency destinationCurrency,
        long destinationAmountMinor,
        BigDecimal rate,
        int etaMinutes,
        List<String> tags,
        Instant expiresAt) {}
