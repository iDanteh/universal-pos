package com.posplatform.modules.invoicing;

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
import java.time.Instant;
import java.util.UUID;

/**
 * CFDI 4.0 document root (ADR-006). The lifecycle machine lives HERE, never
 * in sales (rule 3): PENDING -> STAMPED -> CANCELED (+ERROR). The receptor is
 * a SNAPSHOT captured at emission; totals are the SALE snapshot (the CFDI
 * NEVER rebills an amount different from what was actually paid). The folio
 * fiscal (fiscalId) comes from the FiscalProvider port (Mock PAC v0.6).
 */
@Entity
@Table(name = "invoice")
public class Invoice extends Auditable {

    public enum Status { PENDING, STAMPED, CANCELED, ERROR }

    /** INDIVIDUAL/GLOBAL = CFDI de ingreso (I); CREDIT_NOTE = tipo E (fase 9). */
    public enum Kind { INDIVIDUAL, GLOBAL, CREDIT_NOTE }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** Emisor razón social (fiscal face of ADR-006 rule 1). */
    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    @Column(nullable = false, columnDefinition = "text")
    private String serie = "FA";

    @Column(nullable = false)
    private long folio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Kind kind = Kind.INDIVIDUAL;

    /** The issued operation; NULL for the global invoice (N operations inside). */
    @Column(name = "sale_id")
    private UUID saleId;

    /** For CREDIT_NOTE: the return this note reverses (fase 9 · ADR-019). */
    @Column(name = "return_id")
    private UUID returnId;

    /** For CREDIT_NOTE: the related income CFDI (TipoRelacion 01). */
    @Column(name = "related_invoice_id")
    private UUID relatedInvoiceId;

    // ---- receptor snapshot ----
    @Column(name = "receptor_rfc", length = 13)
    private String receptorRfc;

    @Column(name = "receptor_name", length = 180)
    private String receptorName;

    @Column(name = "receptor_regime", columnDefinition = "text")
    private String receptorRegime;

    @Column(name = "receptor_use", columnDefinition = "text")
    private String receptorUse;

    @Column(name = "receptor_zip", length = 5)
    private String receptorZip;

    // ---- GLOBAL period ----
    @Column(name = "period_year")
    private Integer periodYear;

    @Column(name = "period_month")
    private Integer periodMonth;

    // ---- money snapshot ----
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

    // ---- lifecycle ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.PENDING;

    @Column(name = "status_reason", columnDefinition = "text")
    private String statusReason;

    /** Folio fiscal satelital (UUID) assigned by the provider at stamping. */
    @Column(name = "fiscal_id")
    private UUID fiscalId;

    @Column(nullable = false, columnDefinition = "text")
    private String provider = "MOCK";

    @Column(name = "stamped_at")
    private Instant stampedAt;

    // ---- cancellation ----
    @Column(name = "cancel_motive", length = 2)
    private String cancelMotive;

    @Column(name = "cancel_note", length = 280)
    private String cancelNote;

    // ---- generated document preview (JSON: provider info + XML preview) ----
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "sat_document", columnDefinition = "jsonb")
    private String satDocument;

    private Invoice() {
    }

    public Invoice(UUID tenantId, UUID legalEntityId, String serie, long folio,
                   Kind kind, UUID saleId,
                   String receptorRfc, String receptorName, String receptorRegime,
                   String receptorUse, String receptorZip,
                   Integer periodYear, Integer periodMonth,
                   BigDecimal subtotal, BigDecimal discountTotal,
                   BigDecimal taxTotal, BigDecimal total) {
        this.tenantId = tenantId;
        this.legalEntityId = legalEntityId;
        this.serie = serie;
        this.folio = folio;
        this.kind = kind;
        this.saleId = saleId;
        this.receptorRfc = receptorRfc;
        this.receptorName = receptorName;
        this.receptorRegime = receptorRegime;
        this.receptorUse = receptorUse;
        this.receptorZip = receptorZip;
        this.periodYear = periodYear;
        this.periodMonth = periodMonth;
        this.subtotal = subtotal;
        this.discountTotal = discountTotal;
        this.taxTotal = taxTotal;
        this.total = total;
        this.currency = "MXN";
        this.status = Status.PENDING;
        this.provider = "MOCK";
    }

    /** Stamp transition: only the provider fills the folio fiscal. */
    public void stamp(UUID fiscalId, Instant when) {
        this.status = Status.STAMPED;
        this.fiscalId = fiscalId;
        this.stampedAt = when;
        this.statusReason = null;
    }

    /** Cancellation with SAT motive + note. */
    public void cancel(String motive, String note) {
        this.status = Status.CANCELED;
        this.cancelMotive = motive;
        this.cancelNote = note;
        this.statusReason = null;
    }

    /** Provider rejection surfaced from the port. */
    public void markError(String reason) {
        this.status = Status.ERROR;
        this.statusReason = reason;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getLegalEntityId() { return legalEntityId; }
    public String getSerie() { return serie; }
    public long getFolio() { return folio; }
    public Kind getKind() { return kind; }
    public UUID getSaleId() { return saleId; }
    public UUID getReturnId() { return returnId; }
    public UUID getRelatedInvoiceId() { return relatedInvoiceId; }
    public String getReceptorRfc() { return receptorRfc; }
    public String getReceptorName() { return receptorName; }
    public String getReceptorRegime() { return receptorRegime; }
    public String getReceptorUse() { return receptorUse; }
    public String getReceptorZip() { return receptorZip; }
    public Integer getPeriodYear() { return periodYear; }
    public Integer getPeriodMonth() { return periodMonth; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountTotal() { return discountTotal; }
    public BigDecimal getTaxTotal() { return taxTotal; }
    public BigDecimal getTotal() { return total; }
    public Status getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public UUID getFiscalId() { return fiscalId; }
    public String getProvider() { return provider; }
    public Instant getStampedAt() { return stampedAt; }
    public String getCancelMotive() { return cancelMotive; }
    public String getCancelNote() { return cancelNote; }
    public String getSatDocument() { return satDocument; }
    public void setSatDocument(String satDocument) { this.satDocument = satDocument; }

    /** Credit-note linkage: set right after persisting the PENDING note. */
    public void setReturnLink(UUID returnId, UUID relatedInvoiceId) {
        this.returnId = returnId;
        this.relatedInvoiceId = relatedInvoiceId;
    }
}
