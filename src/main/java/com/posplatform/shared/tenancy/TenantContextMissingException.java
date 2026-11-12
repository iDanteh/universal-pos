package com.posplatform.shared.tenancy;

/**
 * Raised when tenant-scoped code is executed without a resolved tenant.
 * Mapping to HTTP: 500 (programming/configuration error, fail closed).
 */
public class TenantContextMissingException extends RuntimeException {

    public TenantContextMissingException() {
        super("Tenant context is required but no tenant has been resolved for the current operation");
    }
}
