package com.posplatform.modules.invoicing;

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
 * SNAPSHOT line of a CFDI (ADR-006): whatever was billed never recalculates.
 * Individual invoices copy the sale line 1:1; the GLOBAL invoice aggregates
 * the period's invoices-eligible sales by concept (product/sku/unit).
 */
@Entity
@Table(name = "invoice_line")
public class InvoiceLine extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    /** Provenance: NULL in the global invoice's aggregated lines. */
    @Column(name = "sale_line_id")
    private UUID saleLineId;

    @Column(nullable = false, length = 280)
    private String description;

    @Column(length = 64)
    private String sku;

    @Column(name = "unit_sat", nullable = false, columnDefinition = "text")
    private String unitSat;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal discount;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    /** Mirrors the sale line's tax 1:1 (ADR-018): the CFDI never re-bills an
     * amount nor rate different from what was actually charged. */
    @Column(name = "tax_rate_code", length = 16)
    private String taxRateCode;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal;

    protected InvoiceLine() {
    }

    public InvoiceLine(UUID tenantId, Invoice invoice, UUID saleLineId,
                       String description, String sku, String unitSat,
                       BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal discount, BigDecimal lineTotal,
                       String taxRateCode, BigDecimal taxTotal) {
        this.tenantId = tenantId;
        this.invoice = invoice;
        this.saleLineId = saleLineId;
        this.description = description;
        this.sku = sku;
        this.unitSat = unitSat;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.discount = discount;
        this.lineTotal = lineTotal;
        this.taxRateCode = taxRateCode;
        this.taxTotal = taxTotal;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Invoice getInvoice() { return invoice; }
    public UUID getSaleLineId() { return saleLineId; }
    public String getDescription() { return description; }
    public String getSku() { return sku; }
    public String getUnitSat() { return unitSat; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getDiscount() { return discount; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public String getTaxRateCode() { return taxRateCode; }
    public BigDecimal getTaxTotal() { return taxTotal; }
}
