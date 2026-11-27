package com.posplatform.modules.identity.application;

import com.posplatform.modules.identity.Permission;
import com.posplatform.modules.identity.Role;
import com.posplatform.modules.identity.infrastructure.PermissionRepository;
import com.posplatform.modules.identity.infrastructure.RoleRepository;
import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.application.ports.RoleProvisioning;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Identity adapter for the RoleProvisioning port.
 * Grants the OWNER role the foundational permission set.
 */
@Component
public class IdentityTenantRoleProvisioning implements RoleProvisioning {

    /** Full manifest granted on onboarding (v0.10 · ADR-020): the OWNER is the
     *  bootstrap admin and must satisfy every guard of every endpoint. New
     *  permission codes arrive by migration (and are granted to existing
     *  system roles there) — this set tracks the full seeded catalog.
     */
    static final Set<String> OWNER_PERMISSIONS = Set.of(
            "org.view", "org.manage", "org.settings.manage",
            "users.manage", "roles.manage",
            "subscription.view", "subscription.manage",
            "branch.view", "branch.manage",
            "catalog.read", "catalog.write",
            "inventory.read", "inventory.write",
            "sales.read", "sales.execute",
            "fiscal.read", "fiscal.write",
            "cash.operate", "sync.operate"
    );

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public IdentityTenantRoleProvisioning(RoleRepository roleRepository,
                                          PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    @Override
    public Role createOwnerRole(Organization organization) {
        Role role = new Role(organization, "Administrador", "Acceso completo de administración");
        List<Permission> permissions = permissionRepository.findByCodeIn(OWNER_PERMISSIONS);
        if (permissions.size() < OWNER_PERMISSIONS.size()) {
            throw new IllegalStateException(
                    "Permission catalog incomplete for OWNER role; check migration seeds");
        }
        permissions.forEach(role::grant);
        role.markSystem();
        return roleRepository.save(role);
    }
}
