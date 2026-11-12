package com.posplatform.modules.cash;

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
import java.time.Instant;
import java.util.UUID;

/**
 * The drawer (caja) of a tenant (ADR-017): ONE session OPEN per tenant —
 * the partial unique index of V14 is the guard, this entity only mirrors
 * it. Lifecycle machine lives HERE (same rule as the invoice root):
 * OPEN -> CLOSED. Reconciliation is CASH-ONLY: expected_cash is resolved by
 * the service from the attached sales; the counted card/transfer/other
 * amounts are informative (they reconcile against the bank, not the drawer).
 */
@Entity
@Table(name = "cash_session")
public class CashSession extends Auditable {

    public enum Status { OPEN, CLOSED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(name = "opened_by", nullable = false)
    private UUID openedBy;

    @Column(name = "opening_float", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingFloat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.OPEN;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "counted_cash", precision = 14, scale = 2)
    private BigDecimal countedCash;

    @Column(name = "counted_card", precision = 14, scale = 2)
    private BigDecimal countedCard;

    @Column(name = "counted_transfer", precision = 14, scale = 2)
    private BigDecimal countedTransfer;

    @Column(name = "counted_other", precision = 14, scale = 2)
    private BigDecimal countedOther;

    @Column(name = "expected_cash", precision = 14, scale = 2)
    private BigDecimal expectedCash;

    @Column(precision = 14, scale = 2)
    private BigDecimal difference;

    @Column(name = "close_note", length = 280)
    private String closeNote;

    protected CashSession() {
    }

    public CashSession(UUID tenantId, UUID openedBy, BigDecimal openingFloat) {
        this.tenantId = tenantId;
        this.openedBy = openedBy;
        this.openingFloat = openingFloat;
        this.status = Status.OPEN;
        this.openedAt = Instant.now();
    }

    /**
     * Closing transition: only happens once (a second close is a 409 in the
     * service). The reconciliation numbers arrive already resolved.
     */
    public void close(BigDecimal countedCash, BigDecimal countedCard,
                      BigDecimal countedTransfer, BigDecimal countedOther,
                      BigDecimal expectedCash, BigDecimal difference, String note) {
        this.status = Status.CLOSED;
        this.closedAt = Instant.now();
        this.countedCash = countedCash;
        this.countedCard = countedCard;
        this.countedTransfer = countedTransfer;
        this.countedOther = countedOther;
        this.expectedCash = expectedCash;
        this.difference = difference;
        this.closeNote = note;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getOpenedBy() { return openedBy; }
    public BigDecimal getOpeningFloat() { return openingFloat; }
    public Status getStatus() { return status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public BigDecimal getCountedCash() { return countedCash; }
    public BigDecimal getCountedCard() { return countedCard; }
    public BigDecimal getCountedTransfer() { return countedTransfer; }
    public BigDecimal getCountedOther() { return countedOther; }
    public BigDecimal getExpectedCash() { return expectedCash; }
    public BigDecimal getDifference() { return difference; }
    public String getCloseNote() { return closeNote; }
}
