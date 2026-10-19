package com.academy.paybridge.remittance.client;

import com.academy.paybridge.shared.money.Currency;

/** One payment to send. {@code reference} is PayBridge's own, so asking again never sends twice. */
public record SendInstruction(
        String reference,
        long sourceAmountMinor,
        Currency destinationCurrency,
        long destinationAmountMinor,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryBank) {}
