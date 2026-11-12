package com.posplatform.modules.inventory.infrastructure;

import com.posplatform.modules.inventory.InventoryLot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InventoryLotRepository extends JpaRepository<InventoryLot, UUID> {
}
