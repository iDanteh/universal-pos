package com.posplatform.modules.branding;

import com.posplatform.modules.organization.Organization;
import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped relation organization ↔ brand (White Label distribution).
 * DB CHECK tenant_id = organization_id; model persistence only for v0.1.0
 * (management services deferred, documented).
 */
@Entity
@Table(name = "organization_brand")
public class OrganizationBrand extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Column(length = 80)
    private String relationshipType;   // e.g. DIRECT, VIA_RESELLER (declarative)

    /**
     * Controlled overrides of brand.config (ADR-007). Typed by key
     * convention: colors{primary,surface,text}, name, logoUrl, faviconUrl,
     * domain, templates{...}, featureFlags{...}. null = inherit everything.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> overrides;

    protected OrganizationBrand() {
    }

    public OrganizationBrand(Organization organization, Brand brand, String relationshipType) {
        this.organization = organization;
        this.tenantId = organization.getId();
        this.brand = brand;
        this.relationshipType = relationshipType;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Organization getOrganization() { return organization; }
    public Brand getBrand() { return brand; }
    public String getRelationshipType() { return relationshipType; }
    public Map<String, Object> getOverrides() { return overrides; }

    public void setOverrides(Map<String, Object> overrides) {
        this.overrides = overrides;
    }
}
