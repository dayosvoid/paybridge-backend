package com.academy.paybridge.settlement.api;

import java.util.List;

public record SettlementBatchDetails(SettlementBatchView batch, List<SettlementItemView> items) {}
