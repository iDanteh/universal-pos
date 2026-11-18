package com.posplatform.modules.identity.application;

import com.posplatform.shared.api.ForbiddenException;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantContext;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * RUNTIME PERMISSION ENFORCEMENT (v0.10 · ADR-020) — the door every business
 * endpoint goes through.
 *
 * ANY-OF semantics: the caller passes the permission codes that satisfy the
 * operation; the guard resolves the session user's ACTIVE membership grants
 * with ONE query INSIDE the tenant's RLS scope:
 *
 *   membership (ACTIVE) -> membership_role -> role -> role_permission -> permission
 *
 * The DB decides, exactly like tenant resolution (ADR-011): a PENDING or
 * REVOKED member cannot even see their own grants, because the query runs
 * under the tenant GUC and RLS filters everything the grant chain needs.
 *
 * Fail-closed by construction: no tenant bound (an unverified/missing tenant)
 * fails with the DOCUMENTED 404 contract of every module ("a tenant-less
 * request fails with 404 — never a guess"); a bound tenant without the
 * required grants ends in 403 PERMISSION_DENIED. The UI gating (frontend)
 * is courtesy; this guard is the law.
 */
@Component
public class PermissionGuard {

    private final TenantTransactionRunner tenantRunner;
    private final JdbcTemplate jdbc;

    public PermissionGuard(TenantTransactionRunner tenantRunner, JdbcTemplate jdbc) {
        this.tenantRunner = tenantRunner;
        this.jdbc = jdbc;
    }

    /**
     * Requires the current request's user to hold ANY of the given permission
     * codes through an ACTIVE membership of the current tenant.
     */
    public void require(String... codes) {
        if (codes == null || codes.length == 0) {
            throw new IllegalArgumentException("PermissionGuard.require necesita al menos un código");
        }
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new NotFoundException("organization", "current"));
        UUID userId = TenantContext.currentUserId()
                .orElseThrow(() -> new ForbiddenException("PERMISSION_DENIED",
                        "La sesión no tiene un usuario verificado"));

        Set<String> wanted = new LinkedHashSet<>(Arrays.asList(codes));
        Set<String> granted = tenantRunner.run(tenantId, () ->
                new LinkedHashSet<>(jdbc.queryForList("""
                        SELECT DISTINCT p.code
                        FROM membership m
                        JOIN membership_role mr ON mr.membership_id = m.id
                                               AND mr.tenant_id = m.tenant_id
                        JOIN role r ON r.id = mr.role_id
                                   AND r.tenant_id = mr.tenant_id
                        JOIN role_permission rp ON rp.role_id = r.id
                        JOIN permission p ON p.id = rp.permission_id
                        WHERE m.tenant_id = ?
                          AND m.user_id = ?
                          AND m.status = 'ACTIVE'
                        """, String.class, tenantId, userId)));

        for (String code : wanted) {
            if (granted.contains(code)) {
                return;
            }
        }
        throw new ForbiddenException("PERMISSION_DENIED",
                "No tienes permisos para esta operación");
    }

    /**
     * The caller's permission codes for the current tenant (no check, just the
     * resolved grant set — used by GET /auth/permissions for UI gating).
     */
    public Set<String> permissionsOfCurrentUser(UUID tenantId, UUID userId) {
        return tenantRunner.run(tenantId, () ->
                new LinkedHashSet<>(jdbc.queryForList("""
                        SELECT DISTINCT p.code
                        FROM membership m
                        JOIN membership_role mr ON mr.membership_id = m.id
                                               AND mr.tenant_id = m.tenant_id
                        JOIN role r ON r.id = mr.role_id
                                   AND r.tenant_id = mr.tenant_id
                        JOIN role_permission rp ON rp.role_id = r.id
                        JOIN permission p ON p.id = rp.permission_id
                        WHERE m.tenant_id = ?
                          AND m.user_id = ?
                          AND m.status = 'ACTIVE'
                        """, String.class, tenantId, userId)));
    }
}
