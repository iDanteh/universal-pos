package com.posplatform.shared.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox row (schema in place; publisher in a later release).
 *
 * Domain events are written to this table in the SAME transaction as the
 * business change. A relay process (future) will publish them exactly once
 * rather than publishing from inside the transaction (which risks losing or
 * duplicating events on rollback).
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID tenantId;                 // null = platform-level event

    @Column(nullable = false, length = 120)
    private String eventType;

    @Column(nullable = false, length = 120)
    private String aggregateType;

    private UUID aggregateId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    private Instant processedAt;           // null until the relay consumes it

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID tenantId, String eventType, String aggregateType,
                       UUID aggregateId, String payload) {
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getProcessedAt() { return processedAt; }
}
