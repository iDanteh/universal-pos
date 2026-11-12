package com.posplatform.modules.organization;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declarative registry of business verticals (giros) — ADR-010.
 *
 * A vertical is CODE + CATALOG, never free per-tenant logic: it parametrizes
 * the single inventory/product engine of ADR-004 by declaring units of
 * measure, tracking dimensions (lot/expiry/serial), stock policy and
 * product attributes. Adding a new vertical means adding an entry here AND
 * its code to the DB CHECK (V8), keeping both sides synchronized by test.
 *
 * Engine invariant: EVERY valid vertical must be expressible by ADR-004's
 * conceptual model (Product/Variant/Warehouse/Balance/Movement/Lot/Serial/
 * Reservation) — a profile only narrows what the engine pays attention to.
 */
public final class VerticalProfile {

    /** Tracking dimensions a vertical forces the engine to maintain (ADR-004). */
    public enum Dimension { LOT, EXPIRY, SERIAL }

    /** Negative stock behaviour enforced by the sales/inventory core. */
    public enum NegativeStockPolicy { FORBIDDEN, ALLOWED_WITH_ALERT, ALLOWED }

    /** Attribute columns the vertical requires on the product definition. */
    public record ProductAttributeDef(String code, String displayName, boolean required) {
        public ProductAttributeDef {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("attribute code required");
            if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("attribute displayName required");
        }
    }

    /**
     * One vertical profile. immutable.
     */
    public record Profile(
            String code,
            String displayName,
            String baseUnit,
            Set<String> allowedUnits,
            Set<Dimension> trackedDimensions,
            NegativeStockPolicy negativeStockPolicy,
            Set<ProductAttributeDef> productAttributes
    ) {
        public Profile {
            if (allowedUnits == null || !allowedUnits.contains(baseUnit)) {
                throw new IllegalArgumentException("allowedUnits must contain baseUnit for " + code);
            }
            if (trackedDimensions == null) trackedDimensions = Set.of();
            if (productAttributes == null) productAttributes = Set.of();
        }

        public boolean tracks(Dimension dimension) {
            return trackedDimensions.contains(dimension);
        }
    }

    /** Valid codes — MUST stay in sync with the V8 CHECK constraint. */
    public static final class Code {
        public static final String RETAIL       = "RETAIL";
        public static final String RESTAURANT   = "RESTAURANT";
        public static final String PHARMACY     = "PHARMACY";
        public static final String HARDWARE     = "HARDWARE";
        public static final String DISTRIBUTION = "DISTRIBUTION";
        public static final String CUSTOM       = "CUSTOM";

        private Code() {
        }
    }

    private static final Set<String> BASE_UNITS = Set.of("PZ", "KG", "LT", "GR", "MT", "M2", "SRV");

    private static final Map<String, Profile> REGISTRY = Map.of(
            Code.RETAIL, new Profile(
                    Code.RETAIL, "Comercio minorista",
                    "PZ", Set.of("PZ", "KG", "LT"),
                    Set.of(),
                    NegativeStockPolicy.ALLOWED_WITH_ALERT,
                    Set.of(
                            new ProductAttributeDef("brand", "Marca", false),
                            new ProductAttributeDef("model", "Modelo", false),
                            new ProductAttributeDef("size", "Talla/Color", false)
                    )),
            Code.RESTAURANT, new Profile(
                    Code.RESTAURANT, "Alimentos y bebidas",
                    "SRV", Set.of("SRV", "PZ", "KG", "LT", "GR"),
                    Set.of(Dimension.LOT, Dimension.EXPIRY),
                    NegativeStockPolicy.FORBIDDEN,
                    Set.of(
                            new ProductAttributeDef("category", "Categoría culinaria", true),
                            new ProductAttributeDef("portion", "Porción", false)
                    )),
            Code.PHARMACY, new Profile(
                    Code.PHARMACY, "Farmacia",
                    "PZ", Set.of("PZ", "CAJA"),
                    Set.of(Dimension.LOT, Dimension.EXPIRY),
                    NegativeStockPolicy.FORBIDDEN,
                    Set.of(
                            new ProductAttributeDef("activeIngredient", "Principio activo", true),
                            new ProductAttributeDef("dosage", "Dosis", false),
                            new ProductAttributeDef("regulatedClass", "Clase regulada", false)
                    )),
            Code.HARDWARE, new Profile(
                    Code.HARDWARE, "Ferretería y materiales",
                    "PZ", Set.of("PZ", "KG", "MT", "M2", "LT"),
                    Set.of(Dimension.LOT),
                    NegativeStockPolicy.ALLOWED_WITH_ALERT,
                    Set.of(
                            new ProductAttributeDef("material", "Material", false),
                            new ProductAttributeDef("dimensions", "Dimensiones", false),
                            new ProductAttributeDef("brand", "Marca", false)
                    )),
            Code.DISTRIBUTION, new Profile(
                    Code.DISTRIBUTION, "Distribuidora / mayoreo",
                    "PZ", Set.of("PZ", "KG", "LT", "CAJA"),
                    Set.of(Dimension.LOT, Dimension.EXPIRY),
                    NegativeStockPolicy.FORBIDDEN,
                    Set.of(
                            new ProductAttributeDef("brand", "Marca", true),
                            new ProductAttributeDef("skuSupplier", "SKU del proveedor", true),
                            new ProductAttributeDef("minOrder", "Pedido mínimo", false)
                    )),
            Code.CUSTOM, new Profile(
                    Code.CUSTOM, "Giro abierto (base)",
                    "PZ", BASE_UNITS,
                    Set.of(),
                    NegativeStockPolicy.ALLOWED_WITH_ALERT,
                    Set.of())
    );

    private VerticalProfile() {
    }

    /** All known profiles (stable order). */
    public static List<Profile> all() {
        return List.of(
                REGISTRY.get(Code.RETAIL), REGISTRY.get(Code.RESTAURANT),
                REGISTRY.get(Code.PHARMACY), REGISTRY.get(Code.HARDWARE),
                REGISTRY.get(Code.DISTRIBUTION), REGISTRY.get(Code.CUSTOM));
    }

    public static boolean exists(String code) {
        return code != null && REGISTRY.containsKey(code.trim());
    }

    /** Profile for a known code; unknown → IllegalArgumentException (fail fast). */
    public static Profile require(String code) {
        if (code == null) throw new IllegalArgumentException("vertical code must not be null");
        Profile profile = REGISTRY.get(code.trim());
        if (profile == null) {
            throw new IllegalArgumentException("Unknown vertical code: " + code
                    + ". Known codes: " + REGISTRY.keySet());
        }
        return profile;
    }

    /** Throwing-by-code used by Organization.changeVertical (entity style). */
    public static void requireKnown(String code) {
        require(code);
    }

    /** TRUE if the vertical mandates per-variant lot tracking in the engine. */
    public static boolean tracksLots(String code) {
        return require(code).tracks(Dimension.LOT);
    }
}
