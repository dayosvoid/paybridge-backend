package com.academy.paybridge.ledger.api;

import com.academy.paybridge.shared.money.Money;

import java.util.List;

public interface LedgerApi {

    void post(String debitAccount, String creditAccount, Money amount, String reference);

    /** Posts every leg in one transaction: all of them happen, or none do. */
    void postAll(List<LedgerPosting> postings);
}