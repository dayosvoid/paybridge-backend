package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.domain.EntryType;
import com.academy.paybridge.ledger.domain.LedgerEntry;
import com.academy.paybridge.shared.money.Currency;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByReference(String reference);

    Page<LedgerEntry> findByAccountNumberAndCurrencyAndCreatedAtBetween(
            String accountNumber, Currency currency, Instant from, Instant to, Pageable pageable);

    Page<LedgerEntry> findByAccountNumberAndCurrencyAndTypeAndCreatedAtBetween(
            String accountNumber, Currency currency, EntryType type, Instant from, Instant to, Pageable pageable);

    /** Net effect (credits minus debits) of the newest {@code take} entries of one balance of an account. */
    @Query(value = """
            select cast(coalesce(sum(case when e.type = 'CREDIT' then e.amount_minor
                                          else -e.amount_minor end), 0) as bigint)
            from (select type, amount_minor
                  from ledger_entries
                  where account_number = :account and currency = :currency
                  order by created_at desc, id desc
                  limit :take) e
            """, nativeQuery = true)
    long netOfNewest(@Param("account") String account, @Param("currency") String currency,
                     @Param("take") int take);

}