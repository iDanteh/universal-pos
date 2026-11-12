package com.posplatform.modules.catalog;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Sellable SKU item under a product (ADR-004). The SKU is unique per tenant
 * (V10 unique index mirrors the service rule) and same-tenant referential
 * integrity is DB-enforced by the composite FK (product_id, tenant_id) from
 * V10. Attributes carry ONLY the vertical's declared keys (ADR-010) —
 * validated in the service, never free-form per-tenant logic.
 */
@Entity
@Table(name = "product_variant")
public class ProductVariant extends Auditable {

    public enum Status { ACTIVE, DISCONTINUED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** Same-tenant referential integrity is DB-enforced by the composite FK (product_id, tenant_id) from V10 — JPA keeps the plain association (Branch/LegalEntity pattern). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "barcode", length = 64)
    private String barcode;

    @Column(nullable = false, length = 8)
    private String unit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, String> attributes;

    /** NUMERIC(14,4) in the DB — string-safe in the API (money rule). */
    @Column(precision = 14, scale = 4)
    private BigDecimal price;

    /**
     * Transferred-tax rate the tenant assigns to this SKU (v0.8 · ADR-018):
     * normalized UPPER code into the global {@code tax_rate} catalog (V15),
     * validated service-side; null = sin impuesto (tax 0, ObjetoImpuesto 01).
     * 0..1 traslado per line in v0.8 — the IEPS->IVA chain arrives later.
     */
    @Column(name = "tax_rate_code", length = 16)
    private String taxRateCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    protected ProductVariant() {
    }

    public ProductVariant(UUID tenantId, Product product, String sku, String barcode,
                          String unit, Map<String, String> attributes, BigDecimal price,
                          String taxRateCode) {
        this.tenantId = tenantId;
        this.product = product;
        this.sku = sku;
        this.barcode = barcode;
        this.unit = unit;
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        this.price = price;
        this.taxRateCode = taxRateCode;
        this.status = Status.ACTIVE;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Product getProduct() { return product; }
    public UUID getProductId() { return product.getId(); }
    public String getSku() { return sku; }
    public String getBarcode() { return barcode; }
    public String getUnit() { return unit; }
    public Map<String, String> getAttributes() { return attributes; }
    public BigDecimal getPrice() { return price; }
    public String getTaxRateCode() { return taxRateCode; }
    public Status getStatus() { return status; }
}
