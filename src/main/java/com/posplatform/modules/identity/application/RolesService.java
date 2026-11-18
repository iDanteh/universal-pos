package com.posplatform.modules.identity.application;

import com.posplatform.modules.identity.Permission;
import com.posplatform.modules.identity.Role;
import com.posplatform.modules.identity.infrastructure.MembershipRoleRepository;
import com.posplatform.modules.identity.infrastructure.PermissionRepository;
import com.posplatform.modules.identity.infrastructure.RoleRepository;
import com.posplatform.modules.organization.Organization;
import com.posplatform.modules.organization.infrastructure.OrganizationRepository;
import com.posplatform.shared.api.ConflictException;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.outbox.OutboxWriter;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ROLE & PERMISSION use cases (v0.10 · ADR-020). Custom roles are tenant
 * authoring: any subset of the SEEDED catalog (never invented codes). System
 * roles (provisioning, is_system) are immutable — the platform manifest flows
 * INTO them via migrations only.
 */
@Service
public class RolesService {

    public record RoleView(UUID id, String name, String description,
                           boolean system, List<String> permissions) {
    }

    public record PermissionView(String code, String name, String description) {
    }

    public record CreateRoleCommand(String name, String description, List<String> permissions) {
    }

    public record UpdateRoleCommand(String name, String description, List<String> permissions) {
    }

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final MembershipRoleRepository membershipRoleRepository;
    private final OrganizationRepository organizationRepository;
    private final OutboxWriter outboxWriter;
    private final TenantTransactionRunner tenantRunner;

    public RolesService(RoleRepository roleRepository,
                        PermissionRepository permissionRepository,
                        MembershipRoleRepository membershipRoleRepository,
                        OrganizationRepository organizationRepository,
                        OutboxWriter outboxWriter,
                        TenantTransactionRunner tenantRunner) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.membershipRoleRepository = membershipRoleRepository;
        this.organizationRepository = organizationRepository;
        this.outboxWriter = outboxWriter;
        this.tenantRunner = tenantRunner;
    }

    // ---- Queries ----

    public List<RoleView> list(UUID tenantId) {
        return tenantRunner.run(tenantId, () ->
                roleRepository.findByOrganizationId(tenantId).stream()
                        .map(this::viewOf)
                        .toList());
    }

    /** The platform catalog (permission is platform-scoped, no RLS). */
    public List<PermissionView> catalog() {
        return permissionRepository.findAll().stream()
                .map(permission -> new PermissionView(
                        permission.getCode(), permission.getName(), permission.getDescription()))
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .toList();
    }

    // ---- Mutations (custom roles only) ----

    @Transactional
    public RoleView create(UUID tenantId, CreateRoleCommand command) {
        String name = command.name() == null ? "" : command.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("El nombre del rol es requerido");
        }
        List<String> codes = command.permissions() == null ? List.of() : command.permissions();
        return tenantRunner.run(tenantId, () -> {
            boolean nameTaken = roleRepository.findByOrganizationId(tenantId).stream()
                    .anyMatch(role -> role.getName().equalsIgnoreCase(name));
            if (nameTaken) {
                throw new ConflictException("ROLE_NAME_TAKEN",
                        "Ya existe un rol con ese nombre en la organización");
            }
            Set<Permission> permissions = resolvePermissions(codes);
            Organization organization = organizationRepository.findById(tenantId)
                    .orElseThrow(() -> new NotFoundException("organization", tenantId.toString()));
            Role role = new Role(organization, name, descriptionOf(command.description()));
            permissions.forEach(role::grant);
            roleRepository.save(role);

            outboxWriter.append(tenantId, "identity.role.created", "role", role.getId(),
                    Map.of("name", name, "permissions", codes));

            return viewOf(role);
        });
    }

    @Transactional
    public RoleView update(UUID tenantId, UUID roleId, UpdateRoleCommand command) {
        return tenantRunner.run(tenantId, () -> {
            Role role = requireRole(roleId);
            if (role.isSystem()) {
                throw new ConflictException("SYSTEM_ROLE_IMMUTABLE",
                        "Los roles del sistema son inmutables");
            }
            if (command.name() != null && !command.name().isBlank()) {
                String name = command.name().trim();
                boolean nameTaken = roleRepository.findByOrganizationId(tenantId).stream()
                        .anyMatch(other -> !other.getId().equals(roleId)
                                && other.getName().equalsIgnoreCase(name));
                if (nameTaken) {
                    throw new ConflictException("ROLE_NAME_TAKEN",
                            "Ya existe un rol con ese nombre en la organización");
                }
                role.rename(name, descriptionOf(command.description()));
            } else if (command.description() != null) {
                role.rename(role.getName(), descriptionOf(command.description()));
            }
            if (command.permissions() != null) {
                role.setPermissions(resolvePermissions(command.permissions()));
            }
            roleRepository.save(role);

            outboxWriter.append(tenantId, "identity.role.updated", "role", roleId,
                    Map.of("name", role.getName()));

            return viewOf(role);
        });
    }

    @Transactional
    public void delete(UUID tenantId, UUID roleId) {
        tenantRunner.run(tenantId, () -> {
            Role role = requireRole(roleId);
            if (role.isSystem()) {
                throw new ConflictException("SYSTEM_ROLE_IMMUTABLE",
                        "Los roles del sistema no se pueden borrar");
            }
            boolean inUse = membershipRoleRepository.findByTenantId(tenantId).stream()
                    .anyMatch(link -> link.getRole().getId().equals(roleId));
            if (inUse) {
                throw new ConflictException("ROLE_IN_USE",
                        "El rol está asignado a algún member; quítalo primero");
            }
            roleRepository.delete(role);

            outboxWriter.append(tenantId, "identity.role.deleted", "role", roleId,
                    Map.of("name", role.getName()));
            return null;
        });
    }

    // ---- Helpers ----

    private RoleView viewOf(Role role) {
        return new RoleView(
                role.getId(),
                role.getName(),
                role.getDescription(),
                role.isSystem(),
                role.getPermissions().stream()
                        .map(Permission::getCode)
                        .sorted()
                        .toList());
    }

    private Role requireRole(UUID roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("role", roleId.toString()));
    }

    /** Only SEEDED codes (fail-closed): an invented code is a client error. */
    private Set<Permission> resolvePermissions(List<String> codes) {
        Set<String> wanted = new LinkedHashSet<>(codes);
        List<Permission> found = permissionRepository.findByCodeIn(wanted);
        if (found.size() != wanted.size()) {
            throw new IllegalArgumentException(
                    "El catálogo no contiene todos los permisos solicitados");
        }
        return new LinkedHashSet<>(found);
    }

    private String descriptionOf(String description) {
        return description == null ? null : description.trim();
    }
}
