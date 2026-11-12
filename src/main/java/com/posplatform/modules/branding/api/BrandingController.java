package com.posplatform.modules.branding.api;

import com.posplatform.modules.branding.application.BrandResolver;
import com.posplatform.modules.branding.application.EffectiveBranding;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * WHITE LABEL REST surface (ADR-007). Returns the effective branding the
 * tenant is ACTUALLY served with — the same computation a POS client
 * resolver consumes — resolved inside the verified tenant's RLS scope.
 *
 * Fail-closed: without a SERVER-verified tenant the endpoint answers 404.
 * An organization without brand relations yields the platform defaults
 * (source = "platform").
 */
@RestController
@RequestMapping("/api/v1/branding")
public class BrandingController {

    private final BrandResolver brandResolver;

    public BrandingController(BrandResolver brandResolver) {
        this.brandResolver = brandResolver;
    }

    /** Effective branding of the current tenant (defaults → brand.config → overrides). */
    @GetMapping("/effective")
    public EffectiveBranding effective() {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
        return brandResolver.resolve(tenantId);
    }
}
