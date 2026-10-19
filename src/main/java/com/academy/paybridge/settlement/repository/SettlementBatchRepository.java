package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    Optional<SettlementBatch> findByReference(String reference);
}