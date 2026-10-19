package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Currency;

import java.time.Duration;
import java.util.Optional;

public interface SettlementApi {

    /**
     * Settles one batch of up to {@code batchSize} successful external transfers that succeeded at
     * least {@code eligibilityDelay} ago and have never been settled, all in one transaction: the
     * batch, its items, the marks on the transfers and the ledger posting from the settlement
     * account to the provider clearing account happen together or not at all.
     *
     * @return the batch, or empty when nothing is eligible
     */
    Optional<SettlementBatchView> settleNextBatch(Currency currency, Duration eligibilityDelay, int batchSize);
}