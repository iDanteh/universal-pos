package com.posplatform.modules.organization.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to add a SECOND (or later) razón social to an existing organization
 * plus an optional opening branch — ADR-009. The caller already exists in a
 * tenant; shape-validated here, business invariants (unique RFC per tenant,
 * known vertical) enforced in the service and by DB constraints.
 */
public record AddLegalEntityCommand(
        @NotBlank @Size(max = 180) String legalName,
        @Size(max = 180) String tradeName,
        @Size(max = 32) String taxId,
        /** Optional branch opened under the new legal entity (same transaction). */
        @Size(max = 120) String branchName,
        @Size(max = 120) String branchCity
) {
}
