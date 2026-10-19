package com.academy.paybridge.transfer.api;

import com.academy.paybridge.shared.money.Currency;

/** A successful external transfer that is ready to be included in a settlement batch. Principal only. */
public record SettlementCandidate(String reference, long amountMinor, Currency currency) {}
