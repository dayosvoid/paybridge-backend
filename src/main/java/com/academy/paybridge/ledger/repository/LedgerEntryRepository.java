package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.domain.EntryType;
import com.academy.paybridge.ledger.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByReference(String reference);

    Page<LedgerEntry> findByAccountNumberAndCreatedAtBetween(
            String accountNumber, Instant from, Instant to, Pageable pageable);

    Page<LedgerEntry> findByAccountNumberAndTypeAndCreatedAtBetween(
            String accountNumber, EntryType type, Instant from, Instant to, Pageable pageable);

    /** Net effect (credits minus debits) of the newest {@code take} entries of an account. */
    @Query(value = """
            select cast(coalesce(sum(case when e.type = 'CREDIT' then e.amount_minor
                                          else -e.amount_minor end), 0) as bigint)
            from (select type, amount_minor
                  from ledger_entries
                  where account_number = :account
                  order by created_at desc, id desc
                  limit :take) e
            """, nativeQuery = true)
    long netOfNewest(@Param("account") String account, @Param("take") int take);
}