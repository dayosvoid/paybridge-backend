package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Currency;

/** An incoming payment reported by the remittance provider, already authenticated. */
public record InboundPaymentCommand(
        String providerReference,
        String accountNumber,
        long amountMinor,
        Currency currency,
        String senderName) {}
