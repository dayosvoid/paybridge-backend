package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.Remittance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RemittanceRepository extends JpaRepository<Remittance, Long> {

    Optional<Remittance> findByIdempotencyKey(String idempotencyKey);

    Optional<Remittance> findByReference(String reference);
}
