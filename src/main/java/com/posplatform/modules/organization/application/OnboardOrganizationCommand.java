package com.posplatform.modules.organization.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to onboard a new organization with its first legal entity, branch,
 * OWNER role, membership and trial subscription — one ACID transaction.
 */
public record OnboardOrganizationCommand(
        @NotBlank @Size(max = 120) String organizationName,
        @NotBlank @Size(max = 320) @Email String ownerEmail,
        @NotBlank @Size(max = 120) String ownerDisplayName,
        @NotBlank @Size(max = 180) String legalName,
        @Size(max = 180) String tradeName,
        @Size(max = 32) String taxId,
        @NotBlank @Size(max = 120) String branchName,
        @Size(max = 120) String branchCity
) {
}
