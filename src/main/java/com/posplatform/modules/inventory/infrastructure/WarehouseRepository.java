package com.posplatform.modules.inventory.infrastructure;

import com.posplatform.modules.inventory.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    List<Warehouse> findByTenantIdOrderById(UUID tenantId);

    List<Warehouse> findByTenantIdAndActiveTrueOrderById(UUID tenantId);

    boolean existsByTenantIdAndCodeIgnoreCase(UUID tenantId, String code);

    Optional<Warehouse> findByTenantIdAndId(UUID tenantId, UUID id);
}
