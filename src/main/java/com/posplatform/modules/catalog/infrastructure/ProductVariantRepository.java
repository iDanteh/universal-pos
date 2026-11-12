package com.posplatform.modules.catalog.infrastructure;

import com.posplatform.modules.catalog.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    List<ProductVariant> findByTenantId(UUID tenantId);

    /** SKU unique per tenant (V10 unique index mirrors this rule). */
    boolean existsByTenantIdAndSkuIgnoreCase(UUID tenantId, String sku);
}
