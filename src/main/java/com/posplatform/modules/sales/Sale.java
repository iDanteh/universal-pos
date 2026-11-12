package com.posplatform.modules.sales;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The commercial operation root (sales-payments.md): totals are computed ONCE
 * and stored — history never recalculates. Lines are PRICE SNAPSHOTS; the
 * catalog can change later without rewriting history. Money is NUMERIC(14,2)
 * end to end (never float). CURRENTLY NO fiscal documents and NO inventory
 * movements: those arrive with invoicing (ADR-006) and ADR-004 respectively.
 */
@Entity
@Table(name = "sale")
public class Sale extends Auditable {

    public enum Status { COMPLETED, CANCELED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private Long folio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.COMPLETED;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(length = 280)
    private String note;

    @Column(nullable = false, length = 3)
    private String currency = "MXN";

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountTotal;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    /** Shift (ADR-017) the sale was collected in; NULL when no drawer was open. */
    @Column(name = "cash_session_id")
    private UUID cashSessionId;

    protected Sale() {
    }

    public Sale(UUID tenantId, long folio, String note, BigDecimal subtotal,
                BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal total,
                String idempotencyKey) {
        this.tenantId = tenantId;
        this.folio = folio;
        this.note = note;
        this.subtotal = subtotal;
        this.discountTotal = discountTotal;
        this.taxTotal = taxTotal;
        this.total = total;
        this.idempotencyKey = idempotencyKey;
        this.status = Status.COMPLETED;
        this.currency = "MXN";
    }

    public void cancel(String reason) {
        this.status = Status.CANCELED;
        this.statusReason = reason;
    }

    public void attachCashSession(UUID cashSessionId) {
        this.cashSessionId = cashSessionId;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Long getFolio() { return folio; }
    public Status getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public String getNote() { return note; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountTotal() { return discountTotal; }
    public BigDecimal getTaxTotal() { return taxTotal; }
    public BigDecimal getTotal() { return total; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getCashSessionId() { return cashSessionId; }
}
