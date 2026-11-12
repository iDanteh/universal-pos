package com.posplatform.modules.inventory.infrastructure;

import com.posplatform.modules.inventory.InventoryBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {

    List<InventoryBalance> findByTenantIdAndWarehouseIdOrderByVariantId(
            UUID tenantId, UUID warehouseId);
}
