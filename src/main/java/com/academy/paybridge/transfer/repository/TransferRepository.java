
package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    Optional<Transfer> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Transfer> findForUpdateByReference(String reference);

    @Query("""
            select t from Transfer t
            where t.sourceAccount = :account
               or t.destinationAccount = :account
            """)
    Page<Transfer> findInvolving(
            @Param("account") String account,
            Pageable pageable
    );

    @Query("""
            select t from Transfer t
            where (t.sourceAccount = :account
                or t.destinationAccount = :account)
              and t.status = :status
            """)
    Page<Transfer> findInvolvingWithStatus(
            @Param("account") String account,
            @Param("status") TransferStatus status,
            Pageable pageable
    );

    @Query("""
            select t from Transfer t
            where t.status = :status
              and t.bankCode is not null
              and t.createdAt < :cutoff
            order by t.createdAt asc
            """)
    List<Transfer> findStalePending(
            @Param("status") TransferStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );
}
