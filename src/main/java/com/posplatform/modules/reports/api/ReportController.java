package com.posplatform.modules.reports.api;

import com.posplatform.modules.reports.application.ReportsService;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.authz.RequiresPermission;
import com.posplatform.shared.tenancy.TenantContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * REPORTS REST surface (fase 13 · ADR-023). Read-only: nothing here writes a
 * row, emits an outbox event or recalculates a total — the numbers are the
 * persisted snapshots of sales, returns, payments and stamped invoices.
 *
 * Fail-closed tenant: it comes ONLY from SessionAuthFilter (verified against
 * a LIVE membership inside RLS); a tenant-less call answers 404. Guards use
 * the existing v0.10 catalog — sales/taxes need `sales.read`, the Z corte
 * accepts `sales.read` OR `cash.operate` (a cashier can print the shift
 * without the full sales report).
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportsService reportsService;

    public ReportController(ReportsService reportsService) {
        this.reportsService = reportsService;
    }

    /** Sales summary: totals, day series, payment mix and top products. */
    @GetMapping("/sales")
    @RequiresPermission("sales.read")
    public ReportsService.SalesReport sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportsService.salesReport(currentTenant(), from, to);
    }

    /** NET transfer/withholding breakdown for the period (returns subtract). */
    @GetMapping("/taxes")
    @RequiresPermission("sales.read")
    public ReportsService.TaxesReport taxes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportsService.taxesReport(currentTenant(), from, to);
    }

    /** Corte Z of ONE drawer (open or closed): the shift's own ledger. */
    @GetMapping("/z/{cashSessionId}")
    @RequiresPermission({"sales.read", "cash.operate"})
    public ReportsService.ZReport z(@PathVariable UUID cashSessionId) {
        return reportsService.zReport(currentTenant(), cashSessionId);
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
