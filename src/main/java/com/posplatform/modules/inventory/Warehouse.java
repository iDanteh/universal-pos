package com.posplatform.modules.inventory;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.util.UUID;

/**
 * Stock location (ADR-004) — usually one per branch/operation. UNIQUE
 * (tenant_id, code) from V12 mirrors the service rule; same-tenant integrity
 * is DB-enforced by the composite FK (id, tenant_id).
 */
@Entity
@Table(name = "warehouse")
public class Warehouse extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    protected Warehouse() {
    }

    public Warehouse(UUID tenantId, UUID branchId, String code, String name) {
        this.tenantId = tenantId;
        this.branchId = branchId;
        this.code = code;
        this.name = name;
        this.active = true;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getBranchId() { return branchId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
