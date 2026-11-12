package com.posplatform.modules.inventory;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lot + expiry level of stock for one (warehouse, variant, lot_code) key.
 * Populated when the tenant's vertical tracks LOT/EXPIRY (ADR-010); sales
 * consume it FEFO (first expired, first out). Same-tenant FKs are composite.
 */
@Entity
@Table(name = "inventory_lot")
public class InventoryLot extends Auditable {

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "lot_code", nullable = false, length = 64)
    private String lotCode;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    protected InventoryLot() {
    }

    public InventoryLot(UUID tenantId, UUID warehouseId, UUID variantId,
                        String lotCode, LocalDate expiryDate, BigDecimal quantity) {
        this.tenantId = tenantId;
        this.warehouseId = warehouseId;
        this.variantId = variantId;
        this.lotCode = lotCode;
        this.expiryDate = expiryDate;
        this.quantity = quantity;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getVariantId() { return variantId; }
    public String getLotCode() { return lotCode; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public BigDecimal getQuantity() { return quantity; }
}
