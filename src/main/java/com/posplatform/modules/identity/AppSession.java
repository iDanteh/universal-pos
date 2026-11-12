package com.posplatform.modules.identity;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.time.Instant;
import java.util.UUID;

/**
 * DB-backed browser session (PLATFORM-scoped, ADR-011).
 * Only the SHA-256 of the raw token is persisted; the raw token lives
 * exclusively in an HttpOnly cookie and can be revoked server-side.
 */
@Entity
@Table(name = "app_session")
public class AppSession extends Auditable {

    public enum Status { ACTIVE, REVOKED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected AppSession() {
    }

    public AppSession(UUID userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.status = Status.ACTIVE;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }

    public boolean isActive(Instant now) {
        return status == Status.ACTIVE && expiresAt.isAfter(now);
    }

    public void revoke() {
        this.status = Status.REVOKED;
    }
}
