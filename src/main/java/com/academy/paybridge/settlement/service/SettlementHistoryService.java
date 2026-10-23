package com.academy.paybridge.settlement.service;

import com.academy.paybridge.settlement.api.SettlementBatchDetails;
import com.academy.paybridge.settlement.api.SettlementBatchPage;
import com.academy.paybridge.settlement.api.SettlementBatchView;
import com.academy.paybridge.settlement.api.SettlementItemView;
import com.academy.paybridge.settlement.domain.SettlementBatch;
import com.academy.paybridge.settlement.repository.SettlementBatchRepository;
import com.academy.paybridge.settlement.repository.SettlementItemRepository;
import com.academy.paybridge.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SettlementHistoryService {
    private final SettlementBatchRepository batches;
    private final SettlementItemRepository items;

    public SettlementHistoryService(SettlementBatchRepository batches, SettlementItemRepository items) {
        this.batches = batches;
        this.items = items;
    }

    public SettlementBatchPage list(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        var result = batches.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new SettlementBatchPage(result.getContent().stream().map(SettlementHistoryService::toView).toList(),
                page, size, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    public SettlementBatchDetails get(String reference) {
        var batch = batches.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement batch not found: " + reference));
        return new SettlementBatchDetails(toView(batch), items.findByBatchIdOrderByIdAsc(batch.getId()).stream()
                .map(i -> new SettlementItemView(i.getTransferReference(), i.getAmountMinor(), i.getCurrency()))
                .toList());
    }

    private static SettlementBatchView toView(SettlementBatch b) {
        return new SettlementBatchView(b.getReference(), b.getProvider(), b.getCurrency(), b.getStatus(),
                b.getTotalMinor(), b.getItemCount(), b.getCreatedAt());
    }
}
