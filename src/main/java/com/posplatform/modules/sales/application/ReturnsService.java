package com.posplatform.modules.sales.application;

import com.posplatform.modules.inventory.application.InventoryService;
import com.posplatform.modules.sales.Sale;
import com.posplatform.modules.sales.SaleLine;
import com.posplatform.modules.sales.SaleReturn;
import com.posplatform.modules.sales.SaleReturnLine;
import com.posplatform.modules.sales.infrastructure.SaleLineRepository;
import com.posplatform.modules.sales.infrastructure.SaleReturnLineRepository;
import com.posplatform.modules.sales.infrastructure.SaleReturnRepository;
import com.posplatform.modules.sales.infrastructure.SaleRepository;
import com.posplatform.shared.api.ConflictException;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.domain.UuidV7;
import com.posplatform.shared.outbox.OutboxWriter;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RETURNS use case (fase 9 · ADR-019): the inverse operation of a sale.
 * Invariants implemented here:
 *
 *  - target: ONLY COMPLETED sales (a canceled sale already gave its stock
 *    back through the cancellation — returning it again would double it);
 *  - quantity guard: a sale line accepts returns up to its ORIGINAL quantity
 *    minus what prior returns took (per-line, per-tenant sum);
 *  - money: each return line PRORATES the sale line SNAPSHOT proportionally
 *    to the returned quantity — refund net = line_total x q / qty, refund tax
 *    = tax_total x q / qty (both 2dp HALF_UP). The taxes the customer paid
 *    come back EXACTLY as charged (ADR-018): the NC never re-rates anything;
 *  - refund: ONE row per return; refund_method is the customer's choice and
 *    refund_amount IS the return total (never client input);
 *  - stock: returned via InventoryService.returnForSalePartial in the SAME
 *    transaction, lot by lot over the sale's own consumption trail;
 *  - idempotency: same granted-row pattern as sales (key unique per tenant,
 *    replay returns the SAME return);
 *  - cash: a CASH refund attaches to the OPEN shift and REDUCES the expected
 *    drawer at close (CashService subtracts refunds).
 *
 * The credit note (CFDI tipo E) is a SEPARATE invoicing use case — a return
 * is fiscal when its sale has a stamped invoice; the NC reproduces this
 * return's snapshot 1:1.
 */
@Service
public class ReturnsService {

    private static final Set<String> METHODS = Set.of("CASH", "CARD", "TRANSFER", "OTHER");

    private final SaleRepository saleRepository;
    private final SaleLineRepository lineRepository;
    private final SaleReturnRepository returnRepository;
    private final SaleReturnLineRepository returnLineRepository;
    private final InventoryService inventoryService;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;
    private final JdbcTemplate jdbc;

    public ReturnsService(SaleRepository saleRepository,
                          SaleLineRepository lineRepository,
                          SaleReturnRepository returnRepository,
                          SaleReturnLineRepository returnLineRepository,
                          InventoryService inventoryService,
                          OutboxWriter outboxWriter,
                          TenantTransactionRunner tenantRunner,
                          JdbcTemplate jdbc) {
        this.saleRepository = saleRepository;
        this.lineRepository = lineRepository;
        this.returnRepository = returnRepository;
        this.returnLineRepository = returnLineRepository;
        this.inventoryService = inventoryService;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
        this.jdbc = jdbc;
    }

    // ---- Commands ----

    public record ReturnLineCommand(UUID saleLineId, BigDecimal quantity) {
    }

    public record CreateReturnCommand(
            List<ReturnLineCommand> lines,
            String refundMethod,
            String refundReference,
            String note
    ) {
    }

    /** create() result: the view plus whether it was an idempotent REPLAY. */
    public record ReturnOutcome(ReturnView returnView, boolean replay) {
    }

    // ---- Views ----

    public record ReturnLineView(
            UUID id,
            UUID saleLineId,
            UUID variantId,
            String productName,
            String sku,
            String unit,
            String quantity,
            String unitPrice,
            String discount,
            String lineTotal,
            String taxRateCode,
            String taxTotal
    ) {
    }

    public record ReturnView(
            UUID id,
            long folio,
            String folioLabel,
            UUID saleId,
            long saleFolio,
            String status,
            String note,
            String currency,
            String subtotal,
            String discountTotal,
            String taxTotal,
            String total,
            String refundMethod,
            String refundAmount,
            String refundReference,
            String cashSessionId,
            String creditNoteLabel,
            boolean creditNoteIssuable,
            String createdAt,
            List<ReturnLineView> lines
    ) {
    }

    // ---- Use cases ----

    @Transactional
    public ReturnOutcome create(UUID tenantId, UUID saleId, String idempotencyKey,
                                CreateReturnCommand command) {
        return tenantRunner.run(tenantId, () -> {
            int granted = jdbc.update("""
                            INSERT INTO idempotency_key (id, tenant_id, key)
                            VALUES (?, ?, ?)
                            ON CONFLICT (tenant_id, key) DO NOTHING
                            """,
                    UuidV7.next(), tenantId, idempotencyKey);
            if (granted == 0) {
                SaleReturn existing = returnRepository
                        .findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                        .orElseThrow(() -> new ConflictException("IDEMPOTENCY_KEY_IN_FLIGHT",
                                "La clave de idempotencia está en proceso en otra solicitud"));
                return new ReturnOutcome(viewOf(tenantId, existing), true);
            }

            if (command.lines() == null || command.lines().isEmpty()) {
                throw new IllegalArgumentException("La devolución requiere al menos una línea");
            }
            if (command.refundMethod() == null || !METHODS.contains(command.refundMethod())) {
                throw new IllegalArgumentException("Método de reembolso no válido: "
                        + command.refundMethod() + ". Métodos: CASH, CARD, TRANSFER, OTHER");
            }

            Sale sale = saleRepository.findByTenantIdAndId(tenantId, saleId)
                    .orElseThrow(() -> new NotFoundException("sale", saleId.toString()));
            if (sale.getStatus() != Sale.Status.COMPLETED) {
                throw new ConflictException("SALE_NOT_COMPLETE",
                        "Solo se devuelven ventas COMPLETED (una venta cancelada ya devolvió su stock)");
            }

            // the sale's own lines, mapped for provenance validation
            Map<UUID, SaleLine> saleLines = new LinkedHashMap<>();
            for (SaleLine line : lineRepository
                    .findByTenantIdAndSaleIdOrderByCreatedAtAsc(tenantId, sale.getId())) {
                saleLines.put(line.getId(), line);
            }

            long folio = requireFolio(tenantId);

            // ADR-017 attach: a refund paid in CASH while a drawer is OPEN
            // reduces that shift's expected drawer at close.
            List<UUID> openSessions = jdbc.query(
                    "SELECT id FROM cash_session WHERE tenant_id = ? AND status = 'OPEN' LIMIT 1",
                    (rs, i) -> rs.getObject(1, UUID.class), tenantId);
            UUID cashSessionId = openSessions.isEmpty() ? null : openSessions.get(0);

            List<SaleReturnLine> returnLines = new ArrayList<>();
            BigDecimal subtotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal discountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            for (ReturnLineCommand lineCommand : command.lines()) {
                if (lineCommand.saleLineId() == null) {
                    throw new IllegalArgumentException("Cada línea requiere saleLineId");
                }
                SaleLine saleLine = saleLines.get(lineCommand.saleLineId());
                if (saleLine == null) {
                    // foreign sale_line or foreign sale: both are fail-closed 404
                    throw new NotFoundException("sale_line", lineCommand.saleLineId().toString());
                }

                BigDecimal requested = requirePositive("cantidad a devolver",
                        lineCommand.quantity(), 4);
                BigDecimal alreadyReturned = returnLineRepository
                        .sumReturnedQuantity(tenantId, saleLine.getId());
                BigDecimal returnable = saleLine.getQuantity().subtract(alreadyReturned);
                if (requested.compareTo(returnable) > 0) {
                    throw new ConflictException("RETURN_QUANTITY_EXCEEDS",
                            "La línea " + saleLine.getSku() + " admite devolver "
                                    + returnable.toPlainString() + " (solicitaste "
                                    + requested.toPlainString() + ")");
                }

                // PRORATION of the snapshot (ADR-019): the refund reproduces
                // the original line money proportionally — gross = q x price,
                // net = line_total x q / qty, tax = tax_total x q / qty
                // (2dp HALF_UP). The customer gets back the SAME tax charged.
                BigDecimal gross = requested.multiply(saleLine.getUnitPrice())
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal net = saleLine.getLineTotal().multiply(requested)
                        .divide(saleLine.getQuantity(), 2, RoundingMode.HALF_UP);
                BigDecimal discount = gross.subtract(net).setScale(4, RoundingMode.HALF_UP);
                boolean taxed = saleLine.getTaxRateCode() != null
                        && !saleLine.getTaxRateCode().isBlank();
                BigDecimal tax = taxed
                        ? saleLine.getTaxTotal().multiply(requested)
                                .divide(saleLine.getQuantity(), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

                subtotal = subtotal.add(gross);
                discountTotal = discountTotal.add(discount);
                taxTotal = taxTotal.add(tax);

                returnLines.add(new SaleReturnLine(tenantId, null, saleLine.getId(),
                        saleLine.getVariantId(), saleLine.getProductName(), saleLine.getSku(),
                        saleLine.getUnit(), requested, saleLine.getUnitPrice(), discount,
                        net, saleLine.getTaxRateCode(), tax));
            }

            subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
            discountTotal = discountTotal.setScale(2, RoundingMode.HALF_UP);
            taxTotal = taxTotal.setScale(2, RoundingMode.HALF_UP);
            BigDecimal total = subtotal.subtract(discountTotal).add(taxTotal)
                    .setScale(2, RoundingMode.HALF_UP);

            SaleReturn saleReturn = new SaleReturn(tenantId, sale, folio,
                    blankToNull(command.note()), subtotal, discountTotal, taxTotal, total,
                    SaleReturn.RefundMethod.valueOf(command.refundMethod()),
                    blankToNull(command.refundReference()), idempotencyKey);
            if (cashSessionId != null) {
                saleReturn.attachCashSession(cashSessionId);
            }
            saleReturn = returnRepository.save(saleReturn);

            List<ReturnLineView> lineViews = new ArrayList<>();
            List<InventoryService.SaleStockLine> stockLines = new ArrayList<>();
            for (SaleReturnLine linePre : returnLines) {
                SaleReturnLine saved = returnLineRepository.save(new SaleReturnLine(
                        tenantId, saleReturn, linePre.getSaleLineId(), linePre.getVariantId(),
                        linePre.getProductName(), linePre.getSku(), linePre.getUnit(),
                        linePre.getQuantity(), linePre.getUnitPrice(), linePre.getDiscount(),
                        linePre.getLineTotal(), linePre.getTaxRateCode(), linePre.getTaxTotal()));
                lineViews.add(new ReturnLineView(
                        saved.getId(), saved.getSaleLineId(), saved.getVariantId(),
                        saved.getProductName(), saved.getSku(), saved.getUnit(),
                        saved.getQuantity().toPlainString(), saved.getUnitPrice().toPlainString(),
                        saved.getDiscount().toPlainString(), saved.getLineTotal().toPlainString(),
                        saved.getTaxRateCode(), saved.getTaxTotal().toPlainString()));
                stockLines.add(new InventoryService.SaleStockLine(
                        saved.getVariantId(), saved.getQuantity()));
            }

            // stock back in the SAME transaction (lot by lot over the sale's
            // own consumption trail; no-op without stock control)
            inventoryService.returnForSalePartial(tenantId, sale.getId(), stockLines);

            jdbc.update("""
                            UPDATE idempotency_key SET resource_type = ?, resource_id = ?
                            WHERE tenant_id = ? AND key = ?
                            """,
                    "sale_return", saleReturn.getId(), tenantId, idempotencyKey);

            outboxWriter.append(tenantId, "sales.return.completed", "sale_return",
                    saleReturn.getId(),
                    Map.of("folio", folio, "saleId", sale.getId().toString(),
                            "total", total.toPlainString(),
                            "refundMethod", command.refundMethod(),
                            "lines", lineViews.size(), "idempotencyKey", idempotencyKey));

            return new ReturnOutcome(viewOf(tenantId, saleReturn), false);
        });
    }

    /** Recent returns of the tenant (UI groups by sale). */
    @Transactional(readOnly = true)
    public List<ReturnView> list(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> {
            List<ReturnView> views = new ArrayList<>();
            for (SaleReturn saleReturn :
                    returnRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId)) {
                views.add(viewOf(tenantId, saleReturn));
            }
            return views;
        });
    }

    /** Returns of ONE sale (fail-closed 404 on foreign/missing sale). */
    @Transactional(readOnly = true)
    public List<ReturnView> listBySale(UUID tenantId, UUID saleId) {
        return tenantRunner.run(tenantId, () -> {
            Sale sale = saleRepository.findByTenantIdAndId(tenantId, saleId)
                    .orElseThrow(() -> new NotFoundException("sale", saleId.toString()));
            List<ReturnView> views = new ArrayList<>();
            for (SaleReturn saleReturn : returnRepository
                    .findByTenantIdAndSale_IdOrderByCreatedAtDesc(tenantId, sale.getId())) {
                views.add(viewOf(tenantId, saleReturn));
            }
            return views;
        });
    }

    // ---- Internals ----

    private ReturnView viewOf(UUID tenantId, SaleReturn saleReturn) {
        List<ReturnLineView> lines = returnLineRepository
                .findByTenantIdAndSaleReturn_IdOrderByCreatedAtAsc(tenantId, saleReturn.getId())
                .stream()
                .map(line -> new ReturnLineView(
                        line.getId(), line.getSaleLineId(), line.getVariantId(),
                        line.getProductName(), line.getSku(), line.getUnit(),
                        line.getQuantity().toPlainString(), line.getUnitPrice().toPlainString(),
                        line.getDiscount().toPlainString(), line.getLineTotal().toPlainString(),
                        line.getTaxRateCode(), line.getTaxTotal().toPlainString()))
                .toList();

        // fiscal state of the return: the active NC (if any) and whether the
        // sale's own stamped invoice lets us ISSUE one now.
        List<String> noteLabels = jdbc.query("""
                        SELECT serie || '-' || lpad(folio::text, 6, '0')
                        FROM invoice
                        WHERE tenant_id = ? AND return_id = ? AND status = 'STAMPED'
                        LIMIT 1
                        """,
                (rs, i) -> rs.getString(1), tenantId, saleReturn.getId());
        String creditNoteLabel = noteLabels.isEmpty() ? null : noteLabels.get(0);
        boolean hasActiveNote = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM invoice
                        WHERE tenant_id = ? AND return_id = ?
                          AND status IN ('PENDING', 'STAMPED')
                        """,
                Long.class, tenantId, saleReturn.getId()) > 0;
        boolean saleHasStampedInvoice = !jdbc.query("""
                        SELECT 1 FROM invoice
                        WHERE tenant_id = ? AND sale_id = ? AND status = 'STAMPED'
                          AND kind = 'INDIVIDUAL'
                        LIMIT 1
                        """,
                (rs, i) -> rs.getString(1), tenantId, saleReturn.getSaleId()).isEmpty();

        return new ReturnView(
                saleReturn.getId(), saleReturn.getFolio(),
                "DV-" + String.format("%06d", saleReturn.getFolio()),
                saleReturn.getSaleId(),
                saleReturn.getSale().getFolio(), String.valueOf(saleReturn.getStatus()),
                saleReturn.getNote(), saleReturn.getCurrency(),
                saleReturn.getSubtotal().toPlainString(),
                saleReturn.getDiscountTotal().toPlainString(),
                saleReturn.getTaxTotal().toPlainString(),
                saleReturn.getTotal().toPlainString(),
                String.valueOf(saleReturn.getRefundMethod()),
                saleReturn.getRefundAmount().toPlainString(),
                saleReturn.getRefundReference(),
                saleReturn.getCashSessionId() == null ? null : saleReturn.getCashSessionId().toString(),
                creditNoteLabel,
                !hasActiveNote && saleHasStampedInvoice,
                saleReturn.getCreatedAt().toString(), lines);
    }

    /** Atomic per-tenant folio (return_folio, same pattern as sale_folio). */
    private long requireFolio(UUID tenantId) {
        Long folio = jdbc.queryForObject("""
                        INSERT INTO return_folio (tenant_id, last)
                        VALUES (?, 1)
                        ON CONFLICT (tenant_id) DO UPDATE SET last = return_folio.last + 1
                        RETURNING last
                        """,
                Long.class, tenantId);
        return folio == null ? 1 : folio;
    }

    private static BigDecimal requirePositive(String label, BigDecimal value, int scale) {
        if (value == null) {
            throw new IllegalArgumentException(label + " es requerida");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(label + " debe ser positiva");
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
