package com.posplatform.modules.identity.api;

import com.posplatform.modules.identity.application.UsersService;
import com.posplatform.modules.identity.application.UsersService.InviteCommand;
import com.posplatform.modules.identity.application.UsersService.InviteOutcome;
import com.posplatform.modules.identity.application.UsersService.MemberView;
import com.posplatform.modules.identity.application.PermissionGuard;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * MEMBER management REST surface (v0.10 · ADR-020). Every route requires
 * users.manage (fail-closed PermissionGuard). The invitation token is
 * returned ONCE in the 201/200 response — foundation has no SMTP; the admin
 * shares it by their own channel.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    private final UsersService usersService;
    private final PermissionGuard permissionGuard;

    public UsersController(UsersService usersService, PermissionGuard permissionGuard) {
        this.usersService = usersService;
        this.permissionGuard = permissionGuard;
    }

    // ---- Requests ----

    public record InviteRequest(
            @NotBlank @Email String email,
            @Size(max = 120) String displayName,
            @NotEmpty List<UUID> roleIds) {
    }

    public record ChangeRolesRequest(@NotEmpty List<UUID> roleIds) {
    }

    // ---- Use cases ----

    @GetMapping
    public List<MemberView> list() {
        UUID tenantId = currentTenant();
        permissionGuard.require("users.manage");
        return usersService.list(tenantId);
    }

    @PostMapping("/invitations")
    public ResponseEntity<InviteOutcome> invite(@Valid @RequestBody InviteRequest request) {
        UUID tenantId = currentTenant();
        permissionGuard.require("users.manage");
        InviteOutcome outcome = usersService.invite(tenantId, currentUserId(),
                new InviteCommand(request.email(), request.displayName(), request.roleIds()));
        return ResponseEntity.status(HttpStatus.CREATED).body(outcome);
    }

    @PostMapping("/invitations/{membershipId}/resend")
    public InviteOutcome resend(@PathVariable UUID membershipId) {
        UUID tenantId = currentTenant();
        permissionGuard.require("users.manage");
        return usersService.resend(tenantId, membershipId, currentUserId());
    }

    @PatchMapping("/{membershipId}")
    public MemberView changeRoles(@PathVariable UUID membershipId,
                                  @Valid @RequestBody ChangeRolesRequest request) {
        UUID tenantId = currentTenant();
        permissionGuard.require("users.manage");
        return usersService.changeRoles(tenantId, membershipId, request.roleIds());
    }

    @PostMapping("/{membershipId}/revoke")
    public MemberView revoke(@PathVariable UUID membershipId) {
        UUID tenantId = currentTenant();
        permissionGuard.require("users.manage");
        return usersService.revoke(tenantId, membershipId);
    }

    private UUID currentTenant() {
        return TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
    }

    private UUID currentUserId() {
        return TenantContext.currentUserId()
                .orElseThrow(() -> new NotFoundException("user", "current"));
    }
}
