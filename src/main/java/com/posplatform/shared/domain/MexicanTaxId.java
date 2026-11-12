package com.posplatform.shared.domain;

/**
 * Mexican taxpayer id (RFC) value semantics — ADR-006 groundwork used by the
 * legal-entity use cases of ADR-009.
 *
 * Validation is intentionally SHARED DOMAIN code (not per-module): the fiscal
 * module, onboarding and org-growth all must reject the same malformed input.
 * The regex accepts physical persons (4 letters + 6 date digits + 3 chars)
 * and moral entities (3 letters + 6 digits + 3 chars), including the Ñ/&.
 * HOME-ENDED RFC (applied for at SAT) is accepted by the "1" ending rule.
 *
 * This is a format guard, NOT a SAT existence check — that integration is
 * deferred (ADR-006, real CFDI emission in v0.2+).
 */
public final class MexicanTaxId {

    // 3-4 letters(+Ñ&), 6 digits, 3 alphanumeric characters (may be "1" home-ended)
    private static final String RFC_PATTERN = "^([A-ZÑ&]{3,4})(\\d{6})([A-Z\\d]{3})$";

    private MexicanTaxId() {
    }

    /** Normalize input: trim and uppercase (accent-less N for Ñ tolerated). */
    public static String normalize(String raw) {
        if (raw == null) return null;
        return raw.trim().toUpperCase();
    }

    /**
     * TRUE if the value is a well-formed RFC (shape only).
     * null/blank is a valid ANSWER (optional field), validate intent upstream.
     */
    public static boolean isWellFormed(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        return normalized.matches(RFC_PATTERN);
    }

    /** Normalize + validate; throws IllegalArgumentException with user-facing message. */
    public static String requireValid(String raw) {
        String normalized = normalize(raw);
        if (!isWellFormed(normalized)) {
            throw new IllegalArgumentException(
                    "RFC mal formado; formato esperado: 3-4 letras + 6 dígitos + 3 caracteres (p. ej. GME800410HM2)");
        }
        return normalized;
    }
}
