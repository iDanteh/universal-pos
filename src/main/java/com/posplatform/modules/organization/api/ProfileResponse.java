package com.posplatform.modules.organization.api;

import com.posplatform.modules.organization.VerticalProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * REST view of the declarative vertical profile (ADR-010). Mapped from the
 * domain record so the wire contract stays stable (sorted collections, enum
 * as plain names) and future domain changes never leak raw field churn.
 */
public record ProfileResponse(
        String code,
        String displayName,
        String baseUnit,
        List<String> allowedUnits,
        List<String> trackedDimensions,
        String negativeStockPolicy,
        List<AttributeResponse> productAttributes
) {

    /** REST projection of a product attribute definition. */
    public record AttributeResponse(String code, String displayName, boolean required) {
    }

    public static ProfileResponse of(VerticalProfile.Profile profile) {
        return new ProfileResponse(
                profile.code(),
                profile.displayName(),
                profile.baseUnit(),
                sorted(profile.allowedUnits()),
                profile.trackedDimensions().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList(),
                String.valueOf(profile.negativeStockPolicy()).toUpperCase(Locale.ROOT),
                profile.productAttributes().stream()
                        .sorted(Comparator.comparing(VerticalProfile.ProductAttributeDef::code))
                        .map(attr -> new AttributeResponse(
                                attr.code(), attr.displayName(), attr.required()))
                        .toList());
    }

    private static List<String> sorted(Iterable<String> values) {
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .sorted()
                .toList();
    }
}
