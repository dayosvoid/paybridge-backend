
package com.academy.paybridge.ledger.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerPosting;
import com.academy.paybridge.ledger.domain.EntryType;
import com.academy.paybridge.ledger.domain.LedgerEntry;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

@Service
public class LedgerService implements LedgerApi {

    private final AccountApi accounts;
    private final LedgerEntryRepository entries;

    public LedgerService(AccountApi accounts, LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    @Override
    @Transactional
    public void post(String debitAccount, String creditAccount, Money amount, String reference) {
        postAll(List.of(new LedgerPosting(debitAccount, creditAccount, amount, reference)));
    }

    @Override
    @Transactional
    public void postAll(List<LedgerPosting> postings) {
        if (postings == null || postings.isEmpty()) {
            throw new IllegalArgumentException("At least one posting is required");
        }
        for (LedgerPosting p : postings) {
            if (!p.amount().isPositive()) {
                throw new IllegalArgumentException("Amount must be greater than zero");
            }
            if (p.debitAccount().equals(p.creditAccount())) {
                throw new IllegalArgumentException("Debit and credit accounts must differ");
            }
        }

        // Lock every account the batch touches, once, in ascending account-number order.
        // Because every transaction takes its locks in the same global order, no two
        // transactions can each hold a lock the other is waiting for, so deadlock is impossible.
        List<String> ordered = postings.stream()
                .flatMap(p -> Stream.of(p.debitAccount(), p.creditAccount()))
                .distinct()
                .sorted()
                .toList();
        accounts.lockInOrder(ordered);

        for (LedgerPosting p : postings) {
            accounts.debit(p.debitAccount(), p.amount());
            accounts.credit(p.creditAccount(), p.amount());
            entries.save(new LedgerEntry(p.debitAccount(), EntryType.DEBIT, p.amount(), p.reference()));
            entries.save(new LedgerEntry(p.creditAccount(), EntryType.CREDIT, p.amount(), p.reference()));
        }
    }
}