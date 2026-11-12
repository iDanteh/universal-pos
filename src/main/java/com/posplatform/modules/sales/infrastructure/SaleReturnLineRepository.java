package com.posplatform.modules.sales.infrastructure;

import com.posplatform.modules.sales.SaleReturnLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface SaleReturnLineRepository extends JpaRepository<SaleReturnLine, UUID> {

    List<SaleReturnLine> findByTenantIdAndSaleReturn_IdOrderByCreatedAtAsc(UUID tenantId, UUID returnId);

    /** Quantity of a sale line ALREADY returned (guards returnable stock). */
    @Query("""
            SELECT COALESCE(SUM(line.quantity), 0)
            FROM SaleReturnLine line
            WHERE line.tenantId = :tenantId
              AND line.saleLineId = :saleLineId
            """)
    BigDecimal sumReturnedQuantity(@Param("tenantId") UUID tenantId,
                                   @Param("saleLineId") UUID saleLineId);
}
