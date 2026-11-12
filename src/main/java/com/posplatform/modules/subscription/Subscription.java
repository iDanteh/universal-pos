package com.posplatform.modules.subscription;

import com.posplatform.modules.organization.Organization;
import com.posplatform.shared.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import java.time.Instant;
import java.util.UUID;

/**
 * Plan contracted by an organization. Tenant-scoped.
 * Only one ACTIVE/TRIAL subscription per organization (partial unique index).
 */
@Entity
@Table(name = "subscription")
public class Subscription extends Auditable {

    public enum Status { TRIAL, ACTIVE, PAST_DUE, CANCELLED, EXPIRED }

    @Id
    @UuidGenerator(style = Style.TIME)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Subscription() {
    }

    public Subscription(Organization organization, Plan plan, Status status,
                        Instant startedAt, Instant currentPeriodStart, Instant currentPeriodEnd) {
        this.organization = organization;
        this.tenantId = organization.getId();
        this.plan = plan;
        this.status = status;
        this.startedAt = startedAt;
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Organization getOrganization() { return organization; }
    public Plan getPlan() { return plan; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }

    public void cancel(Instant at) {
        if (this.status == Status.CANCELLED) {
            throw new IllegalStateException("subscription already cancelled");
        }
        this.status = Status.CANCELLED;
        this.cancelledAt = at;
    }
}
