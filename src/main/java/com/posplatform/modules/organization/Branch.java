package com.posplatform.modules.organization;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.util.UUID;

/**
 * Sucursal. Belongs to an organization THROUGH its legal entity.
 * Same-tenant referential integrity is enforced at the DATABASE level by the
 * composite foreign key (legal_entity_id, tenant_id) -> legal_entity(id, tenant_id)
 * declared in migration V2: a branch cannot point to a legal entity of another
 * tenant even if application code has bugs (proven by MultiTenantIsolationIT).
 */
@Entity
@Table(name = "branch")
public class Branch extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "legal_entity_id", nullable = false)
    private LegalEntity legalEntity;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 120)
    private String city;

    protected Branch() {
    }

    public Branch(UUID tenantId, LegalEntity legalEntity, String name, String city) {
        this.tenantId = tenantId;
        this.legalEntity = legalEntity;
        this.name = name;
        this.city = city;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public LegalEntity getLegalEntity() { return legalEntity; }
    public String getName() { return name; }
    public String getCity() { return city; }
}
