package com.posplatform.modules.inventory;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Aggregate quantity per (warehouse, variant) — ADR-004 rule 2: updated in
 * the SAME transaction as the movement. Deliberately NO check constraint on
 * quantity: negative stock is a per-vertical POLICY (FORBIDDEN /
 * ALLOWED_WITH_ALERT / ALLOWED) enforced by the service, not by the schema.
 */
@Entity
@Table(name = "inventory_balance")
public class InventoryBalance extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    protected InventoryBalance() {
    }

    public InventoryBalance(UUID tenantId, UUID warehouseId, UUID variantId, BigDecimal quantity) {
        this.tenantId = tenantId;
        this.warehouseId = warehouseId;
        this.variantId = variantId;
        this.quantity = quantity;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getVariantId() { return variantId; }
    public BigDecimal getQuantity() { return quantity; }
}
