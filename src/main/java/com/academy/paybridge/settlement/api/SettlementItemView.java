package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Currency;

/** The stored principal of one transfer in a settlement batch. */
public record SettlementItemView(String transferReference, long amountMinor, Currency currency) {}
