package com.posplatform.shared;

import com.posplatform.shared.tenancy.TenantContext;
import com.posplatform.shared.tenancy.TenantContextMissingException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class TenantContextTest {

    @Test
    void setAndRequireRoundTrip() {
        var tenantId = UUID.randomUUID();
        try {
            TenantContext.setTenantId(tenantId);
            assertThat(TenantContext.currentTenantId()).contains(tenantId);
            assertThat(TenantContext.requireTenantId()).isEqualTo(tenantId);
        } finally {
            TenantContext.clear();
        }
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void requireFailsClosedWhenUnset() {
        Throwable thrown = catchThrowable(TenantContext::requireTenantId);
        assertThat(thrown).isInstanceOf(TenantContextMissingException.class);
    }

    @Test
    void rejectsNullTenant() {
        assertThatThrownBy(() -> TenantContext.setTenantId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
