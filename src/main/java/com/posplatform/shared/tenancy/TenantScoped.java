package com.posplatform.shared.tenancy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an application-service method as tenant-scoped.
 *
 * When the method runs inside a transaction, the tenant from
 * {@link TenantContext} is bound to the CURRENT transaction via
 * {@code SELECT set_config('app.tenant_id', ?, true)} (SET LOCAL semantics).
 * PostgreSQL Row Level Security policies then enforce isolation at the
 * database layer, independent of application code correctness.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantScoped {
}
