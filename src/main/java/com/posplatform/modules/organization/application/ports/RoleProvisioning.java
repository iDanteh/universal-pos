package com.posplatform.modules.organization.application.ports;

import com.posplatform.modules.identity.Role;
import com.posplatform.modules.organization.Organization;

/** PORT: default OWNER role provisioning for a new organization. */
public interface RoleProvisioning {

    Role createOwnerRole(Organization organization);
}
