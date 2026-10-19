package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Currency;

import java.time.Instant;

public record SettlementBatchView(
        String reference, String provider, Currency currency, BatchStatus status,
        long totalMinor, int itemCount, Instant createdAt) {}