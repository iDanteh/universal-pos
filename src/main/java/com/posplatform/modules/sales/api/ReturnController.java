package com.posplatform.modules.sales.api;

import com.posplatform.modules.sales.application.ReturnsService;
import com.posplatform.modules.sales.application.ReturnsService.CreateReturnCommand;
import com.posplatform.modules.sales.application.ReturnsService.ReturnLineCommand;
import com.posplatform.modules.sales.application.ReturnsService.ReturnOutcome;
import com.posplatform.modules.sales.application.ReturnsService.ReturnView;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
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
import java.util.UUID;

/**
 * RETURNS REST surface (fase 9 · ADR-019). Same fail-closed rules as the
 * sales surface: the tenant comes ONLY from SessionAuthFilter; POST requires
 * an Idempotency-Key (a retry returns the SAME return, 200, instead of
 * duplicating money leaving the drawer).
 */
@RestController
@RequestMapping("/api/v1/returns")
public class ReturnController {

    private final ReturnsService returnsService;

    public ReturnController(ReturnsService returnsService) {
        this.returnsService = returnsService;
    }

    // ---- Requests ----

    public record ReturnLineRequest(
            UUID saleLineId,
            java.math.BigDecimal quantity
    ) {
    }

    public record CreateReturnRequest(
            List<ReturnLineRequest> lines,
            String refundMethod,
            String refundReference,
            String note
    ) {
    }

    // ---- Use cases ----

    @PostMapping("/{saleId}")
    public ResponseEntity<ReturnView> create(
            @PathVariable UUID saleId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateReturnRequest request) {
        UUID tenantId = currentTenant();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("El header Idempotency-Key es requerido");
        }
        if (idempotencyKey.length() > 120) {
            throw new IllegalArgumentException("Idempotency-Key demasiado larga (máx 120)");
        }
        List<ReturnLineCommand> lines = request.lines() == null ? List.of()
                : request.lines().stream()
                        .map(item -> new ReturnLineCommand(item.saleLineId(), item.quantity()))
                        .toList();

        ReturnOutcome outcome = returnsService.create(
                tenantId, saleId, idempotencyKey.trim(),
                new CreateReturnCommand(lines, request.refundMethod(),
                        request.refundReference(), request.note()));
        return ResponseEntity
                .status(outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.returnView());
    }

    @GetMapping
    public List<ReturnView> list() {
        return returnsService.list(currentTenant());
    }

    @GetMapping("/{saleId}")
    public List<ReturnView> listBySale(@PathVariable UUID saleId) {
        return returnsService.listBySale(currentTenant(), saleId);
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
