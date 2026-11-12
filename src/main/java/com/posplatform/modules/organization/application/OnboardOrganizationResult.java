package com.posplatform.modules.organization.application;

import java.util.UUID;

/** Read-side contract of the onboarding use case result. */
public record OnboardOrganizationResult(
        UUID organizationId,
        UUID legalEntityId,
        UUID branchId,
        UUID ownerId,
        UUID roleId,
        UUID membershipId,
        UUID subscriptionId
) {
}
