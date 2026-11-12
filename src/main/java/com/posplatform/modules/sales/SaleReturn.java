package com.posplatform.modules.sales;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * The inverse commercial operation (fase 9 · ADR-019): money goes BACK to the
 * customer. Created ATOMICALLY against a COMPLETED sale — lines prorate the
 * sale's SNAPSHOT (line_total / tax_total), stock returns via compensatory
 * SALE_RETURN movements and the refund is recorded in the SAME transaction,
 * so a return can never exist half-applied (rule 3, like SalesService).
 *
 * The refund is ONE row: refund_method + refund_amount. The amount is NOT
 * client input — it IS the return total (the refund equals what was charged,
 * the payments rule of ADR-014 mirrored).
 */
@Entity
@Table(name = "sale_return")
public class SaleReturn extends Auditable {

    public enum Status { COMPLETED }

    /** Same methods as sale_payment — the refund returns through one of them. */
    public enum RefundMethod { CASH, CARD, TRANSFER, OTHER }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @Column(nullable = false)
    private long folio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.COMPLETED;

    @Column(length = 280)
    private String note;

    @Column(nullable = false, columnDefinition = "text")
    private String currency = "MXN";

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountTotal;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false, columnDefinition = "text")
    private RefundMethod refundMethod;

    @Column(name = "refund_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_reference", length = 80)
    private String refundReference;

    /** Shift the refund belongs to (attach rule of ADR-017, same as sale). */
    @Column(name = "cash_session_id")
    private UUID cashSessionId;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    protected SaleReturn() {
    }

    public SaleReturn(UUID tenantId, Sale sale, long folio, String note,
                      BigDecimal subtotal, BigDecimal discountTotal,
                      BigDecimal taxTotal, BigDecimal total,
                      RefundMethod refundMethod, String refundReference,
                      String idempotencyKey) {
        this.tenantId = tenantId;
        this.sale = sale;
        this.folio = folio;
        this.note = note;
        this.subtotal = subtotal;
        this.discountTotal = discountTotal;
        this.taxTotal = taxTotal;
        this.total = total;
        this.refundMethod = refundMethod;
        this.refundAmount = total;
        this.refundReference = refundReference;
        this.idempotencyKey = idempotencyKey;
        this.status = Status.COMPLETED;
        this.currency = "MXN";
    }

    /** Attach rule (ADR-017): the drawer OPEN when the refund left it. */
    public void attachCashSession(UUID sessionId) {
        this.cashSessionId = sessionId;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Sale getSale() { return sale; }
    public UUID getSaleId() { return sale.getId(); }
    public long getFolio() { return folio; }
    public Status getStatus() { return status; }
    public String getNote() { return note; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountTotal() { return discountTotal; }
    public BigDecimal getTaxTotal() { return taxTotal; }
    public BigDecimal getTotal() { return total; }
    public RefundMethod getRefundMethod() { return refundMethod; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public String getRefundReference() { return refundReference; }
    public UUID getCashSessionId() { return cashSessionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
