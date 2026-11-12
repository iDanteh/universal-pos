package com.posplatform.modules.sales.infrastructure;

import com.posplatform.modules.sales.SaleReturn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaleReturnRepository extends JpaRepository<SaleReturn, UUID> {

    Optional<SaleReturn> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<SaleReturn> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    List<SaleReturn> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<SaleReturn> findByTenantIdAndSale_IdOrderByCreatedAtDesc(UUID tenantId, UUID saleId);
}
