package com.posplatform.modules.identity.api;

import com.posplatform.modules.identity.application.PermissionGuard;
import com.posplatform.modules.identity.application.RolesService;
import com.posplatform.modules.identity.application.RolesService.CreateRoleCommand;
import com.posplatform.modules.identity.application.RolesService.PermissionView;
import com.posplatform.modules.identity.application.RolesService.RoleView;
import com.posplatform.modules.identity.application.RolesService.UpdateRoleCommand;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * ROLE & PERMISSION REST surface (v0.10 · ADR-020). Writes require
 * roles.manage; reads admit users.manage OR roles.manage (any-of). System
 * roles are immutable server-side (409 SYSTEM_ROLE_IMMUTABLE).
 */
@RestController
@RequestMapping("/api/v1")
public class RolesController {

    private final RolesService rolesService;
    private final PermissionGuard permissionGuard;

    public RolesController(RolesService rolesService, PermissionGuard permissionGuard) {
        this.rolesService = rolesService;
        this.permissionGuard = permissionGuard;
    }

    // ---- Requests ----

    public record RoleRequest(
            @Size(min = 1, max = 80) String name,
            @Size(max = 255) String description,
            List<String> permissions) {
    }

    // ---- Use cases ----

    @GetMapping("/roles")
    public List<RoleView> list() {
        UUID tenantId = currentTenant();
        permissionGuard.require("users.manage", "roles.manage");
        return rolesService.list(tenantId);
    }

    @GetMapping("/permissions")
    public List<PermissionView> catalog() {
        permissionGuard.require("users.manage", "roles.manage");
        return rolesService.catalog();
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleView> create(@org.springframework.validation.annotation.Validated
                                           @RequestBody RoleRequest request) {
        UUID tenantId = currentTenant();
        permissionGuard.require("roles.manage");
        RoleView view = rolesService.create(tenantId, new CreateRoleCommand(
                request.name(), request.description(), request.permissions()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PatchMapping("/roles/{roleId}")
    public RoleView update(@PathVariable UUID roleId, @RequestBody RoleRequest request) {
        UUID tenantId = currentTenant();
        permissionGuard.require("roles.manage");
        return rolesService.update(tenantId, roleId, new UpdateRoleCommand(
                request.name(), request.description(), request.permissions()));
    }

    @DeleteMapping("/roles/{roleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID roleId) {
        UUID tenantId = currentTenant();
        permissionGuard.require("roles.manage");
        rolesService.delete(tenantId, roleId);
        return ResponseEntity.noContent().build();
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }
}
