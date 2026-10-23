package com.academy.paybridge.auth.service;

import com.academy.paybridge.auth.domain.AppUser;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues and validates short-lived access tokens (JWT, HS256). The token carries the user id (subject),
 * email, role and account number, so a request can be authorised without a database lookup.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    public static final String ISSUER = "paybridge";
    static final String DEFAULT_SECRET = "dev-jwt-secret-change-me-at-least-32-bytes";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;

    public JwtService(@Value("${paybridge.auth.jwt-secret:" + DEFAULT_SECRET + "}") String secret,
                      @Value("${paybridge.auth.access-token-minutes:15}") long accessTokenMinutes) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("paybridge.auth.jwt-secret must be at least 32 characters");
        }
        if (accessTokenMinutes <= 0) {
            throw new IllegalStateException("paybridge.auth.access-token-minutes must be greater than zero");
        }
        if (DEFAULT_SECRET.equals(secret)) {
            log.warn("paybridge.auth.jwt-secret is the built-in dev value. Set PAYBRIDGE_JWT_SECRET before real use.");
        }
        SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = nimbus;
        this.ttl = Duration.ofMinutes(accessTokenMinutes);
    }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name());
        if (user.getAccountNumber() != null) {
            claims.claim("acct", user.getAccountNumber());
        }
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        return new IssuedToken(token, ttl.toSeconds());
    }

    public JwtDecoder decoder() {
        return decoder;
    }
}
