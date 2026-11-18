package com.posplatform.modules.identity;

import com.posplatform.modules.organization.Organization;
import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.time.Instant;
import java.util.UUID;

/**
 * The tenancy grant: (organization, user) with status.
 * Unique per (organization, user); tenant_id == organization.id (DB CHECK).
 *
 * Lifecycle (v0.10 · ADR-020): invited = PENDING with an invitation token;
 * consuming the token activates the grant (ACTIVE); revoke() ENDS it
 * (REVOKED) — a membership is never deleted.
 */
@Entity
@Table(name = "membership")
public class Membership extends Auditable {

    public enum Status { PENDING, ACTIVE, REVOKED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status;

    /**
     * SHA-256 of the raw invitation token (v0.10 · ADR-020). Kept after
     * consumption as a marker: replays of the SAME token are idempotent
     * (already ACTIVE → 200); the credential grants nothing else.
     */
    @Column(name = "invitation_token_hash", length = 64)
    private String invitationTokenHash;

    @Column(name = "invitation_expires_at")
    private Instant invitationExpiresAt;

    @Column(name = "invitation_accepted_at")
    private Instant invitationAcceptedAt;

    /** Informative provenance (no FK: app_user is platform-scoped). */
    @Column(name = "invited_by")
    private UUID invitedBy;

    protected Membership() {
    }

    public Membership(Organization organization, AppUser user, Status status) {
        this.organization = organization;
        this.tenantId = organization.getId();
        this.user = user;
        this.status = status;
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public UUID getTenantId() { return tenantId; }
    public AppUser getUser() { return user; }
    public Status getStatus() { return status; }
    public String getInvitationTokenHash() { return invitationTokenHash; }
    public Instant getInvitationExpiresAt() { return invitationExpiresAt; }
    public Instant getInvitationAcceptedAt() { return invitationAcceptedAt; }
    public UUID getInvitedBy() { return invitedBy; }

    /** Domain transition: the tenancy grant is ENDED (never deleted). */
    public void revoke() {
        this.status = Status.REVOKED;
    }

    /** Invitation issued: PENDING holds the token credential until consumed. */
    public void attachInvitation(String tokenHash, Instant expiresAt, UUID invitedBy) {
        if (this.status != Status.PENDING) {
            throw new IllegalStateException("Solo un membership PENDING lleva invitación");
        }
        this.invitationTokenHash = tokenHash;
        this.invitationExpiresAt = expiresAt;
        this.invitedBy = invitedBy;
    }

    /** A resend invalidates the previous token (the old link dies). */
    public void reissueInvitation(String tokenHash, Instant expiresAt, UUID invitedBy) {
        attachInvitation(tokenHash, expiresAt, invitedBy);
    }

    /**
     * Token consumed: the grant becomes ACTIVE. The token hash is KEPT as a
     * consumed marker (v0.10 · ADR-020): a replay of the same token finds the
     * membership and answers 200 idempotently — the credential no longer
     * changes anything because only the PENDING → ACTIVE transition exists.
     */
    public void acceptInvitation(Instant acceptedAt) {
        if (this.status != Status.PENDING) {
            throw new IllegalStateException("Solo un membership PENDING puede aceptarse");
        }
        this.status = Status.ACTIVE;
        this.invitationAcceptedAt = acceptedAt;
        this.invitationExpiresAt = null;
    }
}
