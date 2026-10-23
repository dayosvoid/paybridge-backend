package com.academy.paybridge.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Test-side copy of how refresh tokens are hashed (SHA-256, lower-case hex), to look tokens up in the table. */
public final class RefreshHash {

    private RefreshHash() {}

    static String of(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
