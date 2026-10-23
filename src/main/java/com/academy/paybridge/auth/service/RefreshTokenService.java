package com.academy.paybridge.auth.service;

import com.academy.paybridge.auth.api.UserView;
import com.academy.paybridge.auth.domain.AppUser;
import com.academy.paybridge.auth.domain.RefreshToken;
import com.academy.paybridge.auth.repository.AppUserRepository;
import com.academy.paybridge.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens with rotation. Every refresh retires the token that was used and issues the next one. A token
 * that is presented a second time means two parties hold it, so the whole login (family) is revoked.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final AppUserRepository users;
    private final JwtService jwt;
    private final TransactionTemplate tx;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository tokens, AppUserRepository users, JwtService jwt,
                               PlatformTransactionManager txManager,
                               @Value("${paybridge.auth.refresh-token-days:7}") long refreshTokenDays) {
        if (refreshTokenDays <= 0) {
            throw new IllegalStateException("paybridge.auth.refresh-token-days must be greater than zero");
        }
        this.tokens = tokens;
        this.users = users;
        this.jwt = jwt;
        this.tx = new TransactionTemplate(txManager);
        this.ttl = Duration.ofDays(refreshTokenDays);
    }

    /** A new login: starts a new family of refresh tokens. */
    public IssuedRefreshToken startSession(Long userId) {
        return issue(userId, UUID.randomUUID().toString());
    }

    /**
     * Exchanges a refresh token for a new access token and the next refresh token.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, expired, revoked or already used
     */
    public Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        String hash = hash(rawToken);
        // Everything, including the revocation on reuse, commits even though the caller then gets a 401.
        Session session = tx.execute(status -> {
            RefreshToken token = tokens.findByTokenHash(hash).orElse(null);
            if (token == null) {
                return null;
            }
            Instant now = Instant.now();
            if (token.getRevokedAt() != null || token.getExpiresAt().isBefore(now)) {
                return null;
            }
            if (token.getUsedAt() != null) {
                log.warn("Refresh token reuse detected for user {}: revoking the whole login", token.getUserId());
                tokens.revokeFamily(token.getFamilyId(), now);
                return null;
            }
            AppUser user = users.findById(token.getUserId()).orElse(null);
            if (user == null || !user.isEnabled()) {
                tokens.revokeFamily(token.getFamilyId(), now);
                return null;
            }
            if (tokens.markUsed(token.getId(), now) != 1) {
                // someone else used it between our read and our update: the same as a reuse
                log.warn("Refresh token for user {} used twice at once: revoking the whole login", user.getId());
                tokens.revokeFamily(token.getFamilyId(), now);
                return null;
            }
            IssuedRefreshToken next = issue(user.getId(), token.getFamilyId());
            return new Session(jwt.issue(user), next, toView(user));
        });
        if (session == null) {
            throw new InvalidRefreshTokenException();
        }
        return session;
    }

    /** Signs out the login this token belongs to. Idempotent: an unknown token is not an error. */
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(hash(rawToken))
                .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    /** Signs the user out of every login. */
    public void logoutAll(Long userId) {
        tokens.revokeAllForUser(userId, Instant.now());
    }

    private IssuedRefreshToken issue(Long userId, String familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new RefreshToken(hash(raw), userId, familyId, Instant.now().plus(ttl)));
        return new IssuedRefreshToken(raw, ttl.toSeconds());
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UserView toView(AppUser u) {
        return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getAccountNumber());
    }
}
