package com.posplatform.shared.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped tenant context.
 *
 * INVARIANT (security-critical):
 *   The tenant id in this context is resolved ONLY from verified application
 *   state (authenticated principal + confirmed Membership of that user in that
 *   organization). It is NEVER taken from client-supplied input such as a
 *   header, query parameter or JSON body. The HTTP request path populates it
 *   in SessionAuthFilter (ADR-011): identity mirror + LIVE Membership read
 *   INSIDE the tenant's RLS scope. It is also set programmatically by trusted
 *   services/tests and validated against RLS.
 *
 * PostgreSQL equivalent: {@code current_setting('app.tenant_id', true)}
 * is bound per transaction from this value (see TenantSettingAspect).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> currentTenantId() {
        return Optional.ofNullable(TENANT_ID.get());
    }

    /** @return the tenant id, or throws if no tenant is bound (fail closed). */
    public static UUID requireTenantId() {
        UUID id = TENANT_ID.get();
        if (id == null) {
            throw new TenantContextMissingException();
        }
        return id;
    }

    public static void setTenantId(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenant id must not be null");
        }
        TENANT_ID.set(tenantId);
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}
