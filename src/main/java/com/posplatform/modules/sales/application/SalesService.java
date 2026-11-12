package com.posplatform.modules.sales.application;

import com.posplatform.modules.catalog.ProductVariant;
import com.posplatform.modules.catalog.infrastructure.ProductVariantRepository;
import com.posplatform.modules.inventory.application.InventoryService;
import com.posplatform.modules.sales.Sale;
import com.posplatform.modules.sales.SaleLine;
import com.posplatform.modules.sales.SalePayment;
import com.posplatform.modules.sales.infrastructure.SaleLineRepository;
import com.posplatform.modules.sales.infrastructure.SalePaymentRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SALES use case (fase 4 · sales-payments.md): create/cancel completed sales
 * with payment records. Invariants implemented here:
 *
 *  - idempotency: the key (V6 unique (tenant_id, key)) guards POST /sales — a
 *    retry returns the SAME sale without duplicating anything;
 *  - folio: per-tenant atomic counter (INSERT ... ON CONFLICT ... RETURNING);
 *  - line snapshot: product name/sku/unit/price copied at sale time (history
 *    never recalculates); line_total = qty*price - discount @ 2dp HALF_UP;
 *  - tax (v0.8 · ADR-018): 0..1 traslado per line via the variant's
 *    tax_rate_code into the global catalog; tax = line net x rate @ 2dp
 *    HALF_UP, SNAPSHOT by line and summed once into the sale (tax_total).
 *    A variant without code keeps the legacy behavior EXACTLY (tax 0);
 *  - money: BigDecimal everywhere, NUMERIC in the DB, string in the API;
 *  - payments must sum EXACTLY the total (cash change arrives with the POS);
 *  - cancel: only COMPLETED sales, and the stock it consumed comes back via
 *    compensatory SALE_RETURN movements (ADR-004 rule 1);
 *  - stock consumption runs in the SAME transaction via InventoryService
 *    (fase 5): FORBIDDEN verticals refuse to sell without stock (409);
 *    tenants without warehouses sell without stock control (v1).
 *
 * NO fiscal documents (ADR-006) yet: it arrives with its own phase; outbox
 * events are the contract seam.
 */
@Service
public class SalesService {

    private static final Set<String> METHODS = Set.of("CASH", "CARD", "TRANSFER", "OTHER");

    private final SaleRepository saleRepository;
    private final SaleLineRepository lineRepository;
    private final SalePaymentRepository paymentRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryService inventoryService;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;
    private final JdbcTemplate jdbc;

    public SalesService(SaleRepository saleRepository,
                        SaleLineRepository lineRepository,
                        SalePaymentRepository paymentRepository,
                        ProductVariantRepository variantRepository,
                        InventoryService inventoryService,
                        OutboxWriter outboxWriter,
                        TenantTransactionRunner tenantRunner,
                        JdbcTemplate jdbc) {
        this.saleRepository = saleRepository;
        this.lineRepository = lineRepository;
        this.paymentRepository = paymentRepository;
        this.variantRepository = variantRepository;
        this.inventoryService = inventoryService;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
        this.jdbc = jdbc;
    }

    // ---- Commands ----

    public record SaleLineCommand(
            UUID variantId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount
    ) {
    }

    public record SalePaymentCommand(
            String method,
            BigDecimal amount,
            String reference
    ) {
    }

    public record CreateSaleCommand(
            List<SaleLineCommand> lines,
            List<SalePaymentCommand> payments,
            String note,
            UUID warehouseId
    ) {
    }

    // ---- Views ----

    public record SaleLineView(
            UUID id,
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

    public record SalePaymentView(
            UUID id,
            String method,
            String amount,
            String reference
    ) {
    }

    public record SaleView(
            UUID id,
            long folio,
            String status,
            String statusReason,
            String note,
            String currency,
            String subtotal,
            String discountTotal,
            String taxTotal,
            String total,
            String idempotencyKey,
            String cashSessionId,
            String createdAt,
            List<SaleLineView> lines,
            List<SalePaymentView> payments
    ) {
    }

    public record SaleOutcome(SaleView sale, boolean replay) {
    }

    // ---- Use cases ----

    @Transactional
    public SaleOutcome create(UUID tenantId, String idempotencyKey, CreateSaleCommand command) {
        return tenantRunner.run(tenantId, () -> {
            int granted = jdbc.update("""
                            INSERT INTO idempotency_key (id, tenant_id, key)
                            VALUES (?, ?, ?)
                            ON CONFLICT (tenant_id, key) DO NOTHING
                            """,
                    UuidV7.next(), tenantId, idempotencyKey);
            if (granted == 0) {
                Sale existing = saleRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                        .orElseThrow(() -> new ConflictException("IDEMPOTENCY_KEY_IN_FLIGHT",
                                "La clave de idempotencia está en proceso en otra solicitud"));
                return new SaleOutcome(viewOf(tenantId, existing), true);
            }

            validateShape(command);

            // Stock seam (ADR-004): the sale consumes inventory in THIS
            // transaction; a failing moveOut rolls the whole sale back.
            Optional<UUID> stockWarehouse = inventoryService
                    .resolveSaleWarehouse(tenantId, command.warehouseId());

            long folio = requireFolio(tenantId);

            // ADR-017 attach: a sale collected while a drawer is OPEN belongs
            // to that shift (reconciliation source of the cash count).
            List<UUID> openSessions = jdbc.query(
                    "SELECT id FROM cash_session WHERE tenant_id = ? AND status = 'OPEN' LIMIT 1",
                    (rs, i) -> rs.getObject(1, UUID.class), tenantId);
            UUID cashSessionId = openSessions.isEmpty() ? null : openSessions.get(0);

            List<SaleLine> lines = new ArrayList<>();
            BigDecimal subtotal = BigDecimal.ZERO;
            BigDecimal discountTotal = BigDecimal.ZERO;
            BigDecimal taxTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            for (SaleLineCommand lineCommand : command.lines()) {
                ProductVariant variant = variantRepository.findById(lineCommand.variantId())
                        .filter(v -> v.getTenantId().equals(tenantId))
                        .orElseThrow(() -> new NotFoundException("variant", lineCommand.variantId().toString()));

                BigDecimal quantity = requirePositive("cantidad", lineCommand.quantity(), 4);
                BigDecimal unitPrice = lineCommand.unitPrice() == null || lineCommand.unitPrice().signum() == 0
                        ? requireCatalogPrice(variant)
                        : requirePositive("precio unitario", lineCommand.unitPrice(), 4);
                BigDecimal discount = lineCommand.discount() == null || lineCommand.discount().signum() == 0
                        ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                        : lineCommand.discount().setScale(4, RoundingMode.HALF_UP);
                if (discount.signum() < 0 || discount.compareTo(quantity.multiply(unitPrice)) > 0) {
                    throw new IllegalArgumentException(
                            "El descuento no puede ser negativo ni mayor al importe de la línea");
                }

                BigDecimal lineTotal = quantity.multiply(unitPrice).subtract(discount)
                        .setScale(2, RoundingMode.HALF_UP);
                subtotal = subtotal.add(quantity.multiply(unitPrice));
                discountTotal = discountTotal.add(discount);

                // ADR-018: the tax is computed ONCE per line, on the line net
                // (qty x price - discount = the CFDI "Base"), rounded 2dp
                // HALF_UP and SNAPSHOTTED. No rate code (or an unknown one is
                // impossible here: the variant validates it in catalog) keeps
                // the legacy behavior EXACTLY: tax 0, totals unchanged.
                String lineTaxCode = normalizeTaxCode(variant.getTaxRateCode());
                BigDecimal lineTax = lineTaxCode == null
                        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                        : lineTotal.multiply(requireTaxRate(lineTaxCode))
                                .setScale(2, RoundingMode.HALF_UP);
                taxTotal = taxTotal.add(lineTax);

                lines.add(new SaleLine(
                        tenantId, null, variant.getId(),
                        variant.getProduct().getName(), variant.getSku(), variant.getUnit(),
                        quantity, unitPrice, discount, lineTotal, lineTaxCode, lineTax));
            }

            subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
            discountTotal = discountTotal.setScale(2, RoundingMode.HALF_UP);
            BigDecimal total = subtotal.subtract(discountTotal).add(taxTotal).setScale(2, RoundingMode.HALF_UP);

            BigDecimal paid = BigDecimal.ZERO.setScale(2);
            List<SalePayment> payments = new ArrayList<>();
            for (SalePaymentCommand paymentCommand : command.payments()) {
                if (paymentCommand.method() == null || !METHODS.contains(paymentCommand.method())) {
                    throw new IllegalArgumentException("Método de pago no válido: " + paymentCommand.method()
                            + ". Métodos: CASH, CARD, TRANSFER, OTHER");
                }
                BigDecimal amount = requirePositive("monto de pago", paymentCommand.amount(), 2)
                        .setScale(2, RoundingMode.HALF_UP);
                payments.add(new SalePayment(
                        tenantId, null, SalePayment.Method.valueOf(paymentCommand.method()),
                        amount, blankToNull(paymentCommand.reference())));
                paid = paid.add(amount);
            }
            if (paid.compareTo(total) != 0) {
                throw new IllegalArgumentException("Los pagos deben sumar exactamente el total ("
                        + total.toPlainString() + "); sumaron " + paid.toPlainString());
            }

            Sale sale = new Sale(tenantId, folio, blankToNull(command.note()),
                    subtotal, discountTotal, taxTotal, total, idempotencyKey);
            if (cashSessionId != null) {
                sale.attachCashSession(cashSessionId);
            }
            sale = saleRepository.save(sale);

            List<SaleLineView> lineViews = new ArrayList<>();
            for (SaleLine linePre : lines) {
                SaleLine saved = lineRepository.save(new SaleLine(
                        tenantId, sale, linePre.getVariantId(), linePre.getProductName(),
                        linePre.getSku(), linePre.getUnit(), linePre.getQuantity(),
                        linePre.getUnitPrice(), linePre.getDiscount(), linePre.getLineTotal(),
                        linePre.getTaxRateCode(), linePre.getTaxTotal()));
                lineViews.add(new SaleLineView(
                        saved.getId(), saved.getVariantId(), saved.getProductName(),
                        saved.getSku(), saved.getUnit(),
                        saved.getQuantity().toPlainString(), saved.getUnitPrice().toPlainString(),
                        saved.getDiscount().toPlainString(), saved.getLineTotal().toPlainString(),
                        saved.getTaxRateCode(), saved.getTaxTotal().toPlainString()));
            }

            List<SalePaymentView> paymentViews = new ArrayList<>();
            for (SalePayment paymentPre : payments) {
                SalePayment saved = paymentRepository.save(new SalePayment(
                        tenantId, sale, paymentPre.getMethod(), paymentPre.getAmount(),
                        paymentPre.getReference()));
                paymentViews.add(new SalePaymentView(
                        saved.getId(), String.valueOf(saved.getMethod()),
                        saved.getAmount().toPlainString(), saved.getReference()));
            }

            // same-transaction stock consumption (ADR-004 rule 2); skipped
            // when the tenant has no warehouse at all.
            if (stockWarehouse.isPresent()) {
                inventoryService.consumeForSale(
                        tenantId, stockWarehouse.get(),
                        lines.stream()
                                .map(l -> new InventoryService.SaleStockLine(
                                        l.getVariantId(), l.getQuantity()))
                                .toList(),
                        sale.getId());
            }

            jdbc.update("""
                            UPDATE idempotency_key SET resource_type = ?, resource_id = ?
                            WHERE tenant_id = ? AND key = ?
                            """,
                    "sale", sale.getId(), tenantId, idempotencyKey);

            outboxWriter.append(
                    tenantId, "sales.sale.completed", "sale", sale.getId(),
                    java.util.Map.of("folio", folio, "total", total.toPlainString(),
                            "lines", lineViews.size(), "idempotencyKey", idempotencyKey));

            return new SaleOutcome(new SaleView(
                    sale.getId(), sale.getFolio(), String.valueOf(sale.getStatus()),
                    sale.getStatusReason(), sale.getNote(), sale.getCurrency(),
                    subtotal.toPlainString(), discountTotal.toPlainString(),
                    taxTotal.toPlainString(), total.toPlainString(), idempotencyKey,
                    cashSessionId == null ? null : cashSessionId.toString(),
                    sale.getCreatedAt().toString(), lineViews, paymentViews), false);
        });
    }

    @Transactional
    public SaleView cancel(UUID tenantId, UUID saleId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("El motivo de cancelación es requerido");
        }
        return tenantRunner.run(tenantId, () -> {
            Sale sale = saleRepository.findByTenantIdAndId(tenantId, saleId)
                    .orElseThrow(() -> new NotFoundException("sale", saleId.toString()));
            if (sale.getStatus() == Sale.Status.CANCELED) {
                throw new ConflictException("SALE_ALREADY_CANCELED", "La venta ya está cancelada");
            }
            sale.cancel(reason.trim());

            // stock returns via compensatory movements in the SAME transaction
            // (no-op when the sale was not stock-tracked)
            inventoryService.returnForSale(tenantId, sale.getId());

            outboxWriter.append(
                    tenantId, "sales.sale.canceled", "sale", sale.getId(),
                    java.util.Map.of("folio", sale.getFolio(), "reason", reason.trim()));

            return viewOf(tenantId, sale);
        });
    }

    @Transactional(readOnly = true)
    public SaleView get(UUID tenantId, UUID saleId) {
        return tenantRunner.run(tenantId, () ->
                saleRepository.findByTenantIdAndId(tenantId, saleId)
                        .map(sale -> viewOf(tenantId, sale))
                        .orElseThrow(() -> new NotFoundException("sale", saleId.toString())));
    }

    @Transactional(readOnly = true)
    public List<SaleView> list(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> {
            List<SaleView> views = new ArrayList<>();
            for (Sale sale : saleRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId)) {
                views.add(viewOf(tenantId, sale));
            }
            return views;
        });
    }

    // ---- Internals ----

    private SaleView viewOf(UUID tenantId, Sale sale) {
        List<SaleLineView> lines = lineRepository
                .findByTenantIdAndSaleIdOrderByCreatedAtAsc(tenantId, sale.getId())
                .stream()
                .map(line -> new SaleLineView(
                        line.getId(), line.getVariantId(), line.getProductName(),
                        line.getSku(), line.getUnit(),
                        line.getQuantity().toPlainString(), line.getUnitPrice().toPlainString(),
                        line.getDiscount().toPlainString(), line.getLineTotal().toPlainString(),
                        line.getTaxRateCode(), line.getTaxTotal().toPlainString()))
                .toList();
        List<SalePaymentView> payments = paymentRepository
                .findByTenantIdAndSaleIdOrderByCreatedAtAsc(tenantId, sale.getId())
                .stream()
                .map(payment -> new SalePaymentView(
                        payment.getId(), String.valueOf(payment.getMethod()),
                        payment.getAmount().toPlainString(), payment.getReference()))
                .toList();
        return new SaleView(
                sale.getId(), sale.getFolio(), String.valueOf(sale.getStatus()),
                sale.getStatusReason(), sale.getNote(), sale.getCurrency(),
                sale.getSubtotal().toPlainString(), sale.getDiscountTotal().toPlainString(),
                sale.getTaxTotal().toPlainString(), sale.getTotal().toPlainString(),
                sale.getIdempotencyKey(),
                sale.getCashSessionId() == null ? null : sale.getCashSessionId().toString(),
                sale.getCreatedAt().toString(), lines, payments);
    }

    /** Atomic per-tenant folio (row lock via upsert's ON CONFLICT). */
    private long requireFolio(UUID tenantId) {
        Long folio = jdbc.queryForObject("""
                        INSERT INTO sale_folio (tenant_id, last)
                        VALUES (?, 1)
                        ON CONFLICT (tenant_id) DO UPDATE SET last = sale_folio.last + 1
                        RETURNING last
                        """,
                Long.class, tenantId);
        return folio == null ? 1 : folio;
    }

    private BigDecimal requireCatalogPrice(ProductVariant variant) {
        if (variant.getPrice() == null) {
            throw new IllegalArgumentException("La variante " + variant.getSku()
                    + " no tiene precio en el catálogo; envía unitPrice");
        }
        return variant.getPrice();
    }

    /** Uppercase-normalized tax code (catalog saves it normalized) or null. */
    private static String normalizeTaxCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Rate of the GLOBAL tax_rate catalog (V15). The code itself is law data:
     * sale lines are validated against the catalog at ASSIGN time in the
     * catalog, so an unknown/blank code here is a programming error and the
     * lookup fails closed. No `active` filter: a sale made while a rate was
     * still listed must keep its own rate value.
     */
    private BigDecimal requireTaxRate(String code) {
        List<BigDecimal> rates = jdbc.query(
                "SELECT rate FROM tax_rate WHERE code = ?",
                (rs, i) -> rs.getBigDecimal(1), code);
        if (rates.isEmpty() || rates.getFirst() == null) {
            throw new IllegalArgumentException("La tasa de impuesto '" + code
                    + "' no existe en el catálogo");
        }
        return rates.getFirst();
    }

    /** Structural guards (non-null lists, present values) before any money math. */
    private static void validateShape(CreateSaleCommand command) {
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("La venta requiere al menos una línea");
        }
        if (command.payments() == null || command.payments().isEmpty()) {
            throw new IllegalArgumentException("La venta requiere al menos un pago");
        }
        for (SaleLineCommand line : command.lines()) {
            if (line.variantId() == null) {
                throw new IllegalArgumentException("Cada línea requiere variantId");
            }
        }
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
