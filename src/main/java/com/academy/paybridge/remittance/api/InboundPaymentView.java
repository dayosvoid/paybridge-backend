package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Currency;

import java.time.Instant;

public record InboundPaymentView(
        String reference,
        String providerReference,
        String status,
        String accountNumber,
        Currency currency,
        long amountMinor,
        String senderName,
        String failureReason,
        Instant receivedAt,
        Instant creditedAt) {}
