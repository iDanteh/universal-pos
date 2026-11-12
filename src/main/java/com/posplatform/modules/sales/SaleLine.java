package com.posplatform.modules.sales;

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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SNAPSHOT line (rule 1 of sales-payments.md): product name/sku/unit/price are
 * copied AT SALE TIME from the catalog. Same-tenant integrity is DB-enforced
 * by the composite FK (sale_id, tenant_id) from V11. Money NUMERIC per column:
 * quantity/unit_price/discount use 4 dp (matches catalog + decimal units like
 * KG), while line_total settles at 2 dp HALF_UP (MXN money rule).
 */
@Entity
@Table(name = "sale_line")
public class SaleLine extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "product_name", nullable = false, length = 180)
    private String productName;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 8)
    private String unit;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal discount;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    /** Snapshot of the applied rate (catalog code) + tax ONCE computed on the
     * line net (qty x price - discount, the CFDI "Base") — history never
     * recalculates. NULL/0 = sin impuesto (pre-tax rows keep default 0). */
    @Column(name = "tax_rate_code", length = 16)
    private String taxRateCode;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal;

    protected SaleLine() {
    }

    public SaleLine(UUID tenantId, Sale sale, UUID variantId, String productName,
                    String sku, String unit, BigDecimal quantity, BigDecimal unitPrice,
                    BigDecimal discount, BigDecimal lineTotal,
                    String taxRateCode, BigDecimal taxTotal) {
        this.tenantId = tenantId;
        this.sale = sale;
        this.variantId = variantId;
        this.productName = productName;
        this.sku = sku;
        this.unit = unit;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.discount = discount;
        this.lineTotal = lineTotal;
        this.taxRateCode = taxRateCode;
        this.taxTotal = taxTotal;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Sale getSale() { return sale; }
    public UUID getVariantId() { return variantId; }
    public String getProductName() { return productName; }
    public String getSku() { return sku; }
    public String getUnit() { return unit; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getDiscount() { return discount; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public String getTaxRateCode() { return taxRateCode; }
    public BigDecimal getTaxTotal() { return taxTotal; }
}
