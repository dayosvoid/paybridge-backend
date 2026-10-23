package com.academy.paybridge.settlement.web;

import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.settlement.api.SettlementBatchDetails;
import com.academy.paybridge.settlement.api.SettlementBatchPage;
import com.academy.paybridge.settlement.api.SettlementBatchView;
import com.academy.paybridge.settlement.service.SettlementHistoryService;
import com.academy.paybridge.shared.money.Currency;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/admin/settlements")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Settlement Administration", description = "Internal ledger settlement postings, not proof of external "
        + "interbank settlement. A successful transfer is not automatically settled: only unsettled successful "
        + "external NGN transfers whose succeededAt is at least the configured eligibility delay ago qualify.")
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer access token", content = @Content),
        @ApiResponse(responseCode = "403", description = "ADMIN role required", content = @Content),
        @ApiResponse(responseCode = "500", description = "Unexpected server failure; a failed run commits no batch",
                content = @Content)
})
public class SettlementAdminController {
    private final SettlementApi settlement;
    private final SettlementHistoryService history;
    private final Duration eligibilityDelay;
    private final int batchSize;

    public SettlementAdminController(SettlementApi settlement, SettlementHistoryService history,
            @Value("${paybridge.settlement.eligibility-delay-seconds:21600}") long eligibilityDelaySeconds,
            @Value("${paybridge.settlement.batch-size:50}") int batchSize) {
        this.settlement = settlement;
        this.history = history;
        this.eligibilityDelay = Duration.ofSeconds(eligibilityDelaySeconds);
        this.batchSize = batchSize;
    }

    @PostMapping("/run")
    @Operation(summary = "Post one eligible NGN settlement batch", description = "Uses the configured eligibility "
            + "delay and batch size, also used by the scheduler. No body or query overrides are supported. "
            + "Moves principal only from the settlement holding account to provider clearing. "
            + "The batch, items, transfer marks and ledger movement commit together or all roll back. "
            + "Manual runs remain available when the scheduler is disabled.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch committed to the internal ledger",
                    content = @Content(schema = @Schema(implementation = SettlementBatchView.class))),
            @ApiResponse(responseCode = "204", description = "No eligible unsettled transfers; no batch created",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid configured settlement arguments", content = @Content),
            @ApiResponse(responseCode = "409", description = "Settlement could not complete; batch rolled back", content = @Content),
            @ApiResponse(responseCode = "422", description = "Insufficient holding funds or restricted account; batch rolled back",
                    content = @Content)
    })
    public ResponseEntity<SettlementBatchView> run() {
        return settlement.settleNextBatch(Currency.NGN, eligibilityDelay, batchSize)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/batches")
    @Operation(summary = "List stored settlement batches", description = "Newest first by createdAt then id, both "
            + "descending. Zero-based page; size must be between 1 and 100. Status is the stored batch status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of batches; items is empty when none match",
                    content = @Content(schema = @Schema(implementation = SettlementBatchPage.class))),
            @ApiResponse(responseCode = "400", description = "Invalid pagination", content = @Content)
    })
    public SettlementBatchPage list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return history.list(page, size);
    }

    @GetMapping("/batches/{reference}")
    @Operation(summary = "Get a settlement batch and its items", description = "Returns stored batch fields and "
            + "transfer references with principal amounts. Items are ordered by id ascending. "
            + "No customer account or personal information is included.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch and associated items",
                    content = @Content(schema = @Schema(implementation = SettlementBatchDetails.class))),
            @ApiResponse(responseCode = "404", description = "Unknown batch reference", content = @Content)
    })
    public SettlementBatchDetails get(@PathVariable String reference) {
        return history.get(reference);
    }
}
