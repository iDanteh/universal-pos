package com.posplatform.modules.inventory.infrastructure;

import com.posplatform.modules.inventory.InventoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {

    /** Recent-first listing, optionally scoped to a warehouse and/or a variant. */
    @Query("""
            SELECT m FROM InventoryMovement m
            WHERE m.tenantId = :tenantId
              AND (:warehouseId IS NULL OR m.warehouseId = :warehouseId)
              AND (:variantId IS NULL OR m.variantId = :variantId)
            ORDER BY m.createdAt DESC
            LIMIT 50
            """)
    List<InventoryMovement> findRecent(@Param("tenantId") UUID tenantId,
                                       @Param("warehouseId") UUID warehouseId,
                                       @Param("variantId") UUID variantId);

    /** Movements that make up a sale's stock consumption (used to compensate on cancel). */
    List<InventoryMovement> findByTenantIdAndReferenceAndCauseOrderByIdAsc(
            UUID tenantId, String reference, InventoryMovement.Cause cause);
}
