package com.posplatform.modules.invoicing.api;

import com.posplatform.modules.invoicing.SatCatalog;
import com.posplatform.modules.invoicing.application.InvoicingService;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.authz.RequiresPermission;
import com.posplatform.shared.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * INVOICING REST surface (fase 6 · ADR-006/016). Fail-closed: the tenant comes
 * ONLY from SessionAuthFilter (X-Tenant-Id verified against a LIVE membership
 * inside RLS); a tenant-less request fails with 404 — never a guess. Issuing
 * does NOT take an idempotency key: the V13 partial unique indexes ARE the
 * guard (one ACTIVE invoice per sale, one ACTIVE global per period) and a
 * duplicate attempt answers 409 with the folio of the existing document.
 */
@RestController
@RequestMapping("/api/v1/invoices")
@RequiresPermission("fiscal.read")
public class InvoicingController {

    private final InvoicingService invoicingService;

    public InvoicingController(InvoicingService invoicingService) {
        this.invoicingService = invoicingService;
    }

    // ---- Requests ----

    public record ReceptorRequest(
            @NotBlank @Size(min = 12, max = 13, message = "RFC de 12-13 caracteres") String rfc,
            @NotBlank @Size(max = 180) String name,
            @NotBlank @Pattern(regexp = "^\\d{3}$", message = "código SAT de régimen (3 dígitos)") String regime,
            @NotBlank @Pattern(regexp = "^[A-Z]\\d{2}$", message = "código SAT de uso CFDI") String use,
            @NotBlank @Pattern(regexp = "^\\d{5}$", message = "CP a 5 dígitos") String zip
    ) {
    }

    public record IssueFromSaleRequest(
            @NotNull UUID saleId,
            UUID legalEntityId,
            @Valid ReceptorRequest receptor,
            List<String> withholdings
    ) {
    }

    public record IssueGlobalRequest(
            @NotNull Integer periodYear,
            @NotNull @Pattern(regexp = "^(0?[1-9]|1[0-2])$", message = "mes 1-12") String periodMonth,
            UUID legalEntityId
    ) {
    }

    public record IssueCreditNoteRequest(@NotNull UUID returnId) {
    }

    public record CancelInvoiceRequest(
            @NotBlank @Pattern(regexp = "^(01|02|03|04)$", message = "motivo SAT: 01/02/03/04") String motive,
            @Size(max = 280) String note
    ) {
    }

    /** Payment complement (fase 11 · ADR-021): amount + SAT forma de pago. */
    public record PaymentRequest(
            @NotNull java.math.BigDecimal amount,
            @NotBlank @Pattern(regexp = "^\\d{2}$", message = "forma de pago SAT: 2 dígitos") String method,
            java.time.Instant paymentDate
    ) {
    }

    public record EmisorRequest(
            /** Optional: the onboard-created razón social starts WITHOUT RFC; it
             *  may be set ONCE while absent (uniqueness per tenant, ADR-009). */
            String taxId,
            @NotBlank @Pattern(regexp = "^[0-9A-Z]{3}$", message = "código SAT de régimen")
            String fiscalRegime,
            @NotBlank @Pattern(regexp = "^\\d{5}$", message = "CP a 5 dígitos") String zipCode
    ) {
    }

    // ---- SAT catalogs (form inputs) ----

    @GetMapping("/sat/catalogs")
    @RequiresPermission({}) // world-readable SAT reference data (no tenant scope)
    public List<InvoicingService.CatalogItem> catalogs(
            @RequestParam String catalog) {
        SatCatalog.Catalog parsed;
        try {
            parsed = SatCatalog.Catalog.valueOf(catalog.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Catálogo SAT no válido: " + catalog
                    + ". Válidos: REGIMEN, USO_CFDI, FORMA_PAGO, METODO_PAGO, "
                    + "MOTIVO_CANCELACION, UNIDAD, PRODUCTO_SERVICIO");
        }
        return invoicingService.catalogs(parsed);
    }

    // ---- Emisor fiscal face ----

    @GetMapping("/emisor")
    public List<InvoicingService.EmisorView> emisor() {
        return invoicingService.emisorList(currentTenant());
    }

    @PutMapping("/emisor/{legalEntityId}")
    @RequiresPermission("fiscal.write")
    public InvoicingService.EmisorView saveEmisor(@PathVariable UUID legalEntityId,
                                                  @Valid @RequestBody EmisorRequest request) {
        return invoicingService.saveEmisor(currentTenant(), legalEntityId,
                request.taxId(), request.fiscalRegime(), request.zipCode());
    }

    // ---- Issue / cancel / read ----

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("fiscal.write")
    public InvoicingService.InvoiceView issueFromSale(@Valid @RequestBody IssueFromSaleRequest request) {
        ReceptorRequest r = request.receptor();
        return invoicingService.issueFromSale(currentTenant(), new InvoicingService.IssueFromSaleCommand(
                request.saleId(),
                request.legalEntityId(),
                r == null ? null
                        : new InvoicingService.ReceptorCommand(r.rfc(), r.name(), r.regime(),
                                r.use(), r.zip()),
                request.withholdings()));
    }

    @PostMapping("/global")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("fiscal.write")
    public InvoicingService.InvoiceView issueGlobal(@Valid @RequestBody IssueGlobalRequest request) {
        int month = Integer.parseInt(request.periodMonth());
        return invoicingService.issueGlobal(currentTenant(), new InvoicingService.IssueGlobalCommand(
                request.periodYear(), month, request.legalEntityId()));
    }

    /**
     * CREDIT_NOTE of one return (fase 9 · ADR-019, CFDI tipo E). No
     * idempotency key: the V16 partial unique index uq_invoice_return_active
     * IS the guard — a duplicate attempt answers 409 with the existing NC.
     */
    @PostMapping("/credit-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("fiscal.write")
    public InvoicingService.InvoiceView issueCreditNote(
            @Valid @RequestBody IssueCreditNoteRequest request) {
        return invoicingService.issueCreditNote(currentTenant(),
                new InvoicingService.IssueCreditNoteCommand(request.returnId()));
    }

    /**
     * PAYMENT complement (fase 11 · ADR-021, CFDI tipo P) against a STAMPED
     * income CFDI. The balance itself is the guard: a repeat exceeding it
     * answers 409 PAG_OVER_BALANCE.
     */
    @PostMapping("/{invoiceId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("fiscal.write")
    public InvoicingService.InvoiceView issuePaymentComplement(
            @PathVariable UUID invoiceId,
            @Valid @RequestBody PaymentRequest request) {
        return invoicingService.issuePaymentComplement(currentTenant(),
                new InvoicingService.IssuePaymentCommand(
                        invoiceId, request.amount(), request.method(),
                        request.paymentDate()));
    }

    @PostMapping("/{invoiceId}/cancel")
    @RequiresPermission("fiscal.write")
    public InvoicingService.InvoiceView cancel(@PathVariable UUID invoiceId,
                                               @Valid @RequestBody CancelInvoiceRequest request) {
        return invoicingService.cancel(currentTenant(), invoiceId, new InvoicingService.CancelCommand(
                request.motive(), request.note()));
    }

    @GetMapping
    public List<InvoicingService.InvoiceView> list() {
        return invoicingService.list(currentTenant());
    }

    @GetMapping("/{invoiceId}")
    public InvoicingService.InvoiceView get(@PathVariable UUID invoiceId) {
        return invoicingService.get(currentTenant(), invoiceId);
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
