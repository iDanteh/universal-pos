package com.posplatform.modules.sales;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Per-tenant folio counter. The next folio comes from an atomic
 * `INSERT ... ON CONFLICT (tenant_id) DO UPDATE SET last = +1 RETURNING last`
 * executed inside the sale's tenant transaction — no lost updates, gaps only
 * on rollback (documented in sales-payments.md). RLS-scoped like every table.
 */
@Entity
@Table(name = "sale_folio")
public class SaleFolio {

    @Id
    private UUID tenantId;

    private long last;

    protected SaleFolio() {
    }

    public UUID getTenantId() { return tenantId; }
    public long getLast() { return last; }
}
