package com.posplatform.modules.identity;

import com.posplatform.modules.organization.Organization;
import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped role definition. Every role belongs to exactly one
 * organization; the OWNER default role is created during onboarding.
 */
@Entity
@Table(name = "role")
public class Role extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** Redundant with organization.id for RLS; DB CHECK keeps them consistent. */
    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 255)
    private String description;

    @ManyToMany
    @JoinTable(
            name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    protected Role() {
    }

    public Role(Organization organization, String name, String description) {
        this.organization = organization;
        this.tenantId = organization.getId();
        this.name = name;
        this.description = description;
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public Set<Permission> getPermissions() { return permissions; }

    public void grant(Permission permission) {
        this.permissions.add(permission);
    }
}
