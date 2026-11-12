package com.posplatform.modules.branding.application;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inheritance chain (ADR-007): platform defaults → brand.config →
 * organization overrides. Maps merge KEY-WISE; scalars overwrite; deepest
 * contributing tier is reported as source. PURE logic — no Spring, no DB.
 */
class EffectiveBrandingTest {

    @Test
    void platformOnlyProducesDefaults() {
        EffectiveBranding branding = EffectiveBranding.compose("platform", EffectiveBranding.platformDefaults());

        assertThat(branding.name()).isEqualTo("POS Platform");
        assertThat(branding.colors()).containsEntry("primary", "#0e7a6a");
        assertThat(branding.templates()).containsKey("invoice");
        assertThat(branding.featureFlags()).containsEntry("offlineMode", true);
        assertThat(branding.source()).isEqualTo("platform");
    }

    @Test
    void brandConfigOverwritesOnlyItsKeys() {
        Map<String, Object> brandConfig = Map.of(
                "name", "VendeMax",
                "colors", Map.of("primary", "#7c3aed"));   // only primary changed

        EffectiveBranding branding = EffectiveBranding.compose(
                "brand", EffectiveBranding.platformDefaults(), brandConfig);

        // scalar overwritten
        assertThat(branding.name()).isEqualTo("VendeMax");
        // colors merged key-wise: surface/text survive from platform tier
        assertThat(branding.colors())
                .containsEntry("primary", "#7c3aed")
                .containsEntry("surface", "#ffffff")
                .containsEntry("text", "#1c2723");
        // templates still platform defaults
        assertThat(branding.templates()).containsKey("invoice");
        assertThat(branding.source()).isEqualTo("brand");
    }

    @Test
    void organizationOverridesWinOverEverything() {
        Map<String, Object> brandConfig = Map.of(
                "name", "VendeMax",
                "colors", Map.of("primary", "#7c3aed"),
                "featureFlags", Map.of("offlineMode", true));

        Map<String, Object> overrides = Map.of(
                "colors", Map.of("primary", "#111827", "surface", "#0b0f0e"),
                "featureFlags", Map.of("offlineMode", false),  // org may disable what brand enabled
                "logoUrl", "https://cdn.example.com/org-logo.svg");

        EffectiveBranding branding = EffectiveBranding.compose(
                "organization", EffectiveBranding.platformDefaults(), brandConfig, overrides);

        assertThat(branding.colors()).containsEntry("primary", "#111827")
                .containsEntry("surface", "#0b0f0e")
                .containsEntry("text", "#1c2723");  // untouched platform key survives 3 levels
        assertThat(branding.featureFlags()).containsEntry("offlineMode", false);
        assertThat(branding.logoUrl()).isEqualTo("https://cdn.example.com/org-logo.svg");
        assertThat(branding.source()).isEqualTo("organization");
    }

    @Test
    void nullLevelsAreIgnored() {
        EffectiveBranding branding = EffectiveBranding.compose(
                "platform", EffectiveBranding.platformDefaults(), null, null);
        assertThat(branding.name()).isEqualTo("POS Platform");
        assertThat(branding.logoUrl()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deepMergeDoesNotMutateInputs() {
        Map<String, Object> platform = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put("primary", "#0e7a6a");
        platform.put("colors", inner);

        Map<String, Object> override = Map.of("colors", Map.of("primary", "#000000"));

        EffectiveBranding.compose("organization", platform, override);

        // the platform baseline map was NOT polluted by the override
        assertThat(((Map<String, Object>) platform.get("colors")).get("primary")).isEqualTo("#0e7a6a");
    }
}
