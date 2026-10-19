package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.money.Currency;

/**
 * Identifies one balance. An account number can hold one balance per currency
 * (an NGN balance and a USD balance), so a number alone is not enough to name a balance.
 */
public record AccountRef(String accountNumber, Currency currency) {}