package com.posplatform.modules.sales.infrastructure;

import com.posplatform.modules.sales.Sale;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaleRepository extends JpaRepository<Sale, UUID> {

    /** Recent sales first (V11 idx_sale_tenant_created). */
    List<Sale> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Sale> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<Sale> findByTenantIdAndId(UUID tenantId, UUID id);
}
