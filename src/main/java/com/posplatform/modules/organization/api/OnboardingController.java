package com.posplatform.modules.organization.api;

import com.posplatform.modules.identity.AppUser;
import com.posplatform.modules.identity.infrastructure.AppUserRepository;
import com.posplatform.modules.organization.application.OnboardOrganizationCommand;
import com.posplatform.modules.organization.application.OnboardingService;
import com.posplatform.config.SessionAuthFilter;
import com.posplatform.shared.api.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ONBOARDING REST surface — SELF-SERVICE bootstrap for authenticated
 * operators (ADR-012):
 *
 *  - The endpoint REQUIRES a live session: an operator with an account
 *    creates a new organization and becomes its OWNER automatically.
 *  - The owner identity is resolved SERVER-SIDE from the session user
 *    ({@link SessionAuthFilter#REQUEST_USER_ID}): whatever the client
 *    sends for owner fields is ignored BY CONSTRUCTION (the request shape
 *    does not even carry them). This closes the cross-tenant bypass that
 *    an arbitrary ownerEmail would open.
 *  - The very FIRST operator of an environment is provisioned by the
 *    trusted platform bootstrap (direct seed), not through this endpoint.
 *
 * Requires authentication only — NO tenant binding: the operation creates
 * a NEW tenant, so X-Tenant-Id is deliberately irrelevant here.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final AppUserRepository appUserRepository;

    public OnboardingController(OnboardingService onboardingService,
                                AppUserRepository appUserRepository) {
        this.onboardingService = onboardingService;
        this.appUserRepository = appUserRepository;
    }

    /**
     * Request shape WITHOUT owner fields: the owner is always the session user.
     * Bean Validation mirrors the shared command constraints.
     */
    public record OnboardingRequest(
            @NotBlank @Size(max = 120) String organizationName,
            @NotBlank @Size(max = 180) String legalName,
            @Size(max = 180) String tradeName,
            @Size(max = 32) String taxId,
            @NotBlank @Size(max = 120) String branchName,
            @Size(max = 120) String branchCity
    ) {
    }

    /** Created graph ids; id fields are opaque handles for the client. */
    public record OnboardingResponse(
            UUID organizationId,
            UUID legalEntityId,
            UUID branchId,
            UUID ownerId,
            UUID ownerRoleId,
            UUID membershipId,
            UUID subscriptionId
    ) {
    }

    @PostMapping
    public ResponseEntity<OnboardingResponse> onboard(
            @Valid @RequestBody OnboardingRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        UUID userId = (UUID) httpRequest.getAttribute(SessionAuthFilter.REQUEST_USER_ID);
        // Fail-closed: a live session always references a persisted user; anything
        // else is a security event, never an onboarding.
        AppUser sessionUser = appUserRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("user", userId.toString()));

        com.posplatform.modules.organization.application.OnboardOrganizationResult result =
                onboardingService.onboard(
                new OnboardOrganizationCommand(
                        request.organizationName(),
                        sessionUser.getEmail(),
                        sessionUser.getDisplayName(),
                        request.legalName(),
                        request.tradeName(),
                        request.taxId(),
                        request.branchName(),
                        request.branchCity()));

        return ResponseEntity.status(HttpStatus.CREATED).body(new OnboardingResponse(
                result.organizationId(),
                result.legalEntityId(),
                result.branchId(),
                result.ownerId(),
                result.roleId(),
                result.membershipId(),
                result.subscriptionId()));
    }
}
