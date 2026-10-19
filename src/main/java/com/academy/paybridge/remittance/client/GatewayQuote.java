package com.academy.paybridge.remittance.client;

import java.math.BigDecimal;

/** A provider's price: the rate, its fee in NGN minor units, what the recipient gets, and how long it takes. */
public record GatewayQuote(BigDecimal rate, long feeMinor, long destinationAmountMinor, int etaMinutes) {}
