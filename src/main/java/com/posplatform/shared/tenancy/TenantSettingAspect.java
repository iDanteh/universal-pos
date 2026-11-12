package com.posplatform.shared.tenancy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Binds {@link TenantContext} to the current PostgreSQL transaction whenever a
 * method annotated with {@link TenantScoped} executes.
 *
 * Ordering contract (see TxConfig): transaction interceptor (order 100) runs
 * FIRST and opens the transaction; this aspect (order 200) runs INSIDE it and
 * executes {@code set_config(..., true)} - transaction scoped - so HikariCP
 * connection reuse can never leak tenant state between transactions.
 *
 * Fail-closed behavior:
 *  - no tenant in context  -> refuse execution;
 *  - no active transaction -> refuse execution (SET LOCAL would be meaningless).
 */
@Aspect
@Component
@Order(200)
public class TenantSettingAspect {

    private static final String BIND_TENANT_SQL =
            "SELECT set_config('app.tenant_id', ?, true)";

    private final JdbcTemplate jdbcTemplate;

    public TenantSettingAspect(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Around("@annotation(TenantScoped)")
    public Object bindTenantToTransaction(ProceedingJoinPoint joinPoint) throws Throwable {
        UUID tenantId = TenantContext.requireTenantId();

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "@TenantScoped requires an active transaction; add @Transactional to '" +
                            joinPoint.getSignature().toShortString() + "'");
        }

        jdbcTemplate.queryForObject(BIND_TENANT_SQL, String.class, tenantId.toString());
        return joinPoint.proceed();
    }
}
