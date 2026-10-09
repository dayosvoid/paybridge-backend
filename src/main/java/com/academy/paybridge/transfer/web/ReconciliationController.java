package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.service.ReconciliationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/admin/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/run")
    public ReconciliationService.Summary run(@RequestParam(defaultValue = "0") long minAgeSeconds,
                                             @RequestParam(defaultValue = "50") int batchSize) {
        if (minAgeSeconds < 0) {
            throw new IllegalArgumentException("minAgeSeconds must be 0 or greater");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        return reconciliation.reconcile(Duration.ofSeconds(minAgeSeconds), batchSize);
    }
}
