package com.academy.paybridge.remittance.api;

/** The quote the customer picked, and who should receive the money. */
public record OutboundRemittanceRequest(
        String quoteId,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryBank) {}
