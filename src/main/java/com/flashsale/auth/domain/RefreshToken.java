package com.flashsale.auth.domain;

import com.flashsale.shared.persistence.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of a refresh token. Only the SHA-256 hash is stored, so a database leak does
 * not hand out usable tokens. Tokens created by rotating the same login share a {@code familyId},
 * which lets the whole chain be revoked when an old token is replayed.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends AssignedIdEntity {

    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public static RefreshToken issue(
            UUID userId, String tokenHash, UUID familyId, Instant now, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.id = UUID.randomUUID();
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.familyId = familyId;
        token.createdAt = now;
        token.expiresAt = expiresAt;
        return token;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID familyId() {
        return familyId;
    }
}
