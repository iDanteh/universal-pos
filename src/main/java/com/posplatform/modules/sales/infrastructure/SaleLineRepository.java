package com.posplatform.modules.sales.infrastructure;

import com.posplatform.modules.sales.SaleLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SaleLineRepository extends JpaRepository<SaleLine, UUID> {

    List<SaleLine> findByTenantIdAndSaleIdOrderByCreatedAtAsc(UUID tenantId, UUID saleId);
}
