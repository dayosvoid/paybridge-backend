package com.academy.paybridge.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 hex of the token. The token itself is never stored. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** All tokens issued from one login share a family. */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when the token has been exchanged for the next one. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {}

    public RefreshToken(String tokenHash, Long userId, String familyId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public Long getUserId() { return userId; }
    public String getFamilyId() { return familyId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
