package com.posplatform.modules.catalog.api;

import com.posplatform.modules.catalog.application.CatalogService;
import com.posplatform.modules.catalog.application.CatalogService.AddVariantCommand;
import com.posplatform.modules.catalog.application.CatalogService.CreateProductCommand;
import com.posplatform.modules.catalog.application.CatalogService.UnitsView;
import com.posplatform.modules.catalog.application.CatalogService.VariantCommand;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.authz.RequiresPermission;
import com.posplatform.shared.tenancy.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CATALOG REST surface (fase 3 · ADR-004 + ADR-010). Fail-closed: the tenant
 * comes ONLY from SessionAuthFilter (X-Tenant-Id verified against a LIVE
 * membership); a missing/unverified tenant fails with 404 — never a guess.
 * Money is NUMERIC(14,4) serialized as string end to end.
 */
@RestController
@RequestMapping("/api/v1/catalog")
@RequiresPermission("catalog.read")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // ---- Requests ----

    public record ProductRequest(
            String name,
            String description,
            String category,
            String baseUnit,
            List<VariantItem> variants
    ) {
        public record VariantItem(
                String sku,
                String barcode,
                String unit,
                java.util.Map<String, String> attributes,
                java.math.BigDecimal price,
                String taxRateCode
        ) {
        }
    }

    public record VariantRequest(
            String sku,
            String barcode,
            String unit,
            java.util.Map<String, String> attributes,
            java.math.BigDecimal price,
            String taxRateCode
    ) {
    }

    // ---- Units / form parameterization ----

    /** Units + declared product attributes of the tenant's CURRENT vertical. */
    @GetMapping("/units")
    public UnitsView units() {
        return catalogService.units(currentTenant());
    }

    /** Active transferred-tax rates (global catalog, v0.8 · ADR-018). */
    @GetMapping("/taxes")
    public List<CatalogService.TaxRateView> taxes() {
        return catalogService.taxes(currentTenant());
    }

    // ---- Products ----

    @GetMapping("/products")
    public List<CatalogService.ProductView> products() {
        return catalogService.listProducts(currentTenant());
    }

    @PostMapping("/products")
    @RequiresPermission("catalog.write")
    public ResponseEntity<CatalogService.ProductView> createProduct(
            @Valid @RequestBody ProductRequest request) {
        UUID tenantId = currentTenant();
        List<VariantCommand> variants = request.variants() == null ? null
                : request.variants().stream()
                        .map(item -> new VariantCommand(
                                item.sku(), item.barcode(), item.unit(),
                                item.attributes(), item.price(), item.taxRateCode()))
                        .toList();
        CatalogService.ProductView created = catalogService.createProduct(tenantId, new CreateProductCommand(
                request.name(), request.description(), request.category(),
                request.baseUnit(), variants));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ---- Variants ----

    @PostMapping("/products/{productId}/variants")
    @RequiresPermission("catalog.write")
    public ResponseEntity<CatalogService.VariantView> addVariant(
            @PathVariable UUID productId,
            @Valid @RequestBody VariantRequest request) {
        UUID tenantId = currentTenant();
        CatalogService.VariantView created = catalogService.addVariant(tenantId, productId,
                new AddVariantCommand(
                        request.sku(), request.barcode(), request.unit(),
                        request.attributes(), request.price(), request.taxRateCode()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
