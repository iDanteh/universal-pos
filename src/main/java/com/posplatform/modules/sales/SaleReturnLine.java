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
 * SNAPSHOT line of a return (fase 9 · ADR-019): the returned quantity plus the
 * PRORATED money of the original sale_line (unit price, discount, net and the
 * tax SNAPSHOT). The line is the CFDI concept source of the credit note — it
 * must reproduce EXACTLY the taxes the sale charged (ADR-018), never re-rate
 * them. Same-tenant integrity: composite FKs to sale_return AND sale_line
 * (V16 adds the UNIQUE (id, tenant_id) sale_line reference target).
 */
@Entity
@Table(name = "sale_return_line")
public class SaleReturnLine extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private SaleReturn saleReturn;

    @Column(name = "sale_line_id", nullable = false)
    private UUID saleLineId;

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

    @Column(name = "tax_rate_code", length = 16)
    private String taxRateCode;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal;

    /** IEPS traslado prorated SEPARATELY (v0.11 · ADR-021) so the NC stamps
     * the same 2-traslado shape the sale charged. tax_total stays the SUM. */
    @Column(name = "ieps_rate_code", length = 16)
    private String iepsRateCode;

    @Column(name = "ieps_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal iepsTotal = BigDecimal.ZERO.setScale(2);

    protected SaleReturnLine() {
    }

    public SaleReturnLine(UUID tenantId, SaleReturn saleReturn, UUID saleLineId,
                          UUID variantId, String productName, String sku, String unit,
                          BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
                          BigDecimal lineTotal, String taxRateCode, BigDecimal taxTotal) {
        this(tenantId, saleReturn, saleLineId, variantId, productName, sku, unit,
                quantity, unitPrice, discount, lineTotal, taxRateCode, taxTotal,
                null, BigDecimal.ZERO.setScale(2));
    }

    public SaleReturnLine(UUID tenantId, SaleReturn saleReturn, UUID saleLineId,
                          UUID variantId, String productName, String sku, String unit,
                          BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
                          BigDecimal lineTotal, String taxRateCode, BigDecimal taxTotal,
                          String iepsRateCode, BigDecimal iepsTotal) {
        this.tenantId = tenantId;
        this.saleReturn = saleReturn;
        this.saleLineId = saleLineId;
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
        this.iepsRateCode = iepsRateCode;
        this.iepsTotal = iepsTotal;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public SaleReturn getSaleReturn() { return saleReturn; }
    public UUID getSaleLineId() { return saleLineId; }
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
    public String getIepsRateCode() { return iepsRateCode; }
    public BigDecimal getIepsTotal() { return iepsTotal; }
}
