package com.academy.paybridge.ledger.api;

import com.academy.paybridge.shared.money.Money;

/** One balanced leg of a ledger batch: the same amount leaves one account and enters another. */
public record LedgerPosting(String debitAccount, String creditAccount, Money amount, String reference) {}