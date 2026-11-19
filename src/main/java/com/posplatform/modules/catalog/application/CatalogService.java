package com.posplatform.modules.catalog.application;

import com.posplatform.modules.catalog.DuplicateSkuException;
import com.posplatform.modules.catalog.Product;
import com.posplatform.modules.catalog.ProductVariant;
import com.posplatform.modules.catalog.infrastructure.ProductRepository;
import com.posplatform.modules.catalog.infrastructure.ProductVariantRepository;
import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.VerticalProfile;
import com.posplatform.modules.organization.infrastructure.OrganizationRepository;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.outbox.OutboxWriter;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CATALOG use case (fase 3 · ADR-004 + ADR-010): create products/variants and
 * list the tenant catalog, with the vertical's declarative parameterization
 * as the ONLY rule source:
 *
 *  - units must belong to the tenant's vertical profile (allowedUnits);
 *  - variant attributes may only use keys the vertical declares; attributes
 *    flagged required=true must be present and non-blank;
 *  - SKU is unique per tenant (service check + V10 unique index);
 *  - price is NUMERIC(14,4) -> BigDecimal (money rule), serialized as string.
 *
 * All writes run inside the tenant-bound transaction (RLS scope). The catalog
 * core created here is what inventory movements consume later (ADR-004):
 * balances are never hand-maintained stock in the catalog.
 */
@Service
@Validated
public class CatalogService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final OrganizationRepository organizationRepository;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;
    private final JdbcTemplate jdbc;

    public CatalogService(ProductRepository productRepository,
                          ProductVariantRepository variantRepository,
                          OrganizationRepository organizationRepository,
                          OutboxWriter outboxWriter,
                          TenantTransactionRunner tenantRunner,
                          JdbcTemplate jdbc) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.organizationRepository = organizationRepository;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
        this.jdbc = jdbc;
    }

    // ---- Commands ----

    public record VariantCommand(
            @NotBlank @Size(max = 64) String sku,
            @Size(max = 64) String barcode,
            @NotBlank @Size(max = 8) String unit,
            Map<String, String> attributes,
            BigDecimal price,
            String taxRateCode,
            String iepsRateCode
    ) {
    }

    public record CreateProductCommand(
            @NotBlank @Size(max = 180) String name,
            @Size(max = 500) String description,
            @Size(max = 120) String category,
            @NotBlank @Size(max = 8) String baseUnit,
            @Valid List<VariantCommand> variants
    ) {
    }

    public record AddVariantCommand(
            @NotBlank @Size(max = 64) String sku,
            @Size(max = 64) String barcode,
            @NotBlank @Size(max = 8) String unit,
            Map<String, String> attributes,
            BigDecimal price,
            String taxRateCode,
            String iepsRateCode
    ) {
    }

    // ---- Views ----

    /** Declarative parameterization of the tenant's CURRENT vertical for the catalog form. */
    public record UnitsView(
            String verticalCode,
            String verticalName,
            String baseUnit,
            List<String> allowedUnits,
            List<AttributeView> productAttributes,
            List<String> trackedDimensions
    ) {
    }

    public record AttributeView(String code, String displayName, boolean required) {
    }

    /** Active rate of the GLOBAL tax_rate catalog (V15): the form's dropdown. */
    public record TaxRateView(
            String code,
            String scheme,
            String factor,
            String rate,
            String description
    ) {
    }

    /** Federal retention rate (V19 · ADR-021): the invoice form's dropdown. */
    public record WithholdingRateView(
            String code,
            String scheme,
            String rate,
            String description
    ) {
    }

    public record VariantView(
            UUID id,
            String sku,
            String barcode,
            String unit,
            Map<String, String> attributes,
            String price,
            String taxRateCode,
            String iepsRateCode,
            String status
    ) {
        static VariantView of(ProductVariant variant) {
            return new VariantView(
                    variant.getId(),
                    variant.getSku(),
                    variant.getBarcode(),
                    variant.getUnit(),
                    variant.getAttributes(),
                    variant.getPrice() == null ? null : variant.getPrice().toPlainString(),
                    variant.getTaxRateCode(),
                    variant.getIepsRateCode(),
                    String.valueOf(variant.getStatus()));
        }
    }

    public record ProductView(
            UUID id,
            String name,
            String description,
            String category,
            String baseUnit,
            String status,
            List<VariantView> variants
    ) {
    }

    // ---- Use cases ----

    /** Product + optional first variants in ONE tenant transaction (AK: deferred). */
    @Transactional
    public ProductView createProduct(UUID tenantId, @Valid CreateProductCommand command) {
        return tenantRunner.run(tenantId, () -> {
            VerticalProfile.Profile profile = profileOf(tenantId);
            requireUnit(profile, command.baseUnit());

            Product product = productRepository.save(new Product(
                    tenantId,
                    command.name().trim(),
                    blankToNull(command.description()),
                    blankToNull(command.category()),
                    command.baseUnit()));

            List<VariantView> variantViews = new ArrayList<>();
            if (command.variants() != null) {
                for (VariantCommand variantCommand : command.variants()) {
                    ProductVariant variant = saveVariant(tenantId, product, profile, variantCommand);
                    variantViews.add(VariantView.of(variant));
                }
            }

            outboxWriter.append(
                    tenantId, "catalog.product.created", "product", product.getId(),
                    Map.of("name", product.getName(),
                            "baseUnit", product.getBaseUnit(),
                            "variants", variantViews.size()));

            return new ProductView(
                    product.getId(),
                    product.getName(),
                    product.getDescription(),
                    product.getCategory(),
                    product.getBaseUnit(),
                    String.valueOf(product.getStatus()),
                    variantViews);
        });
    }

    /** Add a variant (SKU item) under an EXISTING product of the tenant. */
    @Transactional
    public VariantView addVariant(UUID tenantId, UUID productId, @Valid AddVariantCommand command) {
        return tenantRunner.run(tenantId, () -> {
            Product product = productRepository.findById(productId)
                    .filter(p -> p.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new NotFoundException("product", productId.toString()));
            VerticalProfile.Profile profile = profileOf(tenantId);

            ProductVariant variant = saveVariant(tenantId, product, profile,
                    new VariantCommand(
                            command.sku(), command.barcode(), command.unit(),
                            command.attributes(), command.price(), command.taxRateCode(),
                            command.iepsRateCode()));

            outboxWriter.append(
                    tenantId, "catalog.variant.added", "product_variant", variant.getId(),
                    Map.of("sku", variant.getSku(), "productId", productId.toString()));

            return VariantView.of(variant);
        });
    }

    /** The tenant catalog (products + variants), read inside the RLS scope. */
    @Transactional(readOnly = true)
    public List<ProductView> listProducts(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> {
            Map<UUID, List<ProductVariant>> variantsByProduct = new LinkedHashMap<>();
            for (ProductVariant variant : variantRepository.findByTenantId(tenantId)) {
                variantsByProduct
                        .computeIfAbsent(variant.getProductId(), key -> new ArrayList<>())
                        .add(variant);
            }

            List<ProductView> views = new ArrayList<>();
            for (Product product : productRepository.findByTenantIdOrderById(tenantId)) {
                List<VariantView> variants = variantsByProduct
                        .getOrDefault(product.getId(), List.of())
                        .stream()
                        .map(VariantView::of)
                        .toList();
                views.add(new ProductView(
                        product.getId(),
                        product.getName(),
                        product.getDescription(),
                        product.getCategory(),
                        product.getBaseUnit(),
                        String.valueOf(product.getStatus()),
                        variants));
            }
            return views;
        });
    }

    /** Units + product attributes the CURRENT vertical allows (declarative, ADR-010). */
    @Transactional(readOnly = true)
    public UnitsView units(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> {
            VerticalProfile.Profile profile = profileOf(tenantId);
            return new UnitsView(
                    profile.code(),
                    profile.displayName(),
                    profile.baseUnit(),
                    profile.allowedUnits().stream().sorted().toList(),
                    profile.productAttributes().stream()
                            .map(def -> new AttributeView(def.code(), def.displayName(), def.required()))
                            .toList(),
                    profile.trackedDimensions().stream()
                            .map(String::valueOf)
                            .sorted()
                            .toList());
        });
    }

    /** Active transferred-tax rates (global catalog, V15) for the variant form. */
    @Transactional(readOnly = true)
    public List<TaxRateView> taxes(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> jdbc.query("""
                        SELECT code, scheme, factor, rate, description
                        FROM tax_rate
                        WHERE active = true
                        ORDER BY scheme, rate DESC, code
                        """,
                (rs, i) -> new TaxRateView(
                        rs.getString("code"),
                        rs.getString("scheme"),
                        rs.getString("factor"),
                        rs.getBigDecimal("rate").toPlainString(),
                        rs.getString("description"))));
    }

    /** Federal retention rates (global catalog, V19 · ADR-021) for the invoice form. */
    @Transactional(readOnly = true)
    public List<WithholdingRateView> withholdings(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> jdbc.query("""
                        SELECT code, scheme, rate, description
                        FROM withholding_rate
                        ORDER BY scheme, rate DESC, code
                        """,
                (rs, i) -> new WithholdingRateView(
                        rs.getString("code"),
                        rs.getString("scheme"),
                        rs.getBigDecimal("rate").toPlainString(),
                        rs.getString("description"))));
    }

    // ---- Internals ----

    private ProductVariant saveVariant(UUID tenantId, Product product,
                                       VerticalProfile.Profile profile, VariantCommand command) {
        requireUnit(profile, command.unit());
        String sku = requireNotBlank(command.sku()).toUpperCase(Locale.ROOT);
        if (variantRepository.existsByTenantIdAndSkuIgnoreCase(tenantId, sku)) {
            throw new DuplicateSkuException(
                    "El SKU " + sku + " ya está registrado en esta organización");
        }
        requireAttributes(profile, command.attributes());
        validatePrice(command.price());
        String taxRateCode = requireValidTaxRateCode(command.taxRateCode());
        String iepsRateCode = requireValidIepsRateCode(command.iepsRateCode());

        return variantRepository.save(new ProductVariant(
                tenantId,
                product,
                sku,
                blankToNull(command.barcode()),
                command.unit(),
                command.attributes(),
                command.price(),
                taxRateCode,
                iepsRateCode));
    }

    private VerticalProfile.Profile profileOf(UUID tenantId) {
        Organization organization = organizationRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + tenantId));
        return VerticalProfile.require(organization.getVerticalCode());
    }

    private static void requireUnit(VerticalProfile.Profile profile, String unit) {
        if (!profile.allowedUnits().contains(unit)) {
            throw new IllegalArgumentException("La unidad " + unit
                    + " no es válida para el giro " + profile.code()
                    + ". Unidades: " + String.join(", ", profile.allowedUnits()));
        }
    }

    /** Attributes carry ONLY declared keys; required ones must be present. */
    private static void requireAttributes(VerticalProfile.Profile profile,
                                          Map<String, String> attributes) {
        Map<String, String> given = attributes == null ? Map.of() : attributes;
        Map<String, VerticalProfile.ProductAttributeDef> declared = new LinkedHashMap<>();
        for (VerticalProfile.ProductAttributeDef def : profile.productAttributes()) {
            declared.put(def.code(), def);
        }
        for (String key : given.keySet()) {
            if (!declared.containsKey(key)) {
                throw new IllegalArgumentException("Atributo no definido en el giro "
                        + profile.code() + ": " + key + ". Definidos: " + declared.keySet());
            }
        }
        for (VerticalProfile.ProductAttributeDef def : declared.values()) {
            if (def.required() && (given.get(def.code()) == null || given.get(def.code()).isBlank())) {
                throw new IllegalArgumentException(
                        "El atributo obligatorio " + def.code() + " (" + def.displayName() + ") falta");
            }
        }
    }

    private static void validatePrice(BigDecimal price) {
        if (price != null && price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("El precio no puede ser negativo");
        }
    }

    /**
     * Tax rule of ADR-018 (rule 4 of ADR-006): rate codes are catalog data,
     * never hardcoded — blank/missing = sin impuesto (legacy behavior), and a
     * non-blank code MUST exist and be active in the global tax_rate table.
     */
    private String requireValidTaxRateCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        List<String> found = jdbc.query(
                "SELECT code FROM tax_rate WHERE code = ? AND active = true",
                (rs, i) -> rs.getString(1), trimmed);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("La tasa de impuesto '" + trimmed
                    + "' no existe en el catálogo de impuestos");
        }
        return trimmed;
    }

    /**
     * IEPS slot rule (v0.11 · ADR-021): the code must exist AND belong to the
     * IEPS scheme — the IVA slot never hosts IEPS again. Blank = sin IEPS.
     */
    private String requireValidIepsRateCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        List<String> found = jdbc.query(
                "SELECT code FROM tax_rate WHERE code = ? AND scheme = 'IEPS' AND active = true",
                (rs, i) -> rs.getString(1), trimmed);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("La tasa IEPS '" + trimmed
                    + "' no existe en el catálogo de impuestos");
        }
        return trimmed;
    }

    static String requireNotBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El SKU es obligatorio");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
