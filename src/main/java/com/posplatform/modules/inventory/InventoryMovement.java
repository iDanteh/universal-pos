package com.posplatform.modules.inventory;

import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * IMMUTABLE stock movement (ADR-004 rule 1): every entry records direction,
 * cause, quantity and — when the vertical tracks lots (ADR-010) — the lot and
 * expiry it applies to. Corrections NEVER update this row: they are new
 * compensatory movements linked through related_movement_id (e.g. a sale
 * cancel returns stock with cause SALE_RETURN pointing at the SALE out).
 * Same-tenant FKs are composite (warehouse/variant/related movement, tenant).
 */
@Entity
@Table(name = "inventory_movement")
public class InventoryMovement extends Auditable {

    public enum Direction { IN, OUT }

    public enum Cause {
        PURCHASE, ADJUST, SALE, SALE_RETURN, TRANSFER_IN, TRANSFER_OUT
    }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Cause cause;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "lot_code", length = 64)
    private String lotCode;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(length = 120)
    private String reference;

    @Column(length = 280)
    private String note;

    @Column(name = "related_movement_id")
    private UUID relatedMovementId;

    protected InventoryMovement() {
    }

    public InventoryMovement(UUID tenantId, UUID warehouseId, UUID variantId,
                             Direction direction, Cause cause, BigDecimal quantity,
                             String lotCode, LocalDate expiryDate,
                             String reference, String note, UUID relatedMovementId) {
        this.tenantId = tenantId;
        this.warehouseId = warehouseId;
        this.variantId = variantId;
        this.direction = direction;
        this.cause = cause;
        this.quantity = quantity;
        this.lotCode = lotCode;
        this.expiryDate = expiryDate;
        this.reference = reference;
        this.note = note;
        this.relatedMovementId = relatedMovementId;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getVariantId() { return variantId; }
    public Direction getDirection() { return direction; }
    public Cause getCause() { return cause; }
    public BigDecimal getQuantity() { return quantity; }
    public String getLotCode() { return lotCode; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public String getReference() { return reference; }
    public String getNote() { return note; }
    public UUID getRelatedMovementId() { return relatedMovementId; }
}
