package com.posplatform.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Kernel component the domain modules use to emit events.
 * Callers MUST be inside the transaction that performs the business change.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void append(UUID tenantId, String eventType, String aggregateType,
                       UUID aggregateId, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Outbox payload serialization failed", ex);
        }
        repository.save(new OutboxEvent(tenantId, eventType, aggregateType, aggregateId, json));
    }
}
