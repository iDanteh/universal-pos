package com.posplatform.modules.organization.application.ports;

import com.posplatform.modules.identity.AppUser;

import java.util.UUID;

/**
 * PORT: what the organization module needs from the identity module.
 * Implemented by an identity adapter; prevents direct coupling between modules.
 */
public interface UserProvisioning {

    /**
     * Creates a platform user or returns the existing one (idempotent by email)
     * and records the identity-resolution mirror entry for the given
     * organization (only called where a verified Membership is being created).
     */
    AppUser createUserOrExisting(String email, String displayName,
                                 UUID organizationId, String organizationName);
}
