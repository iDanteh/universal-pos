package com.posplatform.modules.sales.api;

import com.posplatform.modules.sales.application.SalesService;
import com.posplatform.modules.sales.application.SalesService.CreateSaleCommand;
import com.posplatform.modules.sales.application.SalesService.SaleLineCommand;
import com.posplatform.modules.sales.application.SalesService.SaleOutcome;
import com.posplatform.modules.sales.application.SalesService.SalePaymentCommand;
import com.posplatform.modules.sales.application.SalesService.SaleView;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SALES REST surface (fase 4 · sales-payments.md). Fail-closed: the tenant
 * comes ONLY from SessionAuthFilter (X-Tenant-Id verified against a LIVE
 * membership inside RLS); a tenant-less request fails with 404 — never a
 * guess. POST /sales REQUIRES an Idempotency-Key header: retries return the
 * same sale (200) instead of duplicating it.
 */
@RestController
@RequestMapping("/api/v1/sales")
public class SaleController {

    private final SalesService salesService;

    public SaleController(SalesService salesService) {
        this.salesService = salesService;
    }

    // ---- Requests ----

    public record SaleLineRequest(
            UUID variantId,
            java.math.BigDecimal quantity,
            java.math.BigDecimal unitPrice,
            java.math.BigDecimal discount
    ) {
    }

    public record SalePaymentRequest(
            String method,
            java.math.BigDecimal amount,
            String reference
    ) {
    }

    public record CreateSaleRequest(
            List<SaleLineRequest> lines,
            List<SalePaymentRequest> payments,
            String note,
            UUID warehouseId
    ) {
    }

    public record CancelSaleRequest(@NotBlank @Size(max = 280) String reason) {
    }

    // ---- Use cases ----

    @PostMapping
    public ResponseEntity<SaleView> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSaleRequest request) {
        UUID tenantId = currentTenant();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("El header Idempotency-Key es requerido");
        }
        if (idempotencyKey.length() > 120) {
            throw new IllegalArgumentException("Idempotency-Key demasiado larga (máx 120)");
        }
        List<SaleLineCommand> lines = request.lines() == null ? List.of()
                : request.lines().stream()
                        .map(item -> new SaleLineCommand(
                                item.variantId(), item.quantity(),
                                item.unitPrice(), item.discount()))
                        .toList();
        List<SalePaymentCommand> payments = request.payments() == null ? List.of()
                : request.payments().stream()
                        .map(item -> new SalePaymentCommand(
                                item.method(), item.amount(), item.reference()))
                        .toList();

        SaleOutcome outcome = salesService.create(
                tenantId, idempotencyKey.trim(),
                new CreateSaleCommand(lines, payments, request.note(), request.warehouseId()));
        return ResponseEntity
                .status(outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.sale());
    }

    @PostMapping("/{saleId}/cancel")
    public SaleView cancel(@PathVariable UUID saleId,
                           @Valid @RequestBody CancelSaleRequest request) {
        return salesService.cancel(currentTenant(), saleId, request.reason());
    }

    @GetMapping
    public List<SaleView> list() {
        return salesService.list(currentTenant());
    }

    @GetMapping("/{saleId}")
    public SaleView get(@PathVariable UUID saleId) {
        return salesService.get(currentTenant(), saleId);
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
