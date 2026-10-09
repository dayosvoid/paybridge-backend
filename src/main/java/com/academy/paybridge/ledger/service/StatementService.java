package com.academy.paybridge.ledger.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.ledger.api.EntryKind;
import com.academy.paybridge.ledger.api.LedgerEntryView;
import com.academy.paybridge.ledger.api.StatementPage;
import com.academy.paybridge.ledger.domain.EntryType;
import com.academy.paybridge.ledger.domain.LedgerEntry;
import com.academy.paybridge.ledger.repository.LedgerEntryRepository;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class StatementService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Instant MIN_TIME = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-01-01T00:00:00Z");

    private final LedgerEntryRepository entries;
    private final AccountApi accounts;

    public StatementService(LedgerEntryRepository entries, AccountApi accounts) {
        this.entries = entries;
        this.accounts = accounts;
    }

    /**
     * One repeatable-read, read-only transaction, so the balance and the entries come from the
     * same snapshot even if a transfer commits while this runs.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatementPage statement(String accountNumber, String type, Instant from, Instant to,
                                   int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        EntryType entryType = parseType(type);
        AccountView account = accounts.findByNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountNumber));

        Instant start = from == null ? MIN_TIME : from;
        Instant end = to == null ? MAX_TIME : to;
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        boolean filtered = entryType != null || from != null || to != null;

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<LedgerEntry> result = entryType == null
                ? entries.findByAccountNumberAndCreatedAtBetween(accountNumber, start, end, pageable)
                : entries.findByAccountNumberAndTypeAndCreatedAtBetween(accountNumber, entryType, start, end, pageable);

        // Balance after the newest entry on this page = current balance minus the net effect
        // of every entry that is newer than this page.
        Long running = null;
        if (!filtered) {
            long newerEntries = (long) page * size;
            long newerNet = newerEntries == 0 ? 0 : entries.netOfNewest(accountNumber, (int) newerEntries);
            running = account.balanceMinor() - newerNet;
        }

        List<LedgerEntryView> items = new ArrayList<>();
        for (LedgerEntry e : result.getContent()) {
            items.add(new LedgerEntryView(
                    e.getId(), e.getType().name(), EntryKind.of(e.getReference()), e.getAmountMinor(),
                    e.getCurrency(), e.getReference(), EntryKind.transferReference(e.getReference()),
                    e.getCreatedAt(), running));
            if (running != null) {
                running = running - signed(e);       // the next (older) entry's balance-after
            }
        }
        return new StatementPage(accountNumber, account.balanceMinor(), items,
                page, size, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    private static long signed(LedgerEntry e) {
        return e.getType() == EntryType.CREDIT ? e.getAmountMinor() : -e.getAmountMinor();
    }

    private static EntryType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return EntryType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be DEBIT or CREDIT");
        }
    }
}