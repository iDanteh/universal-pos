package com.posplatform.modules.organization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The vertical registry is a CODE + CATALOG contract (ADR-010): its codes
 * MUST match the V8 CHECK constraint so both sides can never drift apart.
 */
class VerticalProfilesTest {

    @Test
    void allSixKnownCodesExistExactlyAsDeclaredInV8Check() {
        List<String> codes = VerticalProfile.all().stream().map(VerticalProfile.Profile::code).toList();
        assertThat(codes).containsExactly(
                "RETAIL", "RESTAURANT", "PHARMACY", "HARDWARE", "DISTRIBUTION", "CUSTOM");
    }

    @Test
    void pharmacyRequiresLotAndExpiryTracking() {
        var pharmacy = VerticalProfile.require("PHARMACY");
        assertThat(pharmacy.trackedDimensions()).containsExactlyInAnyOrder(
                VerticalProfile.Dimension.LOT, VerticalProfile.Dimension.EXPIRY);
        assertThat(pharmacy.negativeStockPolicy()).isEqualTo(VerticalProfile.NegativeStockPolicy.FORBIDDEN);
        assertThat(pharmacy.productAttributes())
                .extracting(VerticalProfile.ProductAttributeDef::code)
                .contains("activeIngredient");
    }

    @Test
    void retailIsSimpleHighVolumeProfile() {
        var retail = VerticalProfile.require("RETAIL");
        assertThat(retail.trackedDimensions()).isEmpty();
        assertThat(retail.baseUnit()).isEqualTo("PZ");
        assertThat(retail.allowedUnits()).contains("PZ", "KG", "LT");
    }

    @Test
    void distributionRequiresSupplierSkuAttribute() {
        var distribution = VerticalProfile.require("DISTRIBUTION");
        assertThat(distribution.productAttributes())
                .filteredOn(attribute -> "skuSupplier".equals(attribute.code()))
                .extracting(VerticalProfile.ProductAttributeDef::required)
                .containsExactly(true);
    }

    @Test
    void unknownCodeIsRejectedWithKnownCodesInMessage() {
        assertThatThrownBy(() -> VerticalProfile.require("SPA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPA")
                .hasMessageContaining("PHARMACY");
    }

    @Test
    void everyProfileAllowsItsBaseUnit() {
        // invariant enforced by the record constructor; spot-check the catch of a legacy typo
        assertThatThrownBy(() -> new VerticalProfile.Profile(
                "X", "broken", "ZZ", java.util.Set.of("PZ"),
                java.util.Set.of(), VerticalProfile.NegativeStockPolicy.ALLOWED, java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUnit");
    }
}
