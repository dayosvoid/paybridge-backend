package com.academy.paybridge.transfer.api;

import java.util.List;

public record TransferPage(
        List<TransferView> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext) {}