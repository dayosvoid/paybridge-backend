package com.academy.paybridge.transfer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "paybridge.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliation;
    private final long minAgeSeconds;
    private final int batchSize;

    public ReconciliationScheduler(
            ReconciliationService reconciliation,
            @Value("${paybridge.reconciliation.min-age-seconds:300}") long minAgeSeconds,
            @Value("${paybridge.reconciliation.batch-size:50}") int batchSize) {
        this.reconciliation = reconciliation;
        this.minAgeSeconds = minAgeSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${paybridge.reconciliation.initial-delay-ms:30000}",
            fixedDelayString = "${paybridge.reconciliation.interval-ms:60000}")
    public void run() {
        try {
            ReconciliationService.Summary s = reconciliation.reconcile(Duration.ofSeconds(minAgeSeconds), batchSize);
            if (s.checked() > 0) {
                log.info("Reconciliation: checked={} success={} failed={} stillPending={} errors={}",
                        s.checked(), s.resolvedSuccess(), s.resolvedFailed(), s.stillPending(), s.errors());
            }
        } catch (RuntimeException e) {
            log.error("Reconciliation run failed", e);
        }
    }
}