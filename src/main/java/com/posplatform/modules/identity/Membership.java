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

import java.util.UUID;

/**
 * The tenancy grant: (organization, user) with status.
 * Unique per (organization, user); tenant_id == organization.id (DB CHECK).
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

    /** Domain transition: the tenancy grant is ENDED (never deleted). */
    public void revoke() {
        this.status = Status.REVOKED;
    }
}
