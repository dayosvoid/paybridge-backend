package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.api.InboundPaymentCommand;
import com.academy.paybridge.remittance.api.InboundPaymentView;
import com.academy.paybridge.remittance.api.InboundRemittanceApi;
import com.academy.paybridge.remittance.api.OutboundRemittanceApi;
import com.academy.paybridge.remittance.service.WebhookSignature;
import com.academy.paybridge.shared.money.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Where the remittance provider tells PayBridge that money arrived, plus the operator's view of
 * every inbound payment. The webhook is accepted only with a valid {@code X-Signature} header.
 */
@RestController
public class InboundWebhookController {

    private static final Logger log = LoggerFactory.getLogger(InboundWebhookController.class);
    static final String DEFAULT_SECRET = "dev-webhook-secret-change-me";

    public record InboundWebhookRequest(
            @NotBlank String providerReference,
            @NotBlank String accountNumber,
            @Positive long amountMinor,
            @NotNull Currency currency,
            String senderName) {}

    public record ReversalRequest(@NotBlank String providerReference, String reason) {}

    public record ReturnedRequest(@NotBlank String reference, String reason) {}

    private final InboundRemittanceApi inbound;
    private final OutboundRemittanceApi outbound;
    private final String secret;

    public InboundWebhookController(InboundRemittanceApi inbound, OutboundRemittanceApi outbound,
                                    @Value("${paybridge.remittance.webhook-secret:" + DEFAULT_SECRET + "}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("paybridge.remittance.webhook-secret must not be blank");
        }
        if (DEFAULT_SECRET.equals(secret)) {
            log.warn("paybridge.remittance.webhook-secret is the built-in dev value. Set your own before real use.");
        }
        this.inbound = inbound;
        this.outbound = outbound;
        this.secret = secret;
    }

    @PostMapping("/webhooks/remittance/inbound")
    public ResponseEntity<?> receive(@RequestHeader(value = "X-Signature", required = false) String signature,
                                     @Valid @RequestBody InboundWebhookRequest body) {
        InboundPaymentCommand command = new InboundPaymentCommand(body.providerReference(),
                body.accountNumber(), body.amountMinor(), body.currency(), body.senderName());
        if (!WebhookSignature.matches(secret, command, signature)) {
            log.warn("Rejected inbound webhook {}: bad or missing signature", body.providerReference());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Invalid signature"));
        }
        // 200 even when the payment is UNMATCHED: the provider delivered it fine, and retrying will not help.
        return ResponseEntity.ok(inbound.receive(command));
    }

    /**
     * The provider took an inbound payment back (a chargeback). Signed over {@code reversal|providerReference}.
     * Returns 200 with the payment; status REVERSAL_FAILED means the customer had already spent the money.
     */
    @PostMapping("/webhooks/remittance/inbound/reversal")
    public ResponseEntity<?> reverse(@RequestHeader(value = "X-Signature", required = false) String signature,
                                     @Valid @RequestBody ReversalRequest body) {
        if (!WebhookSignature.matchesText(secret, WebhookSignature.reversalCanonical(body.providerReference()),
                signature)) {
            log.warn("Rejected reversal webhook {}: bad or missing signature", body.providerReference());
            return unauthorized();
        }
        return ResponseEntity.ok(inbound.reverse(body.providerReference(), body.reason()));
    }

    /**
     * The provider reports that a payout PayBridge sent came back. Signed over {@code returned|reference}
     * (PayBridge's own outbound reference). The amount sent is refunded to the customer once.
     */
    @PostMapping("/webhooks/remittance/outbound/returned")
    public ResponseEntity<?> returned(@RequestHeader(value = "X-Signature", required = false) String signature,
                                      @Valid @RequestBody ReturnedRequest body) {
        if (!WebhookSignature.matchesText(secret, WebhookSignature.returnedCanonical(body.reference()), signature)) {
            log.warn("Rejected return webhook {}: bad or missing signature", body.reference());
            return unauthorized();
        }
        return ResponseEntity.ok(outbound.markReturned(body.reference(), body.reason()));
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Unauthorized", "message", "Invalid signature"));
    }

    /** Operator view: GET /inbound-payments?status=UNMATCHED lists payments that need attention. */
    @GetMapping("/inbound-payments")
    public List<InboundPaymentView> list(@RequestParam(required = false) String status) {
        return inbound.list(status);
    }

    @GetMapping("/inbound-payments/{reference}")
    public InboundPaymentView get(@PathVariable String reference) {
        return inbound.getByReference(reference);
    }
}
