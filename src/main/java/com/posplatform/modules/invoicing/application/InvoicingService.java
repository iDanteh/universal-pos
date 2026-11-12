package com.posplatform.modules.invoicing.application;

import com.posplatform.modules.invoicing.Invoice;
import com.posplatform.modules.invoicing.InvoiceLine;
import com.posplatform.modules.invoicing.SatCatalog;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampLine;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampRequest;
import com.posplatform.modules.invoicing.fiscal.MockFiscalProvider;
import com.posplatform.modules.invoicing.infrastructure.InvoiceLineRepository;
import com.posplatform.modules.invoicing.infrastructure.InvoiceRepository;
import com.posplatform.modules.invoicing.infrastructure.SatCatalogRepository;
import com.posplatform.modules.organization.LegalEntity;
import com.posplatform.modules.organization.infrastructure.LegalEntityRepository;
import com.posplatform.modules.sales.Sale;
import com.posplatform.modules.sales.SaleLine;
import com.posplatform.modules.sales.SaleReturn;
import com.posplatform.modules.sales.SaleReturnLine;
import com.posplatform.modules.sales.infrastructure.SaleLineRepository;
import com.posplatform.modules.sales.infrastructure.SaleRepository;
import com.posplatform.modules.sales.infrastructure.SaleReturnLineRepository;
import com.posplatform.modules.sales.infrastructure.SaleReturnRepository;
import com.posplatform.shared.api.ConflictException;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.domain.MexicanTaxId;
import com.posplatform.shared.outbox.OutboxWriter;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * INVOICING use case (fase 6 · ADR-006/016): issue CFDI 4.0 documents from
 * COMPLETED sales. Invariants implemented here:
 *
 *  - the lifecycle machine lives HERE, never in sales (ADR-006 rule 3):
 *    issuing = PENDING -> provider.stamp -> STAMPED with folio fiscal in ONE
 *    transaction for the Mock PAC. A real PAC lands behind the SAME port as
 *    an async worker; the outbox event is already the seam;
 *  - the CFDI is a SNAPSHOT of the sale: totals and lines are copied ONCE —
 *    the CFDI never bills an amount different from what was actually paid
 *    (v0.8 · ADR-018: per-line taxes arrive SNAPSHOT in the sale and are
 *    mirrored 1:1; the mock validates coherence, never recomputes);
 *  - the receptor is a SNAPSHOT captured at emission (RFC validated with the
 *    shared MexicanTaxId guard; SAT codes checked against sat_catalog);
 *  - the emisor is the LEGAL ENTITY fiscal face (rule 1): without RFC +
 *    régime + código postal the entity cannot issue (fail-fast, no guess);
 *  - ONE ACTIVE invoice per sale and ONE ACTIVE global per (tenant, month)
 *    via the V13 partial unique indexes (cancellation frees both);
 *  - the GLOBAL invoice consolidates the period's still-uninvoiced COMPLETED
 *    sales into RFC genérico XAXX010101000 (público en general) aggregating
 *    per concept (producto/sku/unidad) like the RMF regla 2.7.1.4;
 *  - cancellation is motive-driven (sat_catalog MOTIVO_CANCELACION): only
 *    STAMPED documents cancel; motive 04 never applies to sales.
 */
@Service
public class InvoicingService {

    private static final Pattern ZIP_PATTERN = Pattern.compile("^\\d{5}$");
    private static final String SERIE = "FA";
    private static final String SERIE_NC = "NC";

    /** Declared sale units (ADR-010) -> SAT unit keys (Anexo 20 seed subset). */
    private static final Map<String, String> SALE_UNIT_TO_SAT = Map.of(
            "PZ", "H87", "CAJA", "H87", "KG", "KGM", "LT", "LTR",
            "M2", "MTK", "SERV", "E48");
    private static final String DEFAULT_SAT_UNIT = "H87";

    private static final List<Invoice.Status> ACTIVE_STATUSES =
            List.of(Invoice.Status.PENDING, Invoice.Status.STAMPED);

    private static final String SAT_PRODUCT_DEFAULT = "01010101";

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository lineRepository;
    private final SatCatalogRepository satCatalogRepository;
    private final SaleRepository saleRepository;
    private final SaleLineRepository saleLineRepository;
    private final SaleReturnRepository returnRepository;
    private final SaleReturnLineRepository returnLineRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final FiscalProvider fiscalProvider;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;
    private final JdbcTemplate jdbc;

    public InvoicingService(InvoiceRepository invoiceRepository,
                            InvoiceLineRepository lineRepository,
                            SatCatalogRepository satCatalogRepository,
                            SaleRepository saleRepository,
                            SaleLineRepository saleLineRepository,
                            SaleReturnRepository returnRepository,
                            SaleReturnLineRepository returnLineRepository,
                            LegalEntityRepository legalEntityRepository,
                            FiscalProvider fiscalProvider,
                            OutboxWriter outboxWriter,
                            TenantTransactionRunner tenantRunner,
                            JdbcTemplate jdbc) {
        this.invoiceRepository = invoiceRepository;
        this.lineRepository = lineRepository;
        this.satCatalogRepository = satCatalogRepository;
        this.saleRepository = saleRepository;
        this.saleLineRepository = saleLineRepository;
        this.returnRepository = returnRepository;
        this.returnLineRepository = returnLineRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.fiscalProvider = fiscalProvider;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
        this.jdbc = jdbc;
    }

    // ---- Commands ----

    public record ReceptorCommand(String rfc, String name, String regime,
                                  String use, String zip) {
    }

    public record IssueFromSaleCommand(UUID saleId, UUID legalEntityId,
                                       ReceptorCommand receptor) {
    }

    public record IssueGlobalCommand(int periodYear, int periodMonth,
                                     UUID legalEntityId) {
    }

    public record CancelCommand(String motive, String note) {
    }

    /** Issue the CREDIT_NOTE of ONE return (fase 9 · ADR-019). */
    public record IssueCreditNoteCommand(UUID returnId) {
    }

    // ---- Views ----

    public record InvoiceLineView(UUID id, UUID saleLineId, String description,
                                  String sku, String unitSat, String quantity,
                                  String unitPrice, String discount, String lineTotal,
                                  String taxRateCode, String taxTotal) {
    }

    public record InvoiceView(UUID id, String serie, long folio, String folioLabel,
                              String kind, UUID saleId, UUID returnId,
                              UUID relatedInvoiceId, Integer periodYear,
                              Integer periodMonth, String receptorRfc,
                              String receptorName, String receptorRegime,
                              String receptorUse, String receptorZip,
                              String subtotal, String discountTotal, String taxTotal,
                              String total, String status, String statusReason,
                              String fiscalId, String provider, String stampedAt,
                              String cancelMotive, String cancelNote, String createdAt,
                              List<InvoiceLineView> lines) {
    }

    public record EmisorView(UUID id, String legalName, String tradeName, String rfc,
                             String fiscalRegime, String zipCode, boolean complete) {
    }

    public record CatalogItem(String code, String description) {
    }

    // ---- Emisor (fiscal face per razón social, ADR-006 rule 1) ----

    @Transactional(readOnly = true)
    public List<EmisorView> emisorList(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> {
            List<EmisorView> views = new ArrayList<>();
            for (LegalEntity entity : legalEntityRepository.findByOrganizationId(tenantId)) {
                views.add(toEmisorView(entity));
            }
            return views;
        });
    }

    @Transactional
    public EmisorView saveEmisor(UUID tenantId, UUID legalEntityId,
                                 String taxId, String fiscalRegime, String zipCode) {
        return tenantRunner.run(tenantId, () -> {
            LegalEntity entity = legalEntityRepository.findById(legalEntityId)
                    .filter(legal -> legal.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new NotFoundException("legal_entity", legalEntityId.toString()));

            // RFC del emisor: puede materializarse UNA VEZ (mientras esté vacío);
            // unicidad por tenant con el mismo guard del onboarding (ADR-009).
            if (taxId != null && !taxId.isBlank()) {
                String normalized = MexicanTaxId.normalize(taxId);
                MexicanTaxId.requireValid(normalized);
                boolean differs = entity.getTaxId() == null
                        || !entity.getTaxId().equalsIgnoreCase(normalized);
                if (differs && (entity.getTaxId() != null && !entity.getTaxId().isBlank())) {
                    throw new ConflictException("RFC_LOCKED",
                            "La razón social ya tiene un RFC asignado y no es editable en esta fase");
                }
                if (differs) {
                    boolean taken = legalEntityRepository.findByOrganizationId(tenantId).stream()
                            .anyMatch(other -> !other.getId().equals(entity.getId())
                                    && other.getTaxId() != null
                                    && other.getTaxId().equalsIgnoreCase(normalized));
                    if (taken) {
                        throw new ConflictException("RFC_ALREADY_USED",
                                "El RFC " + normalized + " ya está registrado en otra razón social");
                    }
                    entity.assignTaxId(normalized);
                }
            }

            String regime = requireCatalogCode(SatCatalog.Catalog.REGIMEN, fiscalRegime,
                    "El régimen fiscal del emisor");
            String zip = requireZip(zipCode, "El código postal del emisor (LugarExpedicion)");
            entity.completeFiscalFace(regime, zip);
            return toEmisorView(legalEntityRepository.saveAndFlush(entity));
        });
    }

    /** SAT catalog listing for the issuing forms. */
    @Transactional(readOnly = true)
    public List<CatalogItem> catalogs(SatCatalog.Catalog catalog) {
        return satCatalogRepository.findByCatalogAndActiveTrueOrderByCodeAsc(catalog).stream()
                .map(item -> new CatalogItem(item.getCode(), item.getDescription()))
                .toList();
    }

    // ---- Issue: individual CFDI for one sale ----

    @Transactional
    public InvoiceView issueFromSale(UUID tenantId, IssueFromSaleCommand command) {
        return tenantRunner.run(tenantId, () -> {
            if (command.saleId() == null) {
                throw new IllegalArgumentException("El saleId es requerido");
            }
            ReceptorCommand receptor = command.receptor();
            if (receptor == null) {
                throw new IllegalArgumentException("Los datos del receptor son requeridos");
            }

            Sale sale = saleRepository.findByTenantIdAndId(tenantId, command.saleId())
                    .orElseThrow(() -> new NotFoundException("sale", command.saleId().toString()));
            if (sale.getStatus() != Sale.Status.COMPLETED) {
                throw new ConflictException("SALE_NOT_COMPLETE",
                        "Solo se facturan ventas COMPLETED (esta venta no está completa)");
            }

            invoiceRepository
                    .findByTenantIdAndSaleIdAndStatusIn(tenantId, sale.getId(), ACTIVE_STATUSES)
                    .ifPresent(existing -> {
                        throw new ConflictException("INVOICE_ALREADY_EXISTS",
                                "La venta ya tiene una factura activa (" + folioLabel(existing) + ")");
                    });

            LegalEntity emisor = resolveEmisor(tenantId, command.legalEntityId());
            List<SaleLine> saleLines = saleLineRepository
                    .findByTenantIdAndSaleIdOrderByCreatedAtAsc(tenantId, sale.getId());

            long folio = requireFolio(tenantId, SERIE);
            Invoice invoice = new Invoice(
                    tenantId, emisor.getId(), SERIE, folio, Invoice.Kind.INDIVIDUAL, sale.getId(),
                    MexicanTaxId.requireValid(receptor.rfc()),
                    requireReceptorName(receptor.name()),
                    requireCatalogCode(SatCatalog.Catalog.REGIMEN, receptor.regime(),
                            "El régimen fiscal del receptor"),
                    requireCatalogCode(SatCatalog.Catalog.USO_CFDI, receptor.use(),
                            "El uso CFDI"),
                    requireZip(receptor.zip(), "El código postal del receptor"),
                    null, null,
                    sale.getSubtotal(), sale.getDiscountTotal(), sale.getTaxTotal(),
                    sale.getTotal());

            List<StampLine> stampLines = new ArrayList<>();
            for (SaleLine saleLine : saleLines) {
                stampLines.add(toStampLine(saleLine));
            }

            stampAndPersist(invoice, stampLines, emisor, "I", null);

            outboxWriter.append(tenantId, "invoicing.invoice.issued", "invoice", invoice.getId(),
                    Map.of("folioLabel", folioLabel(invoice),
                            "saleId", sale.getId().toString(),
                            "receptorRfc", invoice.getReceptorRfc(),
                            "total", invoice.getTotal().toPlainString(),
                            "fiscalId", invoice.getFiscalId().toString()));

            return viewOf(tenantId, invoice);
        });
    }

    // ---- Issue: global monthly CFDI (publico en general) ----

    @Transactional
    public InvoiceView issueGlobal(UUID tenantId, IssueGlobalCommand command) {
        return tenantRunner.run(tenantId, () -> {
            int year = command.periodYear();
            int month = command.periodMonth();

            invoiceRepository
                    .findByTenantIdAndKindAndPeriodYearAndPeriodMonthAndStatusIn(
                            tenantId, Invoice.Kind.GLOBAL, year, month, ACTIVE_STATUSES)
                    .ifPresent(existing -> {
                        throw new ConflictException("GLOBAL_ALREADY_EXISTS",
                                "El periodo " + year + "-" + month
                                        + " ya tiene una factura global activa ("
                                        + folioLabel(existing) + ")");
                    });

            List<UUID> eligibleSaleIds = jdbc.queryForList("""
                            SELECT s.id
                            FROM sale s
                            WHERE s.tenant_id = ?
                              AND s.status = 'COMPLETED'
                              AND s.created_at >= make_date(?, ?, 1)
                              AND s.created_at <  make_date(?, ?, 1) + interval '1 month'
                              AND NOT EXISTS (
                                  SELECT 1 FROM invoice i
                                  WHERE i.tenant_id = s.tenant_id
                                    AND i.sale_id = s.id
                                    AND i.status IN ('PENDING', 'STAMPED'))
                            ORDER BY s.created_at ASC
                            """,
                    UUID.class, tenantId, year, month, year, month);
            if (eligibleSaleIds.isEmpty()) {
                throw new ConflictException("NO_INVOICEABLE_SALES",
                        "No hay ventas pendientes de facturar en el periodo "
                                + year + "-" + month);
            }

            LegalEntity emisor = resolveEmisor(tenantId, command.legalEntityId());

            // Aggregate by concept (producto/sku/unidad) — RMF regla 2.7.1.4.
            // The tax aggregates as the plain SUM of each sale line's
            // SNAPSHOT (ADR-018: taxes already rounded at line level are
            // never re-rounded); the concept's rate code comes from its
            // first member (same variant -> same rate within a concept).
            BigDecimal subtotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxSum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            List<AggregatedConcept> concepts = new ArrayList<>();
            for (UUID saleId : eligibleSaleIds) {
                for (SaleLine saleLine : saleLineRepository
                        .findByTenantIdAndSaleIdOrderByCreatedAtAsc(tenantId, saleId)) {
                    String key = conceptKey(saleLine);
                    AggregatedConcept concept = concepts.stream()
                            .filter(c -> c.key().equals(key))
                            .findFirst()
                            .orElseGet(() -> {
                                TaxMeta meta = requireTaxMeta(saleLine.getTaxRateCode());
                                AggregatedConcept fresh = new AggregatedConcept(
                                        key, saleLine.getProductName() + " " + saleLine.getSku(),
                                        saleLine.getSku(), toSatUnit(saleLine.getUnit()),
                                        saleLine.getTaxRateCode(), meta);
                                concepts.add(fresh);
                                return fresh;
                            });
                    concept.add(saleLine.getQuantity(), saleLine.getLineTotal(),
                            saleLine.getTaxTotal());
                }
            }

            List<StampLine> stampLines = new ArrayList<>();
            List<ConceptSnapshot> conceptSnapshots = new ArrayList<>();
            for (AggregatedConcept concept : concepts) {
                // importe/quantity rounded to 4dp keeps the CFDI multiply coherently
                BigDecimal quantity = concept.quantitySum().setScale(4, RoundingMode.HALF_UP);
                BigDecimal lineTotal = concept.lineTotalSum().setScale(2, RoundingMode.HALF_UP);
                BigDecimal unitPrice = lineTotal.divide(quantity, 4, RoundingMode.HALF_UP);
                BigDecimal taxTotal = concept.taxTotalSum().setScale(2, RoundingMode.HALF_UP);
                stampLines.add(new StampLine(concept.description(), concept.sku(),
                        concept.unitSat(), quantity, unitPrice,
                        BigDecimal.ZERO.setScale(4), lineTotal,
                        concept.taxRateCode(),
                        concept.taxMeta() == null ? null : concept.taxMeta().scheme(),
                        concept.taxMeta() == null ? null : concept.taxMeta().factor(),
                        concept.taxMeta() == null ? null : concept.taxMeta().rate(),
                        taxTotal));
                conceptSnapshots.add(new ConceptSnapshot(concept, quantity, unitPrice,
                        lineTotal, taxTotal));
                subtotal = subtotal.add(lineTotal);
                taxSum = taxSum.add(taxTotal);
            }

            BigDecimal total = subtotal.subtract(BigDecimal.ZERO.setScale(2))
                    .add(taxSum).setScale(2, RoundingMode.HALF_UP);

            long folio = requireFolio(tenantId, SERIE);
            Invoice invoice = new Invoice(
                    tenantId, emisor.getId(), SERIE, folio, Invoice.Kind.GLOBAL, null,
                    MockFiscalProvider.RFC_PUBLICO_GENERAL, "PUBLICO EN GENERAL",
                    "616", "P01", emisor.getZipCode(),
                    year, month,
                    subtotal, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    taxSum, total);

            // the Mock refuses documents whose concept sum != subtotal
            invoice = invoiceRepository.save(invoice);
            FiscalProvider.StampResult stamp = fiscalProvider.stamp(new StampRequest(
                    invoice.getSerie(), invoice.getFolio(), "I",
                    emisor.getTaxId(), emisor.getLegalName(), emisor.getFiscalRegime(),
                    emisor.getZipCode(),
                    invoice.getReceptorRfc(), invoice.getReceptorName(),
                    invoice.getReceptorRegime(), invoice.getReceptorUse(), invoice.getReceptorZip(),
                    stampLines, invoice.getSubtotal(), invoice.getDiscountTotal(),
                    invoice.getTaxTotal(), invoice.getTotal(), null));
            invoice.stamp(stamp.fiscalId(), stamp.stampedAt());
            invoice.setSatDocument(stamp.detailJson());
            invoiceRepository.saveAndFlush(invoice);

            List<InvoiceLineView> lineViews = new ArrayList<>();
            for (ConceptSnapshot snapshot : conceptSnapshots) {
                InvoiceLine saved = lineRepository.save(new InvoiceLine(
                        tenantId, invoice, null, snapshot.aggregate().description(),
                        snapshot.aggregate().sku(), snapshot.aggregate().unitSat(),
                        snapshot.quantity(), snapshot.unitPrice(),
                        BigDecimal.ZERO.setScale(4), snapshot.lineTotal(),
                        snapshot.aggregate().taxRateCode(), snapshot.taxTotal()));
                lineViews.add(new InvoiceLineView(
                        saved.getId(), null, saved.getDescription(), saved.getSku(),
                        saved.getUnitSat(), saved.getQuantity().toPlainString(),
                        saved.getUnitPrice().toPlainString(), saved.getDiscount().toPlainString(),
                        saved.getLineTotal().toPlainString(),
                        saved.getTaxRateCode(), saved.getTaxTotal().toPlainString()));
            }

            outboxWriter.append(tenantId, "invoicing.invoice.global_issued", "invoice",
                    invoice.getId(),
                    Map.of("folioLabel", folioLabel(invoice),
                            "period", periodLabel(year, month),
                            "sales", eligibleSaleIds.size(),
                            "total", total.toPlainString(),
                            "fiscalId", invoice.getFiscalId().toString()));

            return new InvoiceView(
                    invoice.getId(), invoice.getSerie(), invoice.getFolio(), folioLabel(invoice),
                    String.valueOf(invoice.getKind()), invoice.getSaleId(),
                    invoice.getReturnId(), invoice.getRelatedInvoiceId(),
                    invoice.getPeriodYear(), invoice.getPeriodMonth(),
                    invoice.getReceptorRfc(), invoice.getReceptorName(),
                    invoice.getReceptorRegime(), invoice.getReceptorUse(), invoice.getReceptorZip(),
                    invoice.getSubtotal().toPlainString(), invoice.getDiscountTotal().toPlainString(),
                    invoice.getTaxTotal().toPlainString(), invoice.getTotal().toPlainString(),
                    String.valueOf(invoice.getStatus()), invoice.getStatusReason(),
                    invoice.getFiscalId().toString(), invoice.getProvider(),
                    invoice.getStampedAt().toString(), invoice.getCancelMotive(),
                    invoice.getCancelNote(), invoice.getCreatedAt().toString(),
                    lineViews);
        });
    }

    // ---- Issue: credit note for one return (fase 9 · ADR-019) ----

    @Transactional
    public InvoiceView issueCreditNote(UUID tenantId, IssueCreditNoteCommand command) {
        return tenantRunner.run(tenantId, () -> {
            if (command.returnId() == null) {
                throw new IllegalArgumentException("El returnId es requerido");
            }

            SaleReturn saleReturn = returnRepository
                    .findByTenantIdAndId(tenantId, command.returnId())
                    .orElseThrow(() -> new NotFoundException("sale_return",
                            command.returnId().toString()));

            // fiscal precondition: the sale's own INDIVIDUAL invoice must exist
            // AND be stamped — the NC relates to it (TipoRelacion 01) and copies
            // its receptor SNAPSHOT.
            Invoice relatedInvoice = invoiceRepository
                    .findByTenantIdAndSaleIdAndStatusIn(
                            tenantId, saleReturn.getSaleId(), ACTIVE_STATUSES)
                    .orElseThrow(() -> new ConflictException("RETURN_NOT_INVOICED",
                            "La venta de esta devolución no tiene factura: emite "
                                    + "primero el CFDI de ingreso (FA)"));
            if (relatedInvoice.getStatus() != Invoice.Status.STAMPED
                    || relatedInvoice.getFiscalId() == null) {
                throw new ConflictException("INVOICE_NOT_STAMPED",
                        "La factura " + folioLabel(relatedInvoice)
                                + " aún no está timbrada");
            }

            invoiceRepository
                    .findByTenantIdAndReturnIdAndStatusIn(
                            tenantId, saleReturn.getId(), ACTIVE_STATUSES)
                    .ifPresent(existing -> {
                        throw new ConflictException("CREDIT_NOTE_ALREADY_EXISTS",
                                "Esta devolución ya tiene una nota de crédito activa ("
                                        + folioLabel(existing) + ")");
                    });

            // the NC's emisor IS the original FA's emisor (re-resolved so an
            // entity whose fiscal face went incomplete after the FA fails).
            LegalEntity emisor = resolveEmisor(tenantId, relatedInvoice.getLegalEntityId());

            // receptor = SNAPSHOT copied from the original FA (the customer
            // returns what they bought from THAT receipt).
            long folio = requireFolio(tenantId, SERIE_NC);
            Invoice creditNote = new Invoice(
                    tenantId, emisor.getId(), SERIE_NC, folio,
                    Invoice.Kind.CREDIT_NOTE, null,
                    relatedInvoice.getReceptorRfc(), relatedInvoice.getReceptorName(),
                    relatedInvoice.getReceptorRegime(), relatedInvoice.getReceptorUse(),
                    relatedInvoice.getReceptorZip(),
                    null, null,
                    saleReturn.getSubtotal(), saleReturn.getDiscountTotal(),
                    saleReturn.getTaxTotal(), saleReturn.getTotal());
            creditNote.setReturnLink(saleReturn.getId(), relatedInvoice.getId());

            List<StampLine> stampLines = new ArrayList<>();
            for (SaleReturnLine line : returnLineRepository
                    .findByTenantIdAndSaleReturn_IdOrderByCreatedAtAsc(tenantId, saleReturn.getId())) {
                TaxMeta meta = requireTaxMeta(line.getTaxRateCode());
                stampLines.add(new StampLine(
                        line.getProductName() + " " + line.getSku(),
                        line.getSku(), toSatUnit(line.getUnit()),
                        line.getQuantity(), line.getUnitPrice(),
                        line.getDiscount(), line.getLineTotal(),
                        line.getTaxRateCode(),
                        meta == null ? null : meta.scheme(),
                        meta == null ? null : meta.factor(),
                        meta == null ? null : meta.rate(),
                        line.getTaxTotal()));
            }

            stampAndPersist(creditNote, stampLines, emisor, "E",
                    relatedInvoice.getFiscalId());

            outboxWriter.append(tenantId, "invoicing.creditnote.issued", "invoice",
                    creditNote.getId(),
                    Map.of("folioLabel", folioLabel(creditNote),
                            "returnId", saleReturn.getId().toString(),
                            "relatedFolio", folioLabel(relatedInvoice),
                            "total", creditNote.getTotal().toPlainString(),
                            "fiscalId", creditNote.getFiscalId().toString()));

            return viewOf(tenantId, creditNote);
        });
    }

    // ---- Cancel (SAT motive) ----

    @Transactional
    public InvoiceView cancel(UUID tenantId, UUID invoiceId, CancelCommand command) {
        return tenantRunner.run(tenantId, () -> {
            Invoice invoice = invoiceRepository.findByTenantIdAndId(tenantId, invoiceId)
                    .orElseThrow(() -> new NotFoundException("invoice", invoiceId.toString()));
            if (invoice.getStatus() == Invoice.Status.CANCELED) {
                throw new ConflictException("INVOICE_ALREADY_CANCELED",
                        "La factura ya está cancelada");
            }
            if (invoice.getStatus() != Invoice.Status.STAMPED) {
                throw new ConflictException("INVOICE_NOT_STAMPED",
                        "Solo se pueden cancelar facturas timbradas");
            }
            String motive = requireCancelMotive(command.motive());
            fiscalProvider.cancel(invoice.getFiscalId(), motive);
            String note = command.note() == null || command.note().isBlank()
                    ? null : command.note().trim();
            invoice.cancel(motive, note);
            outboxWriter.append(tenantId, "invoicing.invoice.canceled", "invoice", invoice.getId(),
                    Map.of("folioLabel", folioLabel(invoice), "motive", motive,
                            "fiscalId", invoice.getFiscalId().toString()));
            return viewOf(tenantId, invoice);
        });
    }

    // ---- Reads ----

    @Transactional(readOnly = true)
    public List<InvoiceView> list(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> {
            List<InvoiceView> views = new ArrayList<>();
            for (Invoice invoice : invoiceRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId)) {
                views.add(viewOf(tenantId, invoice));
            }
            return views;
        });
    }

    @Transactional(readOnly = true)
    public InvoiceView get(UUID tenantId, UUID invoiceId) {
        return tenantRunner.run(tenantId, () ->
                invoiceRepository.findByTenantIdAndId(tenantId, invoiceId)
                        .map(invoice -> viewOf(tenantId, invoice))
                        .orElseThrow(() -> new NotFoundException("invoice", invoiceId.toString())));
    }

    // ---- Internals ----

    /**
     * Shared stamp+persistence for the issue paths (same transaction). The
     * document type comes from the invoice KIND (I for income, E for credit
     * notes) and an egreso carries the related FA's folio fiscal.
     */
    private void stampAndPersist(Invoice invoice, List<StampLine> stampLines,
                                 LegalEntity emisor, String documentType,
                                 UUID relatedFiscalId) {
        StampRequest request = new StampRequest(
                invoice.getSerie(), invoice.getFolio(),
                documentType,
                emisor.getTaxId(), emisor.getLegalName(), emisor.getFiscalRegime(),
                emisor.getZipCode(),
                invoice.getReceptorRfc(), invoice.getReceptorName(),
                invoice.getReceptorRegime(), invoice.getReceptorUse(), invoice.getReceptorZip(),
                stampLines, invoice.getSubtotal(), invoice.getDiscountTotal(),
                invoice.getTaxTotal(), invoice.getTotal(), relatedFiscalId);

        FiscalProvider.StampResult stamp;
        try {
            stamp = fiscalProvider.stamp(request);
        } catch (ConflictException ex) {
            // the provider rejected the document: nothing is persisted (the
            // deterministic Mock only rejects broken shapes — a real PAC path
            // maps this to the ERROR state with the SAME port).
            throw ex;
        }
        invoice.stamp(stamp.fiscalId(), stamp.stampedAt());
        invoice.setSatDocument(stamp.detailJson());
        invoiceRepository.saveAndFlush(invoice);

        for (StampLine line : stampLines) {
            lineRepository.save(new InvoiceLine(
                    invoice.getTenantId(), invoice, null, line.description(), line.sku(),
                    line.unitSat(), line.quantity(), line.unitPrice(),
                    line.discount(), line.lineTotal(),
                    line.taxRateCode(), line.taxTotal()));
        }
    }

    private InvoiceView viewOf(UUID tenantId, Invoice invoice) {
        List<InvoiceLineView> lines = lineRepository
                .findByTenantIdAndInvoiceIdOrderByCreatedAtAsc(tenantId, invoice.getId())
                .stream()
                .map(line -> new InvoiceLineView(
                        line.getId(), line.getSaleLineId(), line.getDescription(),
                        line.getSku(), line.getUnitSat(),
                        line.getQuantity().toPlainString(), line.getUnitPrice().toPlainString(),
                        line.getDiscount().toPlainString(), line.getLineTotal().toPlainString(),
                        line.getTaxRateCode(), line.getTaxTotal().toPlainString()))
                .toList();
        return new InvoiceView(
                invoice.getId(), invoice.getSerie(), invoice.getFolio(), folioLabel(invoice),
                String.valueOf(invoice.getKind()), invoice.getSaleId(),
                invoice.getReturnId(), invoice.getRelatedInvoiceId(),
                invoice.getPeriodYear(), invoice.getPeriodMonth(),
                invoice.getReceptorRfc(), invoice.getReceptorName(),
                invoice.getReceptorRegime(), invoice.getReceptorUse(), invoice.getReceptorZip(),
                invoice.getSubtotal().toPlainString(), invoice.getDiscountTotal().toPlainString(),
                invoice.getTaxTotal().toPlainString(), invoice.getTotal().toPlainString(),
                String.valueOf(invoice.getStatus()), invoice.getStatusReason(),
                invoice.getFiscalId() == null ? null : invoice.getFiscalId().toString(),
                invoice.getProvider(),
                invoice.getStampedAt() == null ? null : invoice.getStampedAt().toString(),
                invoice.getCancelMotive(), invoice.getCancelNote(),
                invoice.getCreatedAt().toString(), lines);
    }

    private EmisorView toEmisorView(LegalEntity entity) {
        boolean complete = entity.getTaxId() != null && !entity.getTaxId().isBlank()
                && entity.getFiscalRegime() != null && entity.getZipCode() != null;
        return new EmisorView(entity.getId(), entity.getLegalName(), entity.getTradeName(),
                entity.getTaxId(), entity.getFiscalRegime(), entity.getZipCode(), complete);
    }

    private LegalEntity resolveEmisor(UUID tenantId, UUID requestedId) {
        List<LegalEntity> entities = legalEntityRepository.findByOrganizationId(tenantId);
        if (entities.isEmpty()) {
            throw new ConflictException("EMISOR_MISSING",
                    "La organización no tiene razones sociales registradas");
        }
        LegalEntity emisor;
        if (requestedId != null) {
            emisor = legalEntityRepository.findById(requestedId)
                    .filter(legal -> legal.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new NotFoundException("legal_entity", requestedId.toString()));
        } else if (entities.size() == 1) {
            emisor = entities.getFirst();
        } else {
            throw new ConflictException("LEGAL_ENTITY_REQUIRED",
                    "Indica la razón social emisora (legalEntityId): la organización tiene "
                            + entities.size());
        }
        if (emisor.getTaxId() == null || emisor.getTaxId().isBlank()
                || emisor.getFiscalRegime() == null || emisor.getZipCode() == null) {
            throw new ConflictException("EMISOR_INCOMPLETE",
                    "Completa los datos fiscales del emisor '" + emisor.getLegalName()
                            + "' (RFC, régimen y código postal) antes de facturar");
        }
        return emisor;
    }

    private static String folioLabel(Invoice invoice) {
        return invoice.getSerie() + "-" + String.format("%06d", invoice.getFolio());
    }

    private static String periodLabel(int year, int month) {
        return year + "-" + String.format("%02d", month);
    }

    /** Atomic per-tenant+serie folio (V13 invoice_folio: ON CONFLICT row lock). */
    private long requireFolio(UUID tenantId, String serie) {
        Long folio = jdbc.queryForObject("""
                        INSERT INTO invoice_folio (tenant_id, serie, last)
                        VALUES (?, ?, 1)
                        ON CONFLICT (tenant_id, serie)
                        DO UPDATE SET last = invoice_folio.last + 1
                        RETURNING last
                        """,
                Long.class, tenantId, serie);
        return folio == null ? 1 : folio;
    }

    private static String toSatUnit(String saleUnit) {
        if (saleUnit == null || saleUnit.isBlank()) return DEFAULT_SAT_UNIT;
        return SALE_UNIT_TO_SAT.getOrDefault(saleUnit.trim().toUpperCase(), DEFAULT_SAT_UNIT);
    }

    private static String requireReceptorName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("El nombre del receptor es requerido");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 180) {
            throw new IllegalArgumentException("El nombre del receptor excede 180 caracteres");
        }
        return trimmed;
    }

    private static String requireZip(String zip, String label) {
        if (zip == null || zip.isBlank()) {
            throw new IllegalArgumentException(label + " es requerido (5 dígitos)");
        }
        String trimmed = zip.trim();
        if (!ZIP_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(label + " debe tener exactamente 5 dígitos");
        }
        return trimmed;
    }

    private static String requireCancelMotive(String motive) {
        if (motive == null || motive.isBlank()) {
            throw new IllegalArgumentException("El motivo de cancelación SAT es requerido");
        }
        String trimmed = motive.trim();
        if ("04".equals(trimmed)) {
            throw new ConflictException("INVALID_CANCEL_MOTIVE",
                    "El motivo 04 no aplica a ventas; usa 01, 02 o 03");
        }
        return trimmed;
    }

    private String requireCatalogCode(SatCatalog.Catalog catalog, String code, String label) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(label + " es requerido");
        }
        String trimmed = code.trim();
        satCatalogRepository.findByCatalogAndCode(catalog, trimmed)
                .orElseThrow(() -> new IllegalArgumentException(
                        label + " no es válido: '" + trimmed + "' no existe en el catálogo SAT"));
        return trimmed;
    }

    private static String conceptKey(SaleLine line) {
        return line.getProductName() + "|" + line.getSku() + "|" + line.getUnit();
    }

    // ---- Tax snapshot support (v0.8 · ADR-018) ----

    /** Resolved tax_rate row (global catalog, V15) for one snapshot code. */
    private record TaxMeta(String scheme, String factor, BigDecimal rate) {
    }

    /** Stamp line mirroring the sale line 1:1, taxes SNAPSHOT never re-billed. */
    private StampLine toStampLine(SaleLine saleLine) {
        TaxMeta meta = requireTaxMeta(saleLine.getTaxRateCode());
        return new StampLine(
                saleLine.getProductName() + " " + saleLine.getSku(),
                saleLine.getSku(), toSatUnit(saleLine.getUnit()),
                saleLine.getQuantity(), saleLine.getUnitPrice(),
                saleLine.getDiscount(), saleLine.getLineTotal(),
                saleLine.getTaxRateCode(),
                meta == null ? null : meta.scheme(),
                meta == null ? null : meta.factor(),
                meta == null ? null : meta.rate(),
                saleLine.getTaxTotal());
    }

    /**
     * Resolves the catalog row a sale line's snapshot code points at. Codes
     * are validated at ASSIGN (catalog) and sale time, so a miss here is a
     * data-level inconsistency and fails closed — the CFDI can never guess a
     * rate different from the one actually charged.
     */
    private TaxMeta requireTaxMeta(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        List<TaxMeta> metas = jdbc.query(
                "SELECT scheme, factor, rate FROM tax_rate WHERE code = ?",
                (rs, i) -> new TaxMeta(rs.getString(1), rs.getString(2),
                        rs.getBigDecimal(3)),
                code.trim().toUpperCase(java.util.Locale.ROOT));
        if (metas.isEmpty() || metas.getFirst() == null) {
            throw new IllegalArgumentException("La tasa de impuesto '" + code
                    + "' no existe en el catálogo");
        }
        return metas.getFirst();
    }

    /** Mutable aggregate of one concept across the period (global invoice). */
    private static final class AggregatedConcept {
        private final String key;
        private final String description;
        private final String sku;
        private final String unitSat;
        private final String taxRateCode;
        private final TaxMeta taxMeta;
        private BigDecimal quantitySum = BigDecimal.ZERO;
        private BigDecimal lineTotalSum = BigDecimal.ZERO;
        private BigDecimal taxTotalSum = BigDecimal.ZERO;

        AggregatedConcept(String key, String description, String sku, String unitSat,
                          String taxRateCode, TaxMeta taxMeta) {
            this.key = key;
            this.description = description;
            this.sku = sku;
            this.unitSat = unitSat;
            this.taxRateCode = taxRateCode;
            this.taxMeta = taxMeta;
        }

        String key() { return key; }
        String description() { return description; }
        String sku() { return sku; }
        String unitSat() { return unitSat; }
        String taxRateCode() { return taxRateCode; }
        TaxMeta taxMeta() { return taxMeta; }
        BigDecimal quantitySum() { return quantitySum; }
        BigDecimal lineTotalSum() { return lineTotalSum; }
        BigDecimal taxTotalSum() { return taxTotalSum; }

        void add(BigDecimal quantity, BigDecimal lineTotal, BigDecimal taxTotal) {
            this.quantitySum = this.quantitySum.add(quantity);
            this.lineTotalSum = this.lineTotalSum.add(lineTotal);
            this.taxTotalSum = this.taxTotalSum.add(taxTotal);
        }
    }

    /** Frozen value of one aggregated concept at build time. */
    private record ConceptSnapshot(AggregatedConcept aggregate, BigDecimal quantity,
                                   BigDecimal unitPrice, BigDecimal lineTotal,
                                   BigDecimal taxTotal) {
    }
}
