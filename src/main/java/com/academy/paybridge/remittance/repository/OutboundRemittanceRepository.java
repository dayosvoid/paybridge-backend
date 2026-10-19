package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.OutboundRemittance;
import com.academy.paybridge.remittance.domain.OutboundRemittanceStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OutboundRemittanceRepository extends JpaRepository<OutboundRemittance, Long> {

    Optional<OutboundRemittance> findByIdempotencyKey(String idempotencyKey);

    Optional<OutboundRemittance> findByReference(String reference);

    boolean existsByQuoteId(String quoteId);

    List<OutboundRemittance> findByStatusAndCreatedAtBeforeOrderByIdAsc(
            OutboundRemittanceStatus status, Instant before, Pageable pageable);

    /**
     * Moves a remittance out of PENDING, but only if it is still PENDING. Returns 1 for the caller that
     * won and 0 for anyone who lost the race. This is what makes completing or refunding happen once.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboundRemittance r
               set r.status = :to, r.failureReason = :reason, r.completedAt = :now
             where r.reference = :reference and r.status = com.academy.paybridge.remittance.domain.OutboundRemittanceStatus.PENDING
            """)
    int transitionFromPending(@Param("reference") String reference,
                              @Param("to") OutboundRemittanceStatus to,
                              @Param("reason") String reason,
                              @Param("now") Instant now);

    /** Same as {@link #transitionFromPending} for any one starting status (used for COMPLETED to RETURNED). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboundRemittance r
               set r.status = :to, r.failureReason = :reason
             where r.reference = :reference and r.status = :from
            """)
    int transition(@Param("reference") String reference,
                   @Param("from") OutboundRemittanceStatus from,
                   @Param("to") OutboundRemittanceStatus to,
                   @Param("reason") String reason);

    /** Total sent from an account since a time, leaving out the given statuses (FAILED and RETURNED). */
    @Query("""
            select coalesce(sum(r.sourceAmountMinor), 0L) from OutboundRemittance r
             where r.sourceAccount = :account and r.createdAt >= :since and r.status not in :excluded
            """)
    long sumSince(@Param("account") String account, @Param("since") Instant since,
                  @Param("excluded") Collection<OutboundRemittanceStatus> excluded);

    List<OutboundRemittance> findAllByOrderByIdDesc(Pageable pageable);

    List<OutboundRemittance> findByStatusOrderByIdDesc(OutboundRemittanceStatus status, Pageable pageable);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboundRemittance r set r.providerReference = :providerReference
             where r.reference = :reference
            """)
    int setProviderReference(@Param("reference") String reference,
                             @Param("providerReference") String providerReference);

    /** A note on a remittance that is still PENDING (unknown outcome, refund needing attention). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboundRemittance r set r.failureReason = :reason
             where r.reference = :reference and r.status = com.academy.paybridge.remittance.domain.OutboundRemittanceStatus.PENDING
            """)
    int noteWhilePending(@Param("reference") String reference, @Param("reason") String reason);
}
