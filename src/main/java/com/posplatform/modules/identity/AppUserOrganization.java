package com.posplatform.modules.identity;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.util.UUID;

/**
 * Identity-resolution mirror (PLATFORM-scoped): the organizations a user can
 * reach, written ONLY by trusted onboarding code right after inserting a
 * verified Membership. Source of truth remains membership + RLS; this table
 * exists because RLS cannot perform cross-tenant reads (by design) and login
 * must show the user her organizations BEFORE choosing one.
 *
 * TRADE-OFF (accepted, ADR-011): organizationName is a snapshot written at
 * onboarding; future rename flows must update it. It NEVER grants access —
 * every request still verifies membership inside the tenant's RLS scope.
 */
@Entity
@Table(name = "app_user_organization")
public class AppUserOrganization extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "organization_name", nullable = false, length = 120)
    private String organizationName;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    protected AppUserOrganization() {
    }

    public AppUserOrganization(UUID userId, UUID organizationId, String organizationName) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.organizationName = organizationName;
        this.primary = true;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getOrganizationId() { return organizationId; }
    public String getOrganizationName() { return organizationName; }
    public boolean isPrimary() { return primary; }
}
