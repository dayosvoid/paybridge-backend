package com.academy.paybridge.remittance.web;

import com.academy.paybridge.auth.api.AccountAccess;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittancePage;
import com.academy.paybridge.remittance.api.OutboundRemittanceRequest;
import com.academy.paybridge.remittance.api.OutboundRemittanceView;
import com.academy.paybridge.remittance.api.ProviderQuoteView;
import com.academy.paybridge.shared.money.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Sending money abroad: get quotes from the providers, pick one, send. */
@RestController
@RequestMapping("/outbound-remittances")
public class OutboundRemittanceController {

    public record QuotesRequest(
            @NotBlank String sourceAccount,
            @Positive long sourceAmountMinor,
            @NotNull Currency destinationCurrency) {}

    public record SendRequest(
            @NotBlank String quoteId,
            @NotBlank String beneficiaryName,
            @NotBlank String beneficiaryAccount,
            String beneficiaryBank) {}

    private final OutboundRemittanceApi outbound;
    private final AccountAccess access;

    public OutboundRemittanceController(OutboundRemittanceApi outbound, AccountAccess access) {
        this.outbound = outbound;
        this.access = access;
    }

    /** Step 1: every provider's offer, best first. Each is stored with an expiry. */
    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@accountAccess.canUse(authentication, #request.sourceAccount())")
    public List<ProviderQuoteView> quotes(@Valid @RequestBody QuotesRequest request) {
        return outbound.getQuotes(request.sourceAccount(), request.sourceAmountMinor(), request.destinationCurrency());
    }

    /** Step 2: send with the quote you picked. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OutboundRemittanceView send(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody SendRequest request,
                                       Authentication authentication) {
        // only the owner of the quoted account may send with the quote
        access.requireUse(authentication, outbound.getQuoteAccountNumber(request.quoteId()));
        return outbound.send(new OutboundRemittanceRequest(request.quoteId(), request.beneficiaryName(),
                request.beneficiaryAccount(), request.beneficiaryBank()), idempotencyKey);
    }

    /** Operator view: GET /outbound-remittances?status=PENDING lists payments still waiting on a provider. */
    @GetMapping
    public List<OutboundRemittanceView> list(@RequestParam(required = false) String status) {
        return outbound.list(status);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('CUSTOMER')")
    public OutboundRemittancePage mine(Authentication authentication,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return outbound.listForAccount(access.requireOwnedAccount(authentication), page, size);
    }

    @GetMapping("/{reference}")
    @PostAuthorize("@accountAccess.canView(authentication, returnObject.sourceAccount())")
    public OutboundRemittanceView get(@PathVariable String reference) {
        return outbound.getByReference(reference);
    }

    /** Asks the provider about a PENDING remittance. Safe to call any time: it only settles on a definite answer. */
    @PostMapping("/{reference}/reconcile")
    public OutboundRemittanceView reconcile(@PathVariable String reference) {
        return outbound.reconcile(reference);
    }
}
