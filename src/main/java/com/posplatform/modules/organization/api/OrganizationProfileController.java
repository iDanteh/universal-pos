package com.posplatform.modules.organization.api;

import com.posplatform.modules.organization.LegalEntity;
import com.posplatform.modules.organization.application.AddLegalEntityService;
import com.posplatform.modules.organization.application.OrganizationProfileService;
import com.posplatform.modules.organization.infrastructure.LegalEntityRepository;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Organization PROFILE + GROWTH REST surface. Fail-closed: the tenant comes
 * ONLY from SessionAuthFilter (X-Tenant-Id verified against a LIVE membership
 * inside RLS); a missing/unverified tenant leaves the request tenant-less and
 * every tenant-scoped endpoint here answers 404 — never a guess.
 *
 * Reads run INSIDE the tenant-bound transaction (set_config + query in the
 * same scope) so RLS evaluates them against the verified tenant.
 */
@RestController
@RequestMapping("/api/v1/organizations/current")
public class OrganizationProfileController {

    private final OrganizationProfileService profileService;
    private final AddLegalEntityService addLegalEntityService;
    private final LegalEntityRepository legalEntityRepository;
    private final TenantTransactionRunner tenantRunner;

    public OrganizationProfileController(OrganizationProfileService profileService,
                                         AddLegalEntityService addLegalEntityService,
                                         LegalEntityRepository legalEntityRepository,
                                         TenantTransactionRunner tenantRunner) {
        this.profileService = profileService;
        this.addLegalEntityService = addLegalEntityService;
        this.legalEntityRepository = legalEntityRepository;
        this.tenantRunner = tenantRunner;
    }

    // ---- Requests / responses ----

    public record VerticalChangeRequest(@NotBlank @Size(max = 20) String verticalCode) {
    }

    public record LegalEntityRequest(
            @NotBlank @Size(max = 180) String legalName,
            @Size(max = 180) String tradeName,
            @Size(max = 32) String taxId,
            @Size(max = 120) String branchName,
            @Size(max = 120) String branchCity
    ) {
    }

    public record LegalEntityCreatedResponse(UUID organizationId, UUID legalEntityId, UUID branchId) {
    }

    public record LegalEntityResponse(UUID id, String legalName, String tradeName, String taxId) {
    }

    // ---- Vertical profile (ADR-010) ----

    /** Effective declarative profile of the tenant's CURRENT vertical. */
    @GetMapping("/profile")
    public ProfileResponse profile() {
        return ProfileResponse.of(profileService.profileOf(currentTenant()));
    }

    /** Change giro: pure declarative switch (entity-validated, DB-CHECK backed). */
    @PostMapping("/vertical")
    public ProfileResponse changeVertical(@Valid @RequestBody VerticalChangeRequest request) {
        return ProfileResponse.of(profileService.changeVertical(currentTenant(), request.verticalCode()));
    }

    // ---- Legal entities (ADR-009 growth) ----

    @GetMapping("/legal-entities")
    public List<LegalEntityResponse> legalEntities() {
        UUID tenantId = currentTenant();
        return tenantRunner.run(tenantId, () ->
                legalEntityRepository.findByOrganizationId(tenantId).stream()
                        .map(OrganizationProfileController::toResponse)
                        .toList());
    }

    /** Add a razón social (+ optional first branch) inside the tenant scope. */
    @PostMapping("/legal-entities")
    public ResponseEntity<LegalEntityCreatedResponse> addLegalEntity(
            @Valid @RequestBody LegalEntityRequest request) {
        UUID tenantId = currentTenant();
        AddLegalEntityService.AddLegalEntityResult result = addLegalEntityService.add(tenantId,
                new com.posplatform.modules.organization.application.AddLegalEntityCommand(
                        request.legalName(),
                        request.tradeName(),
                        request.taxId(),
                        request.branchName(),
                        request.branchCity()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new LegalEntityCreatedResponse(
                        result.organizationId(), result.legalEntityId(), result.branchId()));
    }

    private static LegalEntityResponse toResponse(LegalEntity entity) {
        return new LegalEntityResponse(
                entity.getId(), entity.getLegalName(), entity.getTradeName(), entity.getTaxId());
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
