package com.academy.paybridge.settlement.scheduler;

import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.shared.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * Settles one batch per run. Fully automatic: no endpoint triggers it. A failed run changes nothing
 * (the batch is atomic), is logged at ERROR, and is simply tried again on the next tick.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "paybridge.settlement.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementApi settlement;
    private final long eligibilityDelaySeconds;
    private final int batchSize;

    public SettlementScheduler(
            SettlementApi settlement,
            @Value("${paybridge.settlement.eligibility-delay-seconds:21600}") long eligibilityDelaySeconds,
            @Value("${paybridge.settlement.batch-size:50}") int batchSize) {
        this.settlement = settlement;
        this.eligibilityDelaySeconds = eligibilityDelaySeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${paybridge.settlement.initial-delay-ms:60000}",
            fixedDelayString = "${paybridge.settlement.interval-ms:60000}")
    public void run() {
        try {
            // NGN only for now: the settlement and provider clearing accounts are NGN accounts.
            settlement.settleNextBatch(Currency.NGN, Duration.ofSeconds(eligibilityDelaySeconds), batchSize);
        } catch (RuntimeException e) {
            log.error("Settlement run failed; nothing was changed and it will be retried", e);
        }
    }
}