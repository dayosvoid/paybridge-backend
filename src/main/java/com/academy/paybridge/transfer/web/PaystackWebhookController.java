package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.service.TransferService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import com.academy.paybridge.transfer.client.GatewayStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@RestController
@RequestMapping("/webhooks")
public class PaystackWebhookController {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Event(String event, EventData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventData(String reference) {}

    private final TransferService transfers;
    private final JsonMapper json;
    private final String secretKey;

    public PaystackWebhookController(TransferService transfers, JsonMapper json,
                                     @Value("${paystack.secret-key:}") String secretKey) {
        this.transfers = transfers;
        this.json = json;
        this.secretKey = secretKey;
    }

    @PostMapping("/paystack")
    public ResponseEntity<Void> receive(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "x-paystack-signature", required = false) String signature) {

        // 1. Verify BEFORE trusting anything in the body
        if (secretKey.isBlank() || !validSignature(rawBody, signature)) {
            return ResponseEntity.status(401).build();
        }

        // 2. Parse
        Event event;
        try {
            event = json.readValue(rawBody, Event.class);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
        if (event == null || event.event() == null || event.data() == null
                || event.data().reference() == null) {
            return ResponseEntity.ok().build();      // not something we handle
        }

        // 3. Act on transfer events only; everything else is acknowledged and ignored
        switch (event.event()) {
            case "transfer.success" ->
                    transfers.settleFromProvider(event.data().reference(), GatewayStatus.SUCCESS, null);
            case "transfer.failed", "transfer.reversed" ->
                    transfers.settleFromProvider(event.data().reference(), GatewayStatus.FAILED,
                            "Paystack reported " + event.event());
            default -> { }
        }
        return ResponseEntity.ok().build();
    }

    private boolean validSignature(byte[] rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));   // constant-time compare
        } catch (Exception e) {
            return false;
        }
    }
}