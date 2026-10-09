package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.client.GatewayResult;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import com.academy.paybridge.transfer.client.GatewayStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Asks the provider about external transfers that are still PENDING and settles the ones it can.
 * Safe to run at any time and from several places: settling is idempotent and row-locked.
 */
@Service
public class ReconciliationService {

    public record Summary(int checked, int resolvedSuccess, int resolvedFailed, int stillPending, int errors) {}

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final TransferRepository transfers;
    private final TransferGateway gateway;
    private final TransferService transferService;
    private final long notFoundGraceSeconds;

    public ReconciliationService(
            TransferRepository transfers,
            TransferGateway gateway,
            TransferService transferService,
            @Value("${paybridge.reconciliation.not-found-grace-seconds:1800}") long notFoundGraceSeconds) {
        this.transfers = transfers;
        this.gateway = gateway;
        this.transferService = transferService;
        this.notFoundGraceSeconds = notFoundGraceSeconds;
    }

    // Deliberately not @Transactional: every settlement is its own transaction, so one failure
    // cannot roll back the others.
    public Summary reconcile(Duration minAge, int batchSize) {
        Instant now = Instant.now();
        List<Transfer> candidates = transfers.findStalePending(
                TransferStatus.PENDING, now.minus(minAge), PageRequest.of(0, batchSize));

        int success = 0;
        int failed = 0;
        int pending = 0;
        int errors = 0;

        for (Transfer t : candidates) {
            String reference = t.getReference();
            try {
                GatewayResult result = gateway.verify(reference);
                switch (result.status()) {
                    case SUCCESS -> {
                        transferService.settleFromProvider(reference, GatewayStatus.SUCCESS, null);
                        success++;
                    }
                    case FAILED -> {
                        transferService.settleFromProvider(reference, GatewayStatus.FAILED,
                                "Reconciliation: " + result.message());
                        failed++;
                    }
                    case NOT_FOUND -> {
                        long ageSeconds = Duration.between(t.getCreatedAt(), now).getSeconds();
                        if (ageSeconds >= notFoundGraceSeconds) {
                            transferService.settleFromProvider(reference, GatewayStatus.FAILED,
                                    "Provider has no record of this transfer");
                            failed++;
                        } else {
                            pending++;
                        }
                    }
                    case PENDING -> pending++;
                }
            } catch (RuntimeException e) {
                errors++;
                log.warn("Reconciliation could not settle {}: {}", reference, e.getMessage());
            }
        }
        return new Summary(candidates.size(), success, failed, pending, errors);
    }
}