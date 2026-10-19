package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;
import java.time.Instant;

public record OutboundRemittanceView(
        String reference,
        String status,
        String quoteId,
        String providerId,
        String providerReference,
        String sourceAccount,
        long sourceAmountMinor,
        long feeMinor,
        Currency destinationCurrency,
        long destinationAmountMinor,
        BigDecimal rate,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryBank,
        String failureReason,
        Instant createdAt) {}
