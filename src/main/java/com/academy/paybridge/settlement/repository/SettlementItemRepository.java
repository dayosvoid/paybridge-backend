package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementItemRepository extends JpaRepository<SettlementItem, Long> {

    List<SettlementItem> findByBatchId(Long batchId);

    List<SettlementItem> findByBatchIdOrderByIdAsc(Long batchId);
}
