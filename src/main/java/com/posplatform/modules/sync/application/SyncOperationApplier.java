package com.posplatform.modules.sync.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.posplatform.modules.sales.application.SalesService;
import com.posplatform.modules.sales.application.SalesService.CreateSaleCommand;
import com.posplatform.modules.sales.application.SalesService.SaleLineCommand;
import com.posplatform.modules.sales.application.SalesService.SaleOutcome;
import com.posplatform.modules.sales.application.SalesService.SalePaymentCommand;
import com.posplatform.shared.tenancy.TenantTransactionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies ONE offline intent in its OWN transaction (fase 14 · ADR-024).
 *
 * A batch is a sequence of independent intents: one rejected sale must not
 * roll back the others, so each operation gets REQUIRES_NEW. The sales module
 * does the real work (prices/taxes snapshot, folio, inventory, cash attach)
 * and its own idempotency key (`sync:&lt;operationId&gt;`) makes a re-sent intent
 * return the SAME sale instead of duplicating it.
 *
 * The intent is NEVER trusted for money: the payload carries the prices the
 * till showed offline, but the server re-runs the tax engine and rejects the
 * operation when the payments do not match its own total.
 */
@Component
public class SyncOperationApplier {

    private final JdbcTemplate jdbc;
    private final TenantTransactionRunner tenantRunner;
    private final SalesService salesService;
    private final ObjectMapper objectMapper;

    public SyncOperationApplier(JdbcTemplate jdbc,
                                TenantTransactionRunner tenantRunner,
                                SalesService salesService,
                                ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tenantRunner = tenantRunner;
        this.salesService = salesService;
        this.objectMapper = objectMapper;
    }

    /** Applies a PENDING row and records APPLIED in the SAME transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(UUID tenantId, UUID rowId, UUID operationId, String type,
                      String payloadJson, Instant occurredAt) {
        tenantRunner.run(tenantId, () -> {
            if (!"SALE".equals(type)) {
                throw new UnsupportedOperationException("Tipo de operación no soportado: " + type);
            }
            JsonNode payload = read(payloadJson);
            SaleOutcome outcome = salesService.create(
                    tenantId, "sync:" + operationId, toSaleCommand(payload));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("saleId", outcome.sale().id().toString());
            result.put("folio", outcome.sale().folio());
            result.put("total", outcome.sale().total());
            result.put("replay", outcome.replay());

            jdbc.update("""
                            UPDATE sync_operation
                            SET status = 'APPLIED', result = ?::jsonb,
                                error_code = NULL, error_detail = NULL, applied_at = now()
                            WHERE tenant_id = ? AND id = ?
                            """,
                    write(result), tenantId, rowId);
            return null;
        });
    }

    private JsonNode read(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception cause) {
            throw new IllegalArgumentException("El payload de la operación no es JSON válido");
        }
    }

    private CreateSaleCommand toSaleCommand(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("La operación SALE requiere un payload objeto");
        }
        JsonNode linesNode = payload.get("lines");
        if (linesNode == null || !linesNode.isArray() || linesNode.isEmpty()) {
            throw new IllegalArgumentException("La operación SALE requiere al menos una línea");
        }
        List<SaleLineCommand> lines = new ArrayList<>();
        for (JsonNode line : linesNode) {
            lines.add(new SaleLineCommand(
                    requiredUuid(line, "variantId"),
                    requiredDecimal(line, "quantity"),
                    optionalDecimal(line, "unitPrice"),
                    optionalDecimal(line, "discount")));
        }

        JsonNode paymentsNode = payload.get("payments");
        if (paymentsNode == null || !paymentsNode.isArray() || paymentsNode.isEmpty()) {
            throw new IllegalArgumentException("La operación SALE requiere al menos un pago");
        }
        List<SalePaymentCommand> payments = new ArrayList<>();
        for (JsonNode payment : paymentsNode) {
            payments.add(new SalePaymentCommand(
                    requiredText(payment, "method"),
                    requiredDecimal(payment, "amount"),
                    optionalText(payment, "reference")));
        }

        UUID warehouseId = payload.hasNonNull("warehouseId")
                ? UUID.fromString(payload.get("warehouseId").asText())
                : null;
        String note = optionalText(payload, "note");
        return new CreateSaleCommand(lines, payments, note, warehouseId);
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        String value = requiredText(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException cause) {
            throw new IllegalArgumentException("'" + field + "' debe ser un UUID válido");
        }
    }

    private static BigDecimal requiredDecimal(JsonNode node, String field) {
        BigDecimal value = optionalDecimal(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Falta el campo numérico '" + field + "'");
        }
        return value;
    }

    private static BigDecimal optionalDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException("'" + field + "' debe ser numérico");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Falta el campo '" + field + "'");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private String write(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception cause) {
            throw new IllegalStateException("No se pudo serializar el resultado de la operación", cause);
        }
    }
}
