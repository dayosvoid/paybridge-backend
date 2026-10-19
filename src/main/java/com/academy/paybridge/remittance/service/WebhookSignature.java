package com.academy.paybridge.remittance.service;

import com.academy.paybridge.remittance.api.InboundPaymentCommand;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Demo signature scheme for the fake provider: HMAC-SHA256, hex, over the payment's key fields
 * {@code providerReference|accountNumber|amountMinor|currency}, keyed with a shared secret.
 * A real provider adapter replaces this with that provider's own scheme.
 */
public final class WebhookSignature {

    private WebhookSignature() {}

    public static String canonical(InboundPaymentCommand c) {
        return c.providerReference() + "|" + c.accountNumber() + "|" + c.amountMinor() + "|" + c.currency();
    }

    public static String sign(String secret, InboundPaymentCommand command) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(command).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** What a provider signs to report that a payment was taken back: {@code reversal|providerReference}. */
    public static String reversalCanonical(String providerReference) {
        return "reversal|" + providerReference;
    }

    /** What a provider signs to report that a payout came back: {@code returned|reference}. */
    public static String returnedCanonical(String reference) {
        return "returned|" + reference;
    }

    public static String signText(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    public static boolean matchesText(String secret, String text, String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(signText(secret, text).getBytes(StandardCharsets.UTF_8),
                presented.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time comparison, so response timing does not leak how much of a signature was right. */
    public static boolean matches(String secret, InboundPaymentCommand command, String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        byte[] expected = sign(secret, command).getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
