package com.posplatform.modules.branding.application;

import com.posplatform.modules.branding.Brand;
import com.posplatform.modules.branding.OrganizationBrand;
import com.posplatform.modules.branding.infrastructure.OrganizationBrandRepository;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WHITE LABEL resolver (ADR-007 + V8): computes the branding a tenant's
 * users experience by walking the declarative chain
 *
 *   platform defaults  →  brand.config  →  organization_brand.overrides
 *
 * declaratively — NEVER with code branches per brand. The merge rule lives
 * in {@link EffectiveBranding#compose} (pure, unit-tested); this component
 * only loads the levels for the tenant inside its RLS scope.
 *
 * TENANT BINDING: organization_brand is RLS-protected, so the resolver binds
 * the tenant (set_config) exactly like every other tenant use case. The
 * default answer when no relation exists still comes from platform defaults,
 * evaluated INSIDE the bound scope.
 *
 * v0.1.0 policy: if the organization has several brand relations the FIRST
 * wins (management UI deferred); direct resellers may later compose at the
 * platform tier before brand.config, keeping the same merge chain.
 */
@Component
public class BrandResolver {

    private final OrganizationBrandRepository organizationBrandRepository;
    private final TenantTransactionRunner tenantRunner;

    public BrandResolver(OrganizationBrandRepository organizationBrandRepository,
                         TenantTransactionRunner tenantRunner) {
        this.organizationBrandRepository = organizationBrandRepository;
        this.tenantRunner = tenantRunner;
    }

    /** Effective branding: exactly what the resolver of a POS client would consume. */
    public EffectiveBranding resolve(UUID organizationId) {
        return tenantRunner.run(organizationId, () -> {
            List<OrganizationBrand> relations = organizationBrandRepository.findByTenantId(organizationId);
            if (relations.isEmpty()) {
                return EffectiveBranding.compose("platform", EffectiveBranding.platformDefaults());
            }

            Map<String, Object> brandConfig = configOf(relations.get(0));
            Map<String, Object> overrides = overridesOf(relations.get(0));

            // source = deepest tier that contributed (overrides > brand > platform)
            String source = overrides.isEmpty() ? (brandConfig.isEmpty() ? "platform" : "brand") : "organization";

            return EffectiveBranding.compose(
                    source,
                    EffectiveBranding.platformDefaults(),
                    brandConfig,
                    overrides);
        });
    }

    private static Map<String, Object> configOf(OrganizationBrand relation) {
        Brand brand = relation.getBrand();
        return brand == null ? Map.of() : brand.getConfig();
    }

    private static Map<String, Object> overridesOf(OrganizationBrand relation) {
        Map<String, Object> overrides = relation.getOverrides();
        return overrides == null ? Map.of() : overrides;
    }
}
