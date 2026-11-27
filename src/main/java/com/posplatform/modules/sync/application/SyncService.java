package com.posplatform.modules.sync.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.posplatform.shared.api.ConflictException;
import com.posplatform.shared.api.InsufficientStockException;
import com.posplatform.shared.api.NotFoundException;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import com.posplatform.shared.domain.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * OFFLINE-FIRST SYNC (fase 14 · ADR-024): devices, batches and intent results.
 *
 * The POS never replicates tables: it queues INTENTS locally and delivers them
 * in ordered batches. This service owns the server ledger:
 *
 *  - a device is registered once per tenant code (PUT is idempotent);
 *  - a batch is replay-safe by (tenant, clientBatchId): the POS may retry the
 *    SAME HTTP call after a timeout and get the stored answer;
 *  - an intent is unique by (tenant, operationId): re-sending it NEVER applies
 *    it twice — the response marks it as a duplicate with the original result;
 *  - each intent is applied in its own transaction (see
 *    {@link SyncOperationApplier}); one rejected sale does not poison the
 *    others, and the batch reports APPLIED / PARTIAL / REJECTED honestly.
 *
 * The batch header and its PENDING rows commit BEFORE the intents are applied
 * so every operation can run in REQUIRES_NEW and update its own row.
 */
@Service
public class SyncService {

    private static final int MAX_OPERATIONS = 50;

    private final JdbcTemplate jdbc;
    private final TenantTransactionRunner tenantRunner;
    private final SyncOperationApplier applier;
    private final ObjectMapper objectMapper;

    public SyncService(JdbcTemplate jdbc,
                       TenantTransactionRunner tenantRunner,
                       SyncOperationApplier applier,
                       ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tenantRunner = tenantRunner;
        this.applier = applier;
        this.objectMapper = objectMapper;
    }

    // ---- Views ----

    public record DeviceView(UUID id, String code, String name,
                             String registeredAt, String lastSeenAt) {
    }

    public record OperationView(UUID operationId, String type, String status,
                                boolean duplicate, String errorCode, String errorDetail,
                                JsonNode result, String occurredAt, String appliedAt) {
    }

    public record BatchView(UUID id, UUID clientBatchId, UUID deviceId, String status,
                            int operationCount, int appliedCount, int rejectedCount,
                            int duplicateCount, String receivedAt, String ackedAt,
                            List<OperationView> operations) {
    }

    public record BatchOutcome(BatchView batch, boolean replay) {
    }

    // ---- Commands ----

    public record OperationCommand(UUID operationId, String type, JsonNode payload,
                                   String occurredAt) {
    }

    public record ApplyBatchCommand(UUID clientBatchId, UUID deviceId,
                                    List<OperationCommand> operations) {
    }

    private record DeviceRow(UUID id, UUID tenantId, String code, String name,
                             Instant registeredAt, Instant lastSeenAt) {
    }

    private record BatchRow(UUID id, UUID clientBatchId, UUID deviceId, String status,
                            int operationCount, int appliedCount, int rejectedCount,
                            int duplicateCount, Instant receivedAt, Instant ackedAt) {
    }

    private record OperationRow(UUID id, UUID operationId, String type, String status,
                                String errorCode, String errorDetail, String result,
                                Instant occurredAt, Instant appliedAt) {
    }

    private record PendingOperation(UUID rowId, UUID operationId, String type,
                                    String payload, Instant occurredAt) {
    }

    private record Prepared(UUID batchId, List<PendingOperation> pending,
                            List<OperationView> duplicates, Optional<BatchView> replay) {
    }

    // ---- Devices ----

    public DeviceView upsertDevice(UUID tenantId, String code, String name) {
        if (code == null || code.isBlank() || code.length() > 60) {
            throw new IllegalArgumentException("El código del dispositivo es requerido (máx. 60)");
        }
        if (!code.matches("[A-Za-z0-9._-]{1,60}")) {
            throw new IllegalArgumentException(
                    "El código del dispositivo solo admite letras, números, punto, guion y guion bajo");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("El nombre del dispositivo es requerido (máx. 120)");
        }
        return tenantRunner.run(tenantId, () -> {
            DeviceRow row = jdbc.queryForObject("""
                            INSERT INTO pos_device (id, tenant_id, code, name)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name
                            RETURNING id, tenant_id, code, name, registered_at, last_seen_at
                            """,
                    deviceMapper(), UuidV7.next(), tenantId, code.trim(), name.trim());
            return deviceView(row);
        });
    }

    public List<DeviceView> listDevices(UUID tenantId) {
        return tenantRunner.run(tenantId, () -> jdbc.query("""
                        SELECT id, tenant_id, code, name, registered_at, last_seen_at
                        FROM pos_device
                        WHERE tenant_id = ?
                        ORDER BY registered_at, code
                        """,
                deviceMapper(), tenantId).stream().map(SyncService::deviceView).toList());
    }

    // ---- Batches ----

    public BatchOutcome applyBatch(UUID tenantId, UUID userId, ApplyBatchCommand command) {
        validate(command);
        Prepared prepared = prepare(tenantId, userId, command);
        if (prepared.replay().isPresent()) {
            return new BatchOutcome(prepared.replay().get(), true);
        }

        for (PendingOperation operation : prepared.pending()) {
            try {
                applier.apply(tenantId, operation.rowId(), operation.operationId(),
                        operation.type(), operation.payload(), operation.occurredAt());
            } catch (RuntimeException cause) {
                markRejected(tenantId, operation.rowId(),
                        classify(cause), detail(cause));
            }
        }

        return new BatchOutcome(finalizeBatch(tenantId, prepared.batchId(),
                prepared.duplicates()), false);
    }

    public List<BatchView> listBatches(UUID tenantId, UUID deviceId) {
        return tenantRunner.run(tenantId, () -> {
            List<BatchRow> rows = deviceId == null
                    ? jdbc.query("""
                            SELECT id, client_batch_id, device_id, status, operation_count,
                                   applied_count, rejected_count, duplicate_count,
                                   received_at, acked_at
                            FROM sync_batch
                            WHERE tenant_id = ?
                            ORDER BY received_at DESC, id DESC
                            LIMIT 50
                            """, batchMapper(), tenantId)
                    : jdbc.query("""
                            SELECT id, client_batch_id, device_id, status, operation_count,
                                   applied_count, rejected_count, duplicate_count,
                                   received_at, acked_at
                            FROM sync_batch
                            WHERE tenant_id = ? AND device_id = ?
                            ORDER BY received_at DESC, id DESC
                            LIMIT 50
                            """, batchMapper(), tenantId, deviceId);
            return rows.stream().map(row -> batchView(row, List.of())).toList();
        });
    }

    public BatchView getBatch(UUID tenantId, UUID batchId) {
        return tenantRunner.run(tenantId, () -> {
            BatchRow row = findBatch(tenantId, batchId)
                    .orElseThrow(() -> new NotFoundException("sync_batch", batchId.toString()));
            return batchView(row, operationsOfBatch(tenantId, batchId));
        });
    }

    // ---- Internals ----

    private void validate(ApplyBatchCommand command) {
        if (command == null || command.clientBatchId() == null) {
            throw new IllegalArgumentException("Falta 'clientBatchId'");
        }
        if (command.deviceId() == null) {
            throw new IllegalArgumentException("Falta 'deviceId'");
        }
        if (command.operations() == null || command.operations().isEmpty()) {
            throw new IllegalArgumentException("El lote requiere al menos una operación");
        }
        if (command.operations().size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("El lote excede el máximo de " + MAX_OPERATIONS + " operaciones");
        }
        for (OperationCommand operation : command.operations()) {
            if (operation == null || operation.operationId() == null) {
                throw new IllegalArgumentException("Cada operación requiere 'operationId'");
            }
            if (operation.type() == null || operation.type().isBlank()) {
                throw new IllegalArgumentException("Cada operación requiere 'type'");
            }
            if (operation.payload() == null || operation.payload().isNull()) {
                throw new IllegalArgumentException("Cada operación requiere 'payload'");
            }
        }
    }

    private Prepared prepare(UUID tenantId, UUID userId, ApplyBatchCommand command) {
        return tenantRunner.run(tenantId, () -> {
            DeviceRow device = findDevice(tenantId, command.deviceId())
                    .orElseThrow(() -> new NotFoundException("pos_device", command.deviceId().toString()));

            Optional<BatchRow> replay = findBatchByClientId(tenantId, command.clientBatchId());
            if (replay.isPresent()) {
                BatchRow row = replay.get();
                return new Prepared(row.id(), List.of(), List.of(),
                        Optional.of(batchView(row, operationsOfBatch(tenantId, row.id()))));
            }

            UUID batchId = UuidV7.next();
            jdbc.update("""
                            INSERT INTO sync_batch
                                (id, tenant_id, device_id, user_id, client_batch_id, status,
                                 operation_count)
                            VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?)
                            """,
                    batchId, tenantId, device.id(), userId, command.clientBatchId(),
                    command.operations().size());
            jdbc.update("UPDATE pos_device SET last_seen_at = now() WHERE tenant_id = ? AND id = ?",
                    tenantId, device.id());

            List<PendingOperation> pending = new ArrayList<>();
            List<OperationView> duplicates = new ArrayList<>();
            for (OperationCommand operation : command.operations()) {
                UUID rowId = UuidV7.next();
                String payload = write(operation.payload());
                Instant occurredAt = parseInstant(operation.occurredAt());
                int inserted = jdbc.update("""
                                INSERT INTO sync_operation
                                    (id, tenant_id, batch_id, operation_id, type, payload,
                                     status, occurred_at, applied_at)
                                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?, now())
                                ON CONFLICT (tenant_id, operation_id) DO NOTHING
                                """,
                        rowId, tenantId, batchId, operation.operationId(), operation.type(),
                        payload, timestamp(occurredAt));
                if (inserted == 1) {
                    pending.add(new PendingOperation(rowId, operation.operationId(),
                            operation.type(), payload, occurredAt));
                } else {
                    OperationRow existing = findOperation(tenantId, operation.operationId())
                            .orElseThrow(() -> new ConflictException("SYNC_OPERATION_IN_FLIGHT",
                                    "La operación " + operation.operationId()
                                            + " está en proceso en otro lote"));
                    duplicates.add(operationView(existing, true));
                }
            }
            return new Prepared(batchId, pending, duplicates, Optional.empty());
        });
    }

    private BatchView finalizeBatch(UUID tenantId, UUID batchId, List<OperationView> duplicates) {
        return tenantRunner.run(tenantId, () -> {
            List<OperationView> rows = operationsOfBatch(tenantId, batchId);
            List<OperationView> all = new ArrayList<>(rows);
            all.addAll(duplicates);

            int applied = (int) all.stream().filter(op -> "APPLIED".equals(op.status())).count();
            int rejected = (int) all.stream().filter(op -> "REJECTED".equals(op.status())).count();
            String status;
            if (applied == 0 && rejected == 0) {
                status = "RECEIVED";
            } else if (rejected == 0 && applied == all.size()) {
                status = "APPLIED";
            } else if (applied == 0) {
                status = "REJECTED";
            } else {
                status = "PARTIAL";
            }

            jdbc.update("""
                            UPDATE sync_batch
                            SET status = ?, applied_count = ?, rejected_count = ?,
                                duplicate_count = ?, acked_at = now()
                            WHERE tenant_id = ? AND id = ?
                            """,
                    status, applied, rejected, duplicates.size(), tenantId, batchId);

            BatchRow row = findBatch(tenantId, batchId)
                    .orElseThrow(() -> new NotFoundException("sync_batch", batchId.toString()));
            return batchView(row, all);
        });
    }

    private void markRejected(UUID tenantId, UUID rowId, String code, String detail) {
        tenantRunner.run(tenantId, () -> {
            jdbc.update("""
                            UPDATE sync_operation
                            SET status = 'REJECTED', error_code = ?, error_detail = ?,
                                result = NULL, applied_at = now()
                            WHERE tenant_id = ? AND id = ?
                            """,
                    code, detail, tenantId, rowId);
            return null;
        });
    }

    private Optional<DeviceRow> findDevice(UUID tenantId, UUID deviceId) {
        return jdbc.query("""
                        SELECT id, tenant_id, code, name, registered_at, last_seen_at
                        FROM pos_device
                        WHERE tenant_id = ? AND id = ?
                        """,
                deviceMapper(), tenantId, deviceId).stream().findFirst();
    }

    private Optional<BatchRow> findBatch(UUID tenantId, UUID batchId) {
        return jdbc.query("""
                        SELECT id, client_batch_id, device_id, status, operation_count,
                               applied_count, rejected_count, duplicate_count,
                               received_at, acked_at
                        FROM sync_batch
                        WHERE tenant_id = ? AND id = ?
                        """, batchMapper(), tenantId, batchId).stream().findFirst();
    }

    private Optional<BatchRow> findBatchByClientId(UUID tenantId, UUID clientBatchId) {
        return jdbc.query("""
                        SELECT id, client_batch_id, device_id, status, operation_count,
                               applied_count, rejected_count, duplicate_count,
                               received_at, acked_at
                        FROM sync_batch
                        WHERE tenant_id = ? AND client_batch_id = ?
                        """, batchMapper(), tenantId, clientBatchId).stream().findFirst();
    }

    private Optional<OperationRow> findOperation(UUID tenantId, UUID operationId) {
        return jdbc.query("""
                        SELECT id, operation_id, type, status, error_code, error_detail,
                               result::text AS result, occurred_at, applied_at
                        FROM sync_operation
                        WHERE tenant_id = ? AND operation_id = ?
                        """, operationMapper(), tenantId, operationId).stream().findFirst();
    }

    private List<OperationView> operationsOfBatch(UUID tenantId, UUID batchId) {
        return jdbc.query("""
                        SELECT id, operation_id, type, status, error_code, error_detail,
                               result::text AS result, occurred_at, applied_at
                        FROM sync_operation
                        WHERE tenant_id = ? AND batch_id = ?
                        ORDER BY applied_at, id
                        """,
                (rs, i) -> operationView(operationMapper().mapRow(rs, i), false),
                tenantId, batchId);
    }

    private BatchView batchView(BatchRow row, List<OperationView> operations) {
        return new BatchView(row.id(), row.clientBatchId(), row.deviceId(), row.status(),
                row.operationCount(), row.appliedCount(), row.rejectedCount(),
                row.duplicateCount(), instant(row.receivedAt()), instant(row.ackedAt()),
                operations);
    }

    private OperationView operationView(OperationRow row, boolean duplicate) {
        JsonNode result = null;
        if (row.result() != null) {
            try {
                result = objectMapper.readTree(row.result());
            } catch (Exception ignored) {
                result = null;
            }
        }
        return new OperationView(row.operationId(), row.type(), row.status(), duplicate,
                row.errorCode(), row.errorDetail(), result,
                instant(row.occurredAt()), instant(row.appliedAt()));
    }

    private static DeviceView deviceView(DeviceRow row) {
        return new DeviceView(row.id(), row.code(), row.name(),
                instant(row.registeredAt()), instant(row.lastSeenAt()));
    }

    private static RowMapper<DeviceRow> deviceMapper() {
        return (rs, i) -> new DeviceRow(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("code"), rs.getString("name"),
                instantOf(rs, "registered_at"), instantOf(rs, "last_seen_at"));
    }

    private static RowMapper<BatchRow> batchMapper() {
        return (rs, i) -> new BatchRow(
                rs.getObject("id", UUID.class), rs.getObject("client_batch_id", UUID.class),
                rs.getObject("device_id", UUID.class), rs.getString("status"),
                rs.getInt("operation_count"), rs.getInt("applied_count"),
                rs.getInt("rejected_count"), rs.getInt("duplicate_count"),
                instantOf(rs, "received_at"), instantOf(rs, "acked_at"));
    }

    private static RowMapper<OperationRow> operationMapper() {
        return (rs, i) -> new OperationRow(
                rs.getObject("id", UUID.class), rs.getObject("operation_id", UUID.class),
                rs.getString("type"), rs.getString("status"), rs.getString("error_code"),
                rs.getString("error_detail"), rs.getString("result"),
                instantOf(rs, "occurred_at"), instantOf(rs, "applied_at"));
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException cause) {
            return null;
        }
    }

    private String write(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception cause) {
            throw new IllegalArgumentException("El payload de la operación no es serializable");
        }
    }

    private static String classify(RuntimeException cause) {
        if (cause instanceof UnsupportedOperationException) {
            return "UNSUPPORTED_OPERATION";
        }
        if (cause instanceof IllegalArgumentException) {
            return "VALIDATION_ERROR";
        }
        if (cause instanceof NotFoundException) {
            return "NOT_FOUND";
        }
        if (cause instanceof InsufficientStockException) {
            return "INSUFFICIENT_STOCK";
        }
        if (cause instanceof ConflictException conflict) {
            return conflict.getCode();
        }
        return "INTERNAL_ERROR";
    }

    private static String detail(RuntimeException cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
