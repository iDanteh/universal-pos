package com.posplatform.modules.organization.application;

import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.VerticalProfile;
import com.posplatform.modules.organization.infrastructure.OrganizationRepository;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * VERTICAL PROFILE use case (ADR-010): read or change the business vertical
 * (giro) of an organization.
 *
 * The vertical NEVER changes behaviour with per-tenant code: it switches the
 * declarative parameterization (units, tracking dimensions, stock policy)
 * that future product/inventory modules consult through {@link #profileOf}.
 * Changing to an unknown code is rejected by {@link Organization#changeVertical}
 * AND by the DB CHECK constraint (V8) — double enforcement.
 */
@Service
@Validated
public class OrganizationProfileService {

    private final OrganizationRepository organizationRepository;
    private final TenantTransactionRunner tenantRunner;

    public OrganizationProfileService(OrganizationRepository organizationRepository,
                                      TenantTransactionRunner tenantRunner) {
        this.organizationRepository = organizationRepository;
        this.tenantRunner = tenantRunner;
    }

    /** Effective declarative profile of the tenant's current vertical. */
    @Transactional(readOnly = true)
    public VerticalProfile.Profile profileOf(UUID organizationId) {
        return tenantRunner.run(organizationId, () -> {
            Organization organization = organizationRepository.findById(organizationId)
                    .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));
            return VerticalProfile.require(organization.getVerticalCode());
        });
    }

    /** Change giro; business rule validation happens on the entity. */
    public VerticalProfile.Profile changeVertical(UUID organizationId, String verticalCode) {
        return tenantRunner.run(organizationId, () -> {
            Organization organization = organizationRepository.findById(organizationId)
                    .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));
            organization.changeVertical(verticalCode);
            Organization saved = organizationRepository.save(organization);
            return VerticalProfile.require(saved.getVerticalCode());
        });
    }
}
