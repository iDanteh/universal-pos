package com.posplatform.shared.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs a block of work inside one transaction with a tenant explicitly bound
 * (SET LOCAL). Used by trusted services and tests; the production auth layer
 * will use it after resolving the tenant from memberships.
 */
@Component
public class TenantTransactionRunner {

    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public TenantTransactionRunner(TransactionTemplate transactionTemplate, JdbcTemplate jdbcTemplate) {
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    public <T> T run(UUID tenantId, Supplier<T> action) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return transactionTemplate.execute(status -> {
            bind(tenantId);
            return action.get();
        });
    }

    public void run(UUID tenantId, Runnable action) {
        run(tenantId, () -> {
            action.run();
            return null;
        });
    }

    private void bind(UUID tenantId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true)",
                String.class,
                tenantId.toString());
    }
}
