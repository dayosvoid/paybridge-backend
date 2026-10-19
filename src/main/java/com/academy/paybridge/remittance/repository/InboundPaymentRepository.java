package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.InboundPayment;
import com.academy.paybridge.remittance.domain.InboundPaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InboundPaymentRepository extends JpaRepository<InboundPayment, Long> {

    Optional<InboundPayment> findByProviderReference(String providerReference);

    Optional<InboundPayment> findByReference(String reference);

    List<InboundPayment> findByStatusOrderByIdDesc(InboundPaymentStatus status, Pageable pageable);

    List<InboundPayment> findAllByOrderByIdDesc(Pageable pageable);

    /** Moves a payment to a new status, only if it is currently in one of the given statuses. Returns rows changed. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InboundPayment p set p.status = :to, p.failureReason = :reason
             where p.reference = :reference and p.status in :from
            """)
    int transition(@Param("reference") String reference,
                   @Param("from") Collection<InboundPaymentStatus> from,
                   @Param("to") InboundPaymentStatus to,
                   @Param("reason") String reason);
}
