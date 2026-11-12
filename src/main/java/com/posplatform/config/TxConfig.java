package com.posplatform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Explicit transaction interceptor ordering.
 *
 * Row Level Security requires the tenant GUC to be bound to the CURRENT
 * transaction via SET LOCAL. To make that deterministic:
 *
 *  - Transaction interceptor runs at order 100 (outer, transaction begins first).
 *  - Tenant-binding aspect runs at order 200 (inner, runs INSIDE the transaction).
 *
 * Aspect ordering in Spring resolves ascending: lower order value = earlier =
 * outer. See TenantSettingAspect and docs/architecture/multi-tenant-security.md.
 */
@Configuration
@EnableTransactionManagement(order = 100)
public class TxConfig {
}
