package com.posplatform.modules.sales.infrastructure;

import com.posplatform.modules.sales.SalePayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalePaymentRepository extends JpaRepository<SalePayment, UUID> {

    List<SalePayment> findByTenantIdAndSaleIdOrderByCreatedAtAsc(UUID tenantId, UUID saleId);
}
