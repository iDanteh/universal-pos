package com.posplatform.modules.cash.api;

import com.posplatform.config.SessionAuthFilter;
import com.posplatform.modules.cash.application.CashService;
import com.posplatform.modules.cash.application.CashService.CloseCounts;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.authz.RequiresPermission;
import com.posplatform.shared.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CASH REST surface (fase 7 · ADR-017). Fail-closed: the tenant comes ONLY
 * from SessionAuthFilter (X-Tenant-Id verified against a LIVE membership
 * inside RLS); a tenant-less request fails with 404 — never a guess. The
 * drawer identity for open/close arrives verifiable via the session cookie.
 * GET /current answers 404 WITHOUT a body when the tenant has no open
 * drawer — that is a NORMAL state (the admin opens the first shift when the
 * register physically starts, not at onboarding).
 */
@RestController
@RequestMapping("/api/v1/cash/sessions")
@RequiresPermission("cash.operate")
public class CashController {

    private final CashService cashService;

    public CashController(CashService cashService) {
        this.cashService = cashService;
    }

    // ---- Requests ----

    public record OpenRequest(
            /** Fondo inicial del cajón: optional, ZERO for an empty drawer. */
            BigDecimal openingFloat
    ) {
    }

    public record CloseRequest(
            @NotNull(message = "El conteo de efectivo es requerido") BigDecimal countedCash,
            BigDecimal countedCard,
            BigDecimal countedTransfer,
            BigDecimal countedOther,
            @Size(max = 280, message = "La nota no puede exceder 280 caracteres") String note
    ) {
    }

    // ---- Lifecycle ----

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CashService.CashSessionView open(
            @RequestAttribute(SessionAuthFilter.REQUEST_USER_ID) UUID userId,
            @Valid @RequestBody(required = false) OpenRequest request) {
        BigDecimal openingFloat = request == null ? null : request.openingFloat();
        return cashService.open(currentTenant(), userId, openingFloat);
    }

    @PostMapping("/{sessionId}/close")
    public CashService.CashSessionView close(
            @PathVariable UUID sessionId,
            @Valid @RequestBody CloseRequest request) {
        return cashService.close(currentTenant(), sessionId,
                new CloseCounts(
                        request.countedCash(), request.countedCard(),
                        request.countedTransfer(), request.countedOther()),
                request.note());
    }

    // ---- Read ----

    @GetMapping("/current")
    public ResponseEntity<CashService.CashSessionView> current() {
        return cashService.currentOpen(currentTenant())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<CashService.CashSessionView> list() {
        return cashService.list(currentTenant());
    }

    @GetMapping("/{sessionId}")
    public CashService.CashSessionView get(@PathVariable UUID sessionId) {
        return cashService.get(currentTenant(), sessionId);
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
