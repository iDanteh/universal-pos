package com.posplatform.modules.identity.application;

import com.posplatform.modules.identity.AppUser;
import com.posplatform.modules.identity.AppUserOrganization;
import com.posplatform.modules.identity.infrastructure.AppUserOrganizationRepository;
import com.posplatform.modules.identity.infrastructure.AppUserRepository;
import com.posplatform.modules.organization.application.ports.UserProvisioning;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Identity adapter for the UserProvisioning port.
 * Owned by the identity module; the organization module only sees the port.
 *
 * The mirror write happens ONLY here — in the same transaction where a
 * verified Membership is being inserted — so the identity store can never
 * drift ahead of real grants (RLS remains the enforcement layer).
 */
@Component
public class IdentityUserProvisioning implements UserProvisioning {

    private final AppUserRepository appUserRepository;
    private final AppUserOrganizationRepository appUserOrganizationRepository;

    public IdentityUserProvisioning(AppUserRepository appUserRepository,
                                    AppUserOrganizationRepository appUserOrganizationRepository) {
        this.appUserRepository = appUserRepository;
        this.appUserOrganizationRepository = appUserOrganizationRepository;
    }

    @Override
    public AppUser createUserOrExisting(String email, String displayName,
                                        UUID organizationId, String organizationName) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(email)
                .orElseGet(() -> appUserRepository.save(new AppUser(email, displayName)));

        if (appUserOrganizationRepository.findByUserIdAndOrganizationId(user.getId(), organizationId).isEmpty()) {
            appUserOrganizationRepository.save(
                    new AppUserOrganization(user.getId(), organizationId, organizationName));
        }
        return user;
    }
}
