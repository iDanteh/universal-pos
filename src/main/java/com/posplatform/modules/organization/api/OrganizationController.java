package com.posplatform.modules.organization.api;

import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.infrastructure.OrganizationRepository;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Organization REST surface (v0.2 start). Fail-closed: mapped under the
 * authenticated chain and the tenant MUST be bound by SessionAuthFilter
 * (X-Tenant-Id verified against a live ACTIVE membership inside RLS).
 *
 * The read runs INSIDE the tenant-bound transaction (set_config + select in
 * the same scope) so RLS evaluates it against the verified tenant.
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationRepository organizationRepository;
    private final TenantTransactionRunner tenantRunner;

    public OrganizationController(OrganizationRepository organizationRepository,
                                  TenantTransactionRunner tenantRunner) {
        this.organizationRepository = organizationRepository;
        this.tenantRunner = tenantRunner;
    }

    public record CurrentOrganization(UUID id, String name, String verticalCode) {
    }

    @GetMapping("/current")
    public CurrentOrganization current() {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
        return tenantRunner.run(tenantId, () -> {
            Organization organization = organizationRepository.findById(tenantId)
                    .orElseThrow(() -> new NotFoundException("organization", tenantId.toString()));
            return new CurrentOrganization(
                    organization.getId(), organization.getName(), organization.getVerticalCode());
        });
    }
}
