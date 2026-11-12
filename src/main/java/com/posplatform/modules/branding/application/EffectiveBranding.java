package com.posplatform.modules.branding.application;

import java.util.HashMap;
import java.util.Map;

/**
 * The branding a user ACTUALLY experiences — resolved by {@link BrandResolver}
 * from the three-tier inheritance chain of ADR-007:
 *
 *   platform defaults  →  brand.config  →  organization_brand.overrides
 *
 * Purity note: {@link #compose} performs the deep merge and is a STATIC,
 * side-effect-free function — no Spring, no DB — so the inheritance rule is
 * exhaustively unit-testable without a database.
 *
 * Typed keys (ADR-007): a value override defines its type by key convention
 * (colors{primary,surface,text}, name, logoUrl, faviconUrl, domain,
 * templates{invoice,thermal,email}, featureFlags{...}). Unknown keys are
 * carried through untouched (forward compatible) but consumers ignore them.
 */
public record EffectiveBranding(
        String name,
        String logoUrl,
        String faviconUrl,
        String domain,
        Map<String, Object> colors,
        Map<String, Object> templates,
        Map<String, Object> featureFlags,
        String source   // which level provided the LAST winning definition
) {

    public EffectiveBranding {
        colors = colors == null ? Map.of() : Map.copyOf(colors);
        templates = templates == null ? Map.of() : Map.copyOf(templates);
        featureFlags = featureFlags == null ? Map.of() : Map.copyOf(featureFlags);
    }

    /** Platform-level baseline of ADR-007 (used when no brand is attached). */
    public static Map<String, Object> platformDefaults() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("name", "POS Platform");
        defaults.put("colors", Map.of(
                "primary", "#0e7a6a",
                "surface", "#ffffff",
                "text", "#1c2723"));
        defaults.put("templates", Map.of(
                "invoice", "standard",
                "thermal", "standard-58mm",
                "email", "default"));
        defaults.put("featureFlags", Map.of(
                "offlineMode", true,
                "resellerPortal", false));
        return defaults;
    }

    /**
     * Deep-merges config levels into the effective branding. Maps merge KEY-WISE
     * (a colors override only replaces the keys it sets); scalars overwrite.
     * The last level in the argument order always wins.
     */
    public static EffectiveBranding compose(String source,
                                            Map<String, Object>... levels) {
        Map<String, Object> merged = new HashMap<>();
        for (Map<String, Object> level : levels) {
            if (level != null) {
                deepMerge(merged, level);
            }
        }
        return new EffectiveBranding(
                (String) merged.get("name"),
                (String) merged.get("logoUrl"),
                (String) merged.get("faviconUrl"),
                (String) merged.get("domain"),
                asMap(merged.get("colors")),
                asMap(merged.get("templates")),
                asMap(merged.get("featureFlags")),
                source);
    }

    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return copy;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            Object current = target.get(entry.getKey());
            if (value instanceof Map<?, ?> valueMap && current instanceof Map<?, ?> currentMap) {
                Map<String, Object> mergedChild = new HashMap<>(cast(currentMap));
                deepMerge(mergedChild, cast(valueMap));
                target.put(entry.getKey(), mergedChild);
            } else if (value instanceof Map<?, ?> valueMap) {
                Map<String, Object> mergedChild = new HashMap<>();
                deepMerge(mergedChild, cast(valueMap));
                target.put(entry.getKey(), mergedChild);
            } else {
                target.put(entry.getKey(), value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
