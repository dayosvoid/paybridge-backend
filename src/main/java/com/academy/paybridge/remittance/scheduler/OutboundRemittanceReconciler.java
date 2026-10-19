package com.academy.paybridge.remittance.scheduler;

import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Asks the provider about outbound remittances that are still PENDING, so an unknown outcome is
 * eventually settled (completed, or refunded). Fully automatic; a failed run is retried on the next tick.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "paybridge.remittance.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class OutboundRemittanceReconciler {

    private static final Logger log = LoggerFactory.getLogger(OutboundRemittanceReconciler.class);

    private final OutboundRemittanceApi outbound;
    private final long minAgeSeconds;
    private final int batchSize;

    public OutboundRemittanceReconciler(
            OutboundRemittanceApi outbound,
            @Value("${paybridge.remittance.reconciliation.min-age-seconds:120}") long minAgeSeconds,
            @Value("${paybridge.remittance.reconciliation.batch-size:50}") int batchSize) {
        this.outbound = outbound;
        this.minAgeSeconds = minAgeSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${paybridge.remittance.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${paybridge.remittance.reconciliation.interval-ms:60000}")
    public void run() {
        try {
            int settled = outbound.reconcilePending(minAgeSeconds, batchSize);
            if (settled > 0) {
                log.info("Reconciled {} pending outbound remittance(s)", settled);
            }
        } catch (RuntimeException e) {
            log.error("Outbound remittance reconciliation run failed; it will be retried", e);
        }
    }
}
