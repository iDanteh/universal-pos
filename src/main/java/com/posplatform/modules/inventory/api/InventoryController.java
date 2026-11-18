package com.posplatform.modules.inventory.api;

import com.posplatform.modules.inventory.application.InventoryService;
import com.posplatform.modules.inventory.application.InventoryService.AdjustCommand;
import com.posplatform.modules.inventory.application.InventoryService.AdjustLineCommand;
import com.posplatform.modules.inventory.application.InventoryService.BalanceView;
import com.posplatform.modules.inventory.application.InventoryService.CreateWarehouseCommand;
import com.posplatform.modules.inventory.application.InventoryService.MovementView;
import com.posplatform.modules.inventory.application.InventoryService.ReceiveCommand;
import com.posplatform.modules.inventory.application.InventoryService.StockLineCommand;
import com.posplatform.modules.inventory.application.InventoryService.StockOperationResult;
import com.posplatform.modules.inventory.application.InventoryService.TransferCommand;
import com.posplatform.modules.inventory.application.InventoryService.TransferLineCommand;
import com.posplatform.modules.inventory.application.InventoryService.WarehouseView;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.authz.RequiresPermission;
import com.posplatform.shared.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * INVENTORY REST surface (fase 5 · ADR-004). Fail-closed like every module:
 * the tenant comes ONLY from SessionAuthFilter (X-Tenant-Id is a routing
 * hint verified against a LIVE membership inside RLS); a tenant-less request
 * fails with 404. Movements are immutable here as in the service: there is
 * no UPDATE/DELETE endpoint by design.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@RequiresPermission("inventory.read")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    // ---- Requests ----

    public record WarehouseRequest(String code, String name) {
    }

    public record StockLineRequest(
            UUID variantId,
            BigDecimal quantity,
            String lotCode,
            LocalDate expiryDate
    ) {
    }

    public record ReceiveRequest(
            UUID warehouseId,
            List<StockLineRequest> lines,
            String reference,
            String note
    ) {
    }

    public record AdjustLineRequest(
            UUID variantId,
            String direction,
            BigDecimal quantity,
            String lotCode,
            LocalDate expiryDate
    ) {
    }

    public record AdjustRequest(
            UUID warehouseId,
            List<AdjustLineRequest> lines,
            String reason,
            String note
    ) {
    }

    public record TransferLineRequest(UUID variantId, BigDecimal quantity) {
    }

    public record TransferRequest(
            UUID fromWarehouseId,
            UUID toWarehouseId,
            List<TransferLineRequest> lines,
            String note
    ) {
    }

    // ---- Warehouses ----

    @PostMapping("/warehouses")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("inventory.write")
    public WarehouseView createWarehouse(@RequestBody WarehouseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("El cuerpo del almacén es requerido");
        }
        return inventoryService.createWarehouse(currentTenant(),
                new CreateWarehouseCommand(request.code(), request.name(), null));
    }

    @GetMapping("/warehouses")
    public List<WarehouseView> listWarehouses() {
        return inventoryService.listWarehouses(currentTenant());
    }

    // ---- Balances / movements ----

    @GetMapping("/balances")
    public List<BalanceView> listBalances(@RequestParam(required = false) UUID warehouseId) {
        UUID tenantId = currentTenant();
        if (warehouseId == null) {
            throw new IllegalArgumentException("El parámetro warehouseId es requerido");
        }
        return inventoryService.listBalances(tenantId, warehouseId);
    }

    @GetMapping("/movements")
    public List<MovementView> listMovements(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID variantId) {
        return inventoryService.listMovements(currentTenant(), warehouseId, variantId);
    }

    // ---- Stock operations ----

    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("inventory.write")
    public StockOperationResult receive(@RequestBody ReceiveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("El cuerpo de la recepción es requerido");
        }
        List<StockLineCommand> lines = request.lines() == null ? List.of()
                : request.lines().stream()
                        .map(line -> new StockLineCommand(
                                line.variantId(), line.quantity(), line.lotCode(), line.expiryDate()))
                        .toList();
        return inventoryService.receive(currentTenant(),
                new ReceiveCommand(request.warehouseId(), lines,
                        request.reference(), request.note()));
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("inventory.write")
    public StockOperationResult adjust(@RequestBody AdjustRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("El cuerpo del ajuste es requerido");
        }
        List<AdjustLineCommand> lines = request.lines() == null ? List.of()
                : request.lines().stream()
                        .map(line -> new AdjustLineCommand(
                                line.variantId(), line.direction(), line.quantity(),
                                line.lotCode(), line.expiryDate()))
                        .toList();
        return inventoryService.adjust(currentTenant(),
                new AdjustCommand(request.warehouseId(), lines,
                        request.reason(), request.note()));
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("inventory.write")
    public StockOperationResult transfer(@RequestBody TransferRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("El cuerpo de la transferencia es requerido");
        }
        List<TransferLineCommand> lines = request.lines() == null ? List.of()
                : request.lines().stream()
                        .map(line -> new TransferLineCommand(line.variantId(), line.quantity()))
                        .toList();
        return inventoryService.transfer(currentTenant(),
                new TransferCommand(request.fromWarehouseId(), request.toWarehouseId(),
                        lines, request.note()));
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
