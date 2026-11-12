package com.posplatform.modules.inventory.application;

import com.posplatform.modules.catalog.ProductVariant;
import com.posplatform.modules.catalog.infrastructure.ProductVariantRepository;
import com.posplatform.modules.inventory.InventoryBalance;
import com.posplatform.modules.inventory.InventoryLot;
import com.posplatform.modules.inventory.InventoryMovement;
import com.posplatform.modules.inventory.Warehouse;
import com.posplatform.modules.inventory.infrastructure.InventoryBalanceRepository;
import com.posplatform.modules.inventory.infrastructure.InventoryLotRepository;
import com.posplatform.modules.inventory.infrastructure.InventoryMovementRepository;
import com.posplatform.modules.inventory.infrastructure.WarehouseRepository;
import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.VerticalProfile;
import com.posplatform.modules.organization.infrastructure.OrganizationRepository;
import com.posplatform.shared.api.ConflictException;
import com.posplatform.shared.api.InsufficientStockException;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.outbox.OutboxWriter;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * INVENTORY use case (fase 5 · ADR-004 + ADR-010): warehouses, immutable
 * movements, lot/expiry tracking and aggregate balances. Invariants:
 *
 *  - movements are NEVER updated: corrections are compensatory movements
 *    linked through related_movement_id (a sale cancel creates SALE_RETURN
 *    movements pointing at the original SALE outs);
 *  - balances update in the SAME transaction as the movement;
 *  - negative stock is the vertical's POLICY: FORBIDDEN -> 409
 *    INSUFFICIENT_STOCK; ALLOWED(_WITH_ALERT) -> proceeds (balance negative);
 *  - verticals that track LOT/EXPIRY (ADR-010) require them on stock IN and
 *    consume FEFO (first expiring first) on stock OUT;
 *  - transfers are a MIRROR PAIR (TRANSFER_OUT -> TRANSFER_IN) linked via
 *    related_movement_id and never negative (physical stock);
 *  - quantities: BigDecimal everywhere, NUMERIC(14,4) in the DB, string API.
 *
 * The SALES module consumes this service INSIDE its own tenant transaction
 * (consumeForSale / returnForSale / resolveSaleWarehouse) so the sale, its
 * payments and the stock movements commit or roll back together.
 */
@Service
public class InventoryService {

    private final WarehouseRepository warehouseRepository;
    private final InventoryLotRepository lotRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final InventoryMovementRepository movementRepository;
    private final ProductVariantRepository variantRepository;
    private final OrganizationRepository organizationRepository;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;
    private final JdbcTemplate jdbc;

    public InventoryService(WarehouseRepository warehouseRepository,
                            InventoryLotRepository lotRepository,
                            InventoryBalanceRepository balanceRepository,
                            InventoryMovementRepository movementRepository,
                            ProductVariantRepository variantRepository,
                            OrganizationRepository organizationRepository,
                            OutboxWriter outboxWriter,
                            TenantTransactionRunner tenantRunner,
                            JdbcTemplate jdbc) {
        this.warehouseRepository = warehouseRepository;
        this.lotRepository = lotRepository;
        this.balanceRepository = balanceRepository;
        this.movementRepository = movementRepository;
        this.variantRepository = variantRepository;
        this.organizationRepository = organizationRepository;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
        this.jdbc = jdbc;
    }

    // ---- Commands ----

    public record StockLineCommand(
            UUID variantId,
            BigDecimal quantity,
            String lotCode,
            LocalDate expiryDate
    ) {
    }

    public record CreateWarehouseCommand(String code, String name, UUID branchId) {
    }

    public record ReceiveCommand(
            UUID warehouseId,
            List<StockLineCommand> lines,
            String reference,
            String note
    ) {
    }

    public record AdjustLineCommand(
            UUID variantId,
            String direction, // IN | OUT
            BigDecimal quantity,
            String lotCode,
            LocalDate expiryDate
    ) {
    }

    public record AdjustCommand(
            UUID warehouseId,
            List<AdjustLineCommand> lines,
            String reason,
            String note
    ) {
    }

    public record TransferLineCommand(UUID variantId, BigDecimal quantity) {
    }

    public record TransferCommand(
            UUID fromWarehouseId,
            UUID toWarehouseId,
            List<TransferLineCommand> lines,
            String note
    ) {
    }

    /** What the SALES module sends down: variant + quantity (snapshot data). */
    public record SaleStockLine(UUID variantId, BigDecimal quantity) {
    }

    // ---- Views ----

    public record WarehouseView(UUID id, String code, String name, boolean active, String createdAt) {
    }

    public record LotView(String lotCode, String expiryDate, String quantity) {
    }

    public record BalanceView(
            UUID variantId,
            String sku,
            String productName,
            String unit,
            String quantity,
            List<LotView> lots
    ) {
    }

    public record MovementView(
            UUID id,
            UUID warehouseId,
            String warehouseCode,
            String direction,
            String cause,
            UUID variantId,
            String sku,
            String quantity,
            String lotCode,
            String expiryDate,
            String reference,
            String note,
            String relatedMovementId,
            String createdAt
    ) {
    }

    public record StockOperationResult(List<MovementView> movements, String alert) {
    }

    // ---- Use cases (public API owns the tenant transaction) ----

    @Transactional
    public WarehouseView createWarehouse(UUID tenantId, CreateWarehouseCommand command) {
        return tenantRunner.run(tenantId, () -> {
            if (command.code() == null || command.code().isBlank()) {
                throw new IllegalArgumentException("El código del almacén es requerido");
            }
            if (command.name() == null || command.name().isBlank()) {
                throw new IllegalArgumentException("El nombre del almacén es requerido");
            }
            String code = command.code().trim().toUpperCase(Locale.ROOT);
            if (warehouseRepository.existsByTenantIdAndCodeIgnoreCase(tenantId, code)) {
                throw new ConflictException("WAREHOUSE_DUPLICATE",
                        "El código de almacén " + code + " ya existe en esta organización");
            }
            if (command.branchId() != null
                    // branch integrity is cross-module in v1; null = operating warehouse
                    && command.branchId().toString().isBlank()) {
                throw new IllegalArgumentException("branchId inválido");
            }

            Warehouse warehouse = warehouseRepository.saveAndFlush(
                    new Warehouse(tenantId, command.branchId(), code, command.name().trim()));

            outboxWriter.append(tenantId, "inventory.warehouse.created", "warehouse",
                    warehouse.getId(), Map.of("code", code, "name", warehouse.getName()));

            return new WarehouseView(warehouse.getId(), warehouse.getCode(),
                    warehouse.getName(), warehouse.isActive(),
                    warehouse.getCreatedAt().toString());
        });
    }

    @Transactional(readOnly = true)
    public List<WarehouseView> listWarehouses(UUID tenantId) {
        return tenantRunner.run(tenantId, () ->
                warehouseRepository.findByTenantIdOrderById(tenantId).stream()
                        .map(w -> new WarehouseView(w.getId(), w.getCode(), w.getName(),
                                w.isActive(), w.getCreatedAt().toString()))
                        .toList());
    }

    /** Aggregate balances (+ lots when the vertical tracks them) of one warehouse. */
    @Transactional(readOnly = true)
    public List<BalanceView> listBalances(UUID tenantId, UUID warehouseId) {
        return tenantRunner.run(tenantId, () -> {
            Warehouse warehouse = requireWarehouse(tenantId, warehouseId);
            VerticalProfile.Profile profile = profileOf(tenantId);
            boolean tracksLots = profile.tracks(VerticalProfile.Dimension.LOT);

            Map<UUID, List<LotView>> lotsByVariant = new LinkedHashMap<>();
            if (tracksLots) {
                jdbc.query("""
                        SELECT variant_id, lot_code, expiry_date, quantity
                        FROM inventory_lot
                        WHERE tenant_id = ? AND warehouse_id = ? AND quantity <> 0
                        ORDER BY variant_id, expiry_date ASC NULLS LAST, lot_code
                        """,
                        rs -> {
                            UUID variantId = rs.getObject("variant_id", UUID.class);
                            LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
                            lotsByVariant
                                    .computeIfAbsent(variantId, key -> new ArrayList<>())
                                    .add(new LotView(
                                            rs.getString("lot_code"),
                                            expiry == null ? null : expiry.toString(),
                                            rs.getBigDecimal("quantity").toPlainString()));
                        },
                        tenantId, warehouse.getId());
            }

            List<BalanceView> views = new ArrayList<>();
            for (InventoryBalance balance : balanceRepository
                    .findByTenantIdAndWarehouseIdOrderByVariantId(tenantId, warehouse.getId())) {
                ProductVariant variant = variantRepository.findById(balance.getVariantId())
                        .filter(v -> v.getTenantId().equals(tenantId))
                        .orElse(null);
                if (variant == null) {
                    continue; // unreachable: composite FK guarantees same tenant
                }
                views.add(new BalanceView(
                        variant.getId(), variant.getSku(),
                        variant.getProduct().getName(), variant.getUnit(),
                        balance.getQuantity().toPlainString(),
                        lotsByVariant.getOrDefault(variant.getId(), List.of())));
            }
            return views;
        });
    }

    @Transactional
    public StockOperationResult receive(UUID tenantId, ReceiveCommand command) {
        return tenantRunner.run(tenantId, () -> {
            if (command.warehouseId() == null) {
                throw new IllegalArgumentException("El almacén es requerido");
            }
            if (command.lines() == null || command.lines().isEmpty()) {
                throw new IllegalArgumentException("La recepción requiere al menos una línea");
            }
            Warehouse warehouse = requireWarehouse(tenantId, command.warehouseId());
            VerticalProfile.Profile profile = profileOf(tenantId);

            List<MovementView> views = new ArrayList<>();
            for (StockLineCommand line : command.lines()) {
                ProductVariant variant = requireVariant(tenantId, line.variantId());
                BigDecimal quantity = requirePositiveQuantity(line.quantity());
                requireLotRulesOnIn(profile, line.lotCode(), line.expiryDate());

                InventoryMovement movement = movementRepository.saveAndFlush(new InventoryMovement(
                        tenantId, warehouse.getId(), variant.getId(),
                        InventoryMovement.Direction.IN, InventoryMovement.Cause.PURCHASE,
                        quantity, blankToNull(line.lotCode()), line.expiryDate(),
                        blankToNull(command.reference()), blankToNull(command.note()),
                        null));

                if (line.lotCode() != null) {
                    upsertLot(tenantId, warehouse.getId(), variant.getId(),
                            line.lotCode().trim(), line.expiryDate(), quantity);
                }
                applyBalanceDelta(tenantId, warehouse.getId(), variant.getId(), quantity);

                views.add(toView(movement, warehouse.getCode(), variant));
            }

            outboxWriter.append(tenantId, "inventory.stock.received", "warehouse",
                    warehouse.getId(), Map.of("lines", views.size()));

            return new StockOperationResult(views, null);
        });
    }

    /** Manual correction: ADJUST IN/OUT rows (corrections are new movements). */
    @Transactional
    public StockOperationResult adjust(UUID tenantId, AdjustCommand command) {
        return tenantRunner.run(tenantId, () -> {
            if (command.warehouseId() == null) {
                throw new IllegalArgumentException("El almacén es requerido");
            }
            if (command.reason() == null || command.reason().isBlank()) {
                throw new IllegalArgumentException("El motivo del ajuste es requerido");
            }
            if (command.lines() == null || command.lines().isEmpty()) {
                throw new IllegalArgumentException("El ajuste requiere al menos una línea");
            }
            Warehouse warehouse = requireWarehouse(tenantId, command.warehouseId());
            VerticalProfile.Profile profile = profileOf(tenantId);

            List<MovementView> views = new ArrayList<>();
            for (AdjustLineCommand line : command.lines()) {
                ProductVariant variant = requireVariant(tenantId, line.variantId());
                BigDecimal quantity = requirePositiveQuantity(line.quantity());
                InventoryMovement.Direction direction = parseDirection(line.direction());

                if (direction == InventoryMovement.Direction.OUT) {
                    List<InventoryMovement> outs = moveOut(tenantId, warehouse, variant, quantity,
                            InventoryMovement.Cause.ADJUST, null,
                            "Ajuste: " + command.reason().trim(), false);
                    for (InventoryMovement out : outs) {
                        views.add(toView(out, warehouse.getCode(), variant));
                    }
                } else {
                    requireLotRulesOnIn(profile, line.lotCode(), line.expiryDate());
                    InventoryMovement movement = movementRepository.saveAndFlush(new InventoryMovement(
                            tenantId, warehouse.getId(), variant.getId(),
                            InventoryMovement.Direction.IN, InventoryMovement.Cause.ADJUST,
                            quantity, blankToNull(line.lotCode()), line.expiryDate(),
                            null, "Ajuste: " + command.reason().trim(), null));
                    if (line.lotCode() != null) {
                        upsertLot(tenantId, warehouse.getId(), variant.getId(),
                                line.lotCode().trim(), line.expiryDate(), quantity);
                    }
                    applyBalanceDelta(tenantId, warehouse.getId(), variant.getId(), quantity);
                    views.add(toView(movement, warehouse.getCode(), variant));
                }
            }

            outboxWriter.append(tenantId, "inventory.stock.adjusted", "warehouse",
                    warehouse.getId(), Map.of("lines", views.size()));

            return new StockOperationResult(views, null);
        });
    }

    /** Mirror-pair transfer (ADR-004 rule 4); physically impossible to go negative. */
    @Transactional
    public StockOperationResult transfer(UUID tenantId, TransferCommand command) {
        return tenantRunner.run(tenantId, () -> {
            if (command.fromWarehouseId() == null || command.toWarehouseId() == null) {
                throw new IllegalArgumentException("Se requieren almacén de origen y destino");
            }
            if (command.fromWarehouseId().equals(command.toWarehouseId())) {
                throw new IllegalArgumentException("El origen y el destino deben ser distintos");
            }
            if (command.lines() == null || command.lines().isEmpty()) {
                throw new IllegalArgumentException("La transferencia requiere al menos una línea");
            }
            Warehouse from = requireWarehouse(tenantId, command.fromWarehouseId());
            Warehouse to = requireWarehouse(tenantId, command.toWarehouseId());

            List<MovementView> views = new ArrayList<>();
            for (TransferLineCommand line : command.lines()) {
                ProductVariant variant = requireVariant(tenantId, line.variantId());
                BigDecimal quantity = requirePositiveQuantity(line.quantity());

                // OUT first (FEFO from source); despite the tenant policy a
                // TRANSFER may never move stock that is not there.
                List<InventoryMovement> outs = moveOut(tenantId, from, variant, quantity,
                        InventoryMovement.Cause.TRANSFER_OUT, null,
                        blankToNull(command.note()), true);

                for (InventoryMovement out : outs) {
                    InventoryMovement in = movementRepository.saveAndFlush(new InventoryMovement(
                            tenantId, to.getId(), variant.getId(),
                            InventoryMovement.Direction.IN, InventoryMovement.Cause.TRANSFER_IN,
                            out.getQuantity(), out.getLotCode(), out.getExpiryDate(),
                            blankToNull(command.note()), null, out.getId()));
                    if (out.getLotCode() != null) {
                        upsertLot(tenantId, to.getId(), variant.getId(),
                                out.getLotCode(), out.getExpiryDate(), out.getQuantity());
                    }
                    applyBalanceDelta(tenantId, to.getId(), variant.getId(), out.getQuantity());
                    views.add(toView(in, to.getCode(), variant));
                    views.add(toView(out, from.getCode(), variant));
                }
            }

            outboxWriter.append(tenantId, "inventory.stock.transferred", "warehouse",
                    from.getId(), Map.of("to", String.valueOf(to.getId()), "lines", views.size() / 2));

            return new StockOperationResult(views, null);
        });
    }

    @Transactional(readOnly = true)
    public List<MovementView> listMovements(UUID tenantId, UUID warehouseId, UUID variantId) {
        return tenantRunner.run(tenantId, () -> {
            if (warehouseId != null) {
                requireWarehouse(tenantId, warehouseId);
            }
            if (variantId != null) {
                requireVariant(tenantId, variantId);
            }
            List<MovementView> views = new ArrayList<>();
            for (InventoryMovement movement : movementRepository
                    .findRecent(tenantId, warehouseId, variantId)) {
                ProductVariant variant = variantRepository.findById(movement.getVariantId())
                        .orElse(null);
                String warehouseCode = warehouseRepository.findById(movement.getWarehouseId())
                        .map(Warehouse::getCode)
                        .orElse("?");
                views.add(toView(movement, warehouseCode, variant));
            }
            return views;
        });
    }

    // ---- Sale seam (called INSIDE the sales transaction; no runner here) ----

    /**
     * Resolves the warehouse a sale consumes from. Explicit id wins (fail
     * closed 404/409 on foreign/inactive); otherwise the tenant must have
     * exactly zero (no stock control for that sale) or one active warehouse.
     */
    public Optional<UUID> resolveSaleWarehouse(UUID tenantId, UUID requestedId) {
        if (requestedId != null) {
            Warehouse warehouse = requireWarehouse(tenantId, requestedId);
            if (!warehouse.isActive()) {
                throw new ConflictException("WAREHOUSE_INACTIVE",
                        "El almacén seleccionado no está activo");
            }
            return Optional.of(warehouse.getId());
        }
        List<Warehouse> actives = warehouseRepository.findByTenantIdAndActiveTrueOrderById(tenantId);
        if (actives.isEmpty()) {
            return Optional.empty(); // tenant without warehouses: sale is not stock-tracked
        }
        if (actives.size() == 1) {
            return Optional.of(actives.get(0).getId());
        }
        throw new ConflictException("WAREHOUSE_REQUIRED",
                "La organización tiene varios almacenes activos: envía warehouseId en la venta");
    }

    /**
     * Consumes the sale's stock (cause SALE, reference = sale id) FEFO when
     * the vertical tracks lots, enforcing the vertical's negative-stock
     * policy. Runs INSIDE the sale's transaction: a conflict rolls back the
     * whole sale.
     */
    public void consumeForSale(UUID tenantId, UUID warehouseId,
                               List<SaleStockLine> lines, UUID saleId) {
        Warehouse warehouse = requireWarehouse(tenantId, warehouseId);
        VerticalProfile.Profile profile = profileOf(tenantId);
        for (SaleStockLine line : lines) {
            ProductVariant variant = requireVariant(tenantId, line.variantId());
            moveOut(tenantId, warehouse, variant,
                    requirePositiveQuantity(line.quantity()),
                    InventoryMovement.Cause.SALE, saleId.toString(), null, false);
        }
    }

    /**
     * Cancels the stock a sale consumed: for EVERY SALE out movement creates
     * the compensatory SALE_RETURN in (same warehouse, same lot) linked via
     * related_movement_id — limited to the budget NOT yet returned by partial
     * devoluciones (v0.9 · ADR-019), so cancel after partial returns returns
     * exactly the remainder. No-op when the sale was not stock-tracked.
     */
    public void returnForSale(UUID tenantId, UUID saleId) {
        List<InventoryMovement> outs = movementRepository
                .findByTenantIdAndReferenceAndCauseOrderByIdAsc(
                        tenantId, saleId.toString(), InventoryMovement.Cause.SALE);
        if (outs.isEmpty()) {
            return; // sale without stock control (tenant without warehouses)
        }
        Map<UUID, BigDecimal> returnedAgainst = returnedAgainstOuts(tenantId, saleId);
        for (InventoryMovement out : outs) {
            // the compensatory budget: what the movement took minus what
            // partial returns already put back through this exact movement
            BigDecimal budget = out.getQuantity()
                    .subtract(returnedAgainst.getOrDefault(out.getId(), BigDecimal.ZERO));
            if (budget.signum() <= 0) {
                continue;
            }
            movementRepository.saveAndFlush(new InventoryMovement(
                tenantId, out.getWarehouseId(), out.getVariantId(),
                    InventoryMovement.Direction.IN, InventoryMovement.Cause.SALE_RETURN,
                    budget, out.getLotCode(), out.getExpiryDate(),
                    saleId.toString(), null, out.getId()));
            if (out.getLotCode() != null) {
                upsertLot(tenantId, out.getWarehouseId(), out.getVariantId(),
                        out.getLotCode(), out.getExpiryDate(), budget);
            }
            applyBalanceDelta(tenantId, out.getWarehouseId(), out.getVariantId(), budget);
        }
    }

    /**
     * Partial return (v0.9 · ADR-019): returns SPECIFIC quantities of a
     * COMPLETED sale by walking its SALE out movements in order and creating
     * compensatory SALE_RETURN in movements lot by lot (same warehouse, same
     * lot, related_movement_id). Fails closed 409 RETURN_QUANTITY_EXCEEDS when
     * the requested quantity exceeds what is still returnable (sold minus
     * already returned). Runs INSIDE the return's transaction; a sale without
     * stock control (tenant without warehouses) is a no-op, like the cancel.
     */
    public void returnForSalePartial(UUID tenantId, UUID saleId,
                                     List<SaleStockLine> lines) {
        List<InventoryMovement> outs = movementRepository
                .findByTenantIdAndReferenceAndCauseOrderByIdAsc(
                        tenantId, saleId.toString(), InventoryMovement.Cause.SALE);
        if (outs.isEmpty()) {
            return; // sale without stock control: the refund alone is the operation
        }
        Map<UUID, BigDecimal> returnedAgainst = returnedAgainstOuts(tenantId, saleId);

        // budget remaining per out movement + availability per variant
        Map<UUID, BigDecimal> budgetPerOut = new java.util.HashMap<>();
        Map<UUID, BigDecimal> availablePerVariant = new java.util.HashMap<>();
        for (InventoryMovement out : outs) {
            BigDecimal budget = out.getQuantity()
                    .subtract(returnedAgainst.getOrDefault(out.getId(), BigDecimal.ZERO));
            budgetPerOut.put(out.getId(), budget);
            if (budget.signum() > 0) {
                availablePerVariant.merge(out.getVariantId(), budget, BigDecimal::add);
            }
        }

        // fail closed BEFORE writing anything
        for (SaleStockLine line : lines) {
            BigDecimal requested = requirePositiveQuantity(line.quantity());
            BigDecimal available = availablePerVariant.getOrDefault(line.variantId(), BigDecimal.ZERO);
            if (requested.compareTo(available) > 0) {
                ProductVariant variant = requireVariant(tenantId, line.variantId());
                throw new ConflictException("RETURN_QUANTITY_EXCEEDS",
                        "No hay stock pendiente de devolver del SKU " + variant.getSku()
                                + " (solicitaste " + requested.toPlainString()
                                + ", disponible " + available.toPlainString() + ")");
            }
        }

        // allocate lot by lot, in the same order the sale consumed
        for (SaleStockLine line : lines) {
            BigDecimal remaining = requirePositiveQuantity(line.quantity());
            for (InventoryMovement out : outs) {
                if (remaining.signum() <= 0) {
                    break;
                }
                if (!out.getVariantId().equals(line.variantId())) {
                    continue;
                }
                BigDecimal budget = budgetPerOut.getOrDefault(out.getId(), BigDecimal.ZERO);
                if (budget.signum() <= 0) {
                    continue;
                }
                BigDecimal take = remaining.min(budget);
                movementRepository.saveAndFlush(new InventoryMovement(
                        tenantId, out.getWarehouseId(), out.getVariantId(),
                        InventoryMovement.Direction.IN, InventoryMovement.Cause.SALE_RETURN,
                        take, out.getLotCode(), out.getExpiryDate(),
                        saleId.toString(), null, out.getId()));
                if (out.getLotCode() != null) {
                    upsertLot(tenantId, out.getWarehouseId(), out.getVariantId(),
                            out.getLotCode(), out.getExpiryDate(), take);
                }
                applyBalanceDelta(tenantId, out.getWarehouseId(), out.getVariantId(), take);
                budgetPerOut.put(out.getId(), budget.subtract(take));
                remaining = remaining.subtract(take);
            }
        }
    }

    /** Quantity already compensated per SALE out movement (partial returns). */
    private Map<UUID, BigDecimal> returnedAgainstOuts(UUID tenantId, UUID saleId) {
        Map<UUID, BigDecimal> returned = new java.util.HashMap<>();
        for (InventoryMovement ret : movementRepository
                .findByTenantIdAndReferenceAndCauseOrderByIdAsc(
                        tenantId, saleId.toString(), InventoryMovement.Cause.SALE_RETURN)) {
            if (ret.getRelatedMovementId() != null) {
                returned.merge(ret.getRelatedMovementId(), ret.getQuantity(), BigDecimal::add);
            }
        }
        return returned;
    }

    // ---- Internals ----

    /** Core OUT writer: policy check + FEFO lot consumption + movements + balance. */
    private List<InventoryMovement> moveOut(UUID tenantId, Warehouse warehouse,
                                            ProductVariant variant, BigDecimal quantity,
                                            InventoryMovement.Cause cause, String reference,
                                            String note, boolean strictUnavailable) {
        VerticalProfile.NegativeStockPolicy policy = policyOf(tenantId);
        boolean strict = strictUnavailable
                || policy == VerticalProfile.NegativeStockPolicy.FORBIDDEN;

        // Lock the aggregate row for the availability check (row is created
        // on demand; upserts serialise concurrent writers).
        BigDecimal current = currentQuantity(tenantId, warehouse.getId(), variant.getId());
        if (strict && current.subtract(quantity).signum() < 0) {
            throw new InsufficientStockException(variant.getSku(), current, quantity);
        }

        List<InventoryMovement> movements = new ArrayList<>();
        if (profileOf(tenantId).tracks(VerticalProfile.Dimension.LOT)) {
            BigDecimal remaining = quantity;
            List<LotRow> lots = fefoLotsForUpdate(tenantId, warehouse.getId(), variant.getId());
            for (LotRow lot : lots) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal take = lot.quantity().min(remaining);
                jdbc.update("UPDATE inventory_lot SET quantity = quantity - ?, updated_at = now() WHERE id = ?",
                        take, lot.id());
                movements.add(movementRepository.saveAndFlush(new InventoryMovement(
                        tenantId, warehouse.getId(), variant.getId(),
                        InventoryMovement.Direction.OUT, cause,
                        take, lot.lotCode(), lot.expiryDate(), reference, note, null)));
                remaining = remaining.subtract(take);
            }
            if (remaining.signum() > 0) {
                // not enough stock in lots: the remainder moves WITHOUT lot
                // (only reachable with a permissive policy or empty stock)
                movements.add(movementRepository.saveAndFlush(new InventoryMovement(
                        tenantId, warehouse.getId(), variant.getId(),
                        InventoryMovement.Direction.OUT, cause,
                        remaining, null, null, reference, note, null)));
            }
        } else {
            movements.add(movementRepository.save(new InventoryMovement(
                    tenantId, warehouse.getId(), variant.getId(),
                    InventoryMovement.Direction.OUT, cause,
                    quantity, null, null, reference, note, null)));
        }

        applyBalanceDelta(tenantId, warehouse.getId(), variant.getId(), quantity.negate());
        return movements;
    }

    private VerticalProfile.NegativeStockPolicy policyOf(UUID tenantId) {
        return profileOf(tenantId).negativeStockPolicy();
    }

    private VerticalProfile.Profile profileOf(UUID tenantId) {
        Organization organization = organizationRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + tenantId));
        return VerticalProfile.require(organization.getVerticalCode());
    }

    private Warehouse requireWarehouse(UUID tenantId, UUID warehouseId) {
        if (warehouseId == null) {
            throw new IllegalArgumentException("El almacén es requerido");
        }
        return warehouseRepository.findByTenantIdAndId(tenantId, warehouseId)
                .orElseThrow(() -> new NotFoundException("warehouse", warehouseId.toString()));
    }

    private ProductVariant requireVariant(UUID tenantId, UUID variantId) {
        if (variantId == null) {
            throw new IllegalArgumentException("La variante es requerida");
        }
        return variantRepository.findById(variantId)
                .filter(v -> v.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NotFoundException("variant", variantId.toString()));
    }

    /** Verticals tracking LOT/EXPIRY (ADR-010) require them on every stock IN. */
    private static void requireLotRulesOnIn(VerticalProfile.Profile profile,
                                            String lotCode, LocalDate expiryDate) {
        boolean tracksLot = profile.tracks(VerticalProfile.Dimension.LOT);
        boolean tracksExpiry = profile.tracks(VerticalProfile.Dimension.EXPIRY);
        if (!tracksLot && (lotCode != null || expiryDate != null)) {
            throw new IllegalArgumentException("El giro " + profile.code()
                    + " no lleva lotes; omite lotCode/expiryDate");
        }
        if (tracksLot && (lotCode == null || lotCode.isBlank())) {
            throw new IllegalArgumentException(
                    "El giro " + profile.code() + " requiere el código de lote (lotCode)");
        }
        if (tracksExpiry && lotCode != null && expiryDate == null) {
            throw new IllegalArgumentException(
                    "El giro " + profile.code() + " requiere la caducidad del lote (expiryDate)");
        }
        if (lotCode != null && !tracksLot) {
            throw new IllegalArgumentException(
                    "El giro " + profile.code() + " no lleva lotes; omite lotCode");
        }
        if (expiryDate != null && !tracksExpiry) {
            throw new IllegalArgumentException(
                    "El giro " + profile.code() + " no lleva caducidad; omite expiryDate");
        }
    }

    private static InventoryMovement.Direction parseDirection(String direction) {
        if (direction == null) {
            throw new IllegalArgumentException("La dirección del ajuste es requerida (IN|OUT)");
        }
        return switch (direction.trim().toUpperCase(Locale.ROOT)) {
            case "IN" -> InventoryMovement.Direction.IN;
            case "OUT" -> InventoryMovement.Direction.OUT;
            default -> throw new IllegalArgumentException("Dirección inválida: " + direction);
        };
    }

    private static BigDecimal requirePositiveQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("La cantidad es requerida");
        }
        BigDecimal scaled = quantity.setScale(4, RoundingMode.HALF_UP);
        if (scaled.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva");
        }
        return scaled;
    }

    private BigDecimal currentQuantity(UUID tenantId, UUID warehouseId, UUID variantId) {
        List<BigDecimal> rows = jdbc.queryForList("""
                SELECT quantity FROM inventory_balance
                WHERE tenant_id = ? AND warehouse_id = ? AND variant_id = ?
                FOR UPDATE
                """, BigDecimal.class, tenantId, warehouseId, variantId);
        return rows.isEmpty() ? BigDecimal.ZERO.setScale(4) : rows.get(0);
    }

    private void applyBalanceDelta(UUID tenantId, UUID warehouseId, UUID variantId,
                                   BigDecimal delta) {
        jdbc.update("""
                INSERT INTO inventory_balance (id, tenant_id, warehouse_id, variant_id, quantity)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, warehouse_id, variant_id)
                DO UPDATE SET quantity = inventory_balance.quantity + EXCLUDED.quantity,
                              updated_at = now()
                """,
                com.posplatform.shared.domain.UuidV7.next(), tenantId, warehouseId,
                variantId, delta);
    }

    private void upsertLot(UUID tenantId, UUID warehouseId, UUID variantId,
                           String lotCode, LocalDate expiryDate, BigDecimal delta) {
        jdbc.update("""
                INSERT INTO inventory_lot (id, tenant_id, warehouse_id, variant_id,
                                           lot_code, expiry_date, quantity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, warehouse_id, variant_id, lot_code)
                DO UPDATE SET quantity = inventory_lot.quantity + EXCLUDED.quantity,
                              updated_at = now()
                """,
                com.posplatform.shared.domain.UuidV7.next(), tenantId, warehouseId,
                variantId, lotCode, expiryDate, delta);
    }

    record LotRow(UUID id, String lotCode, LocalDate expiryDate, BigDecimal quantity) {
    }

    private List<LotRow> fefoLotsForUpdate(UUID tenantId, UUID warehouseId, UUID variantId) {
        return jdbc.query("""
                SELECT id, lot_code, expiry_date, quantity
                FROM inventory_lot
                WHERE tenant_id = ? AND warehouse_id = ? AND variant_id = ? AND quantity > 0
                ORDER BY expiry_date ASC NULLS LAST, lot_code
                FOR UPDATE
                """,
                (rs, i) -> new LotRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("lot_code"),
                        rs.getObject("expiry_date", LocalDate.class),
                        rs.getBigDecimal("quantity")),
                tenantId, warehouseId, variantId);
    }

    private MovementView toView(InventoryMovement movement, String warehouseCode,
                                ProductVariant variant) {
        return new MovementView(
                movement.getId(),
                movement.getWarehouseId(),
                warehouseCode,
                String.valueOf(movement.getDirection()),
                String.valueOf(movement.getCause()),
                movement.getVariantId(),
                variant == null ? null : variant.getSku(),
                movement.getQuantity().toPlainString(),
                movement.getLotCode(),
                movement.getExpiryDate() == null ? null : movement.getExpiryDate().toString(),
                movement.getReference(),
                movement.getNote(),
                movement.getRelatedMovementId() == null
                        ? null : movement.getRelatedMovementId().toString(),
                movement.getCreatedAt().toString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
