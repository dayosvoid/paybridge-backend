package com.academy.paybridge.settlement.api;

import java.util.List;

public record SettlementBatchPage(
        List<SettlementBatchView> items, int page, int size,
        long totalItems, int totalPages, boolean hasNext) {}
