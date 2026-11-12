package com.posplatform.modules.catalog;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.util.UUID;

/**
 * Commercial definition of the tenant (ADR-004): the catalog root under which
 * variants (sellable SKU items) live. The base unit MUST be one of the
 * tenant's vertical profile units — enforced by {@link CatalogService} against
 * {@link com.posplatform.modules.organization.VerticalProfile} (declarative,
 * no per-tenant logic per ADR-010). Money never appears here; PRICE lives on
 * the variant as NUMERIC.
 */
@Entity
@Table(name = "product")
public class Product extends Auditable {

    public enum Status { ACTIVE, DISCONTINUED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 180)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 120)
    private String category;

    @Column(name = "base_unit", nullable = false, length = 8)
    private String baseUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    protected Product() {
    }

    public Product(UUID tenantId, String name, String description, String category, String baseUnit) {
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.category = category;
        this.baseUnit = baseUnit;
        this.status = Status.ACTIVE;
    }

    /** Declarative switch: no destructive operation, catalog-only semantics. */
    public void discontinue() {
        this.status = Status.DISCONTINUED;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getBaseUnit() { return baseUnit; }
    public Status getStatus() { return status; }
}
