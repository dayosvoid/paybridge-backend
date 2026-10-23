package com.academy.paybridge.remittance.api;

import java.util.List;

public record OutboundRemittancePage(
        List<OutboundRemittanceView> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext) {}
