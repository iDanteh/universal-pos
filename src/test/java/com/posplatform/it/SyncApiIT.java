package com.posplatform.it;

import com.posplatform.modules.identity.AppUser;
import com.posplatform.modules.identity.infrastructure.AppUserRepository;
import com.posplatform.modules.organization.application.OnboardOrganizationCommand;
import com.posplatform.modules.organization.application.OnboardOrganizationResult;
import com.posplatform.modules.organization.application.OnboardingService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OFFLINE SYNC (fase 14 · ADR-024): devices, replay-safe batches and
 * idempotent intents. The POS sends the prices it showed offline; the server
 * re-runs the tax engine and rejects a payment that does not match its total.
 *
 * Exact numbers: price 100.00 + IVA16 -> 116.00.
 */
@AutoConfigureMockMvc
class SyncApiIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AppUserRepository appUserRepository;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private record Seeded(UUID organizationId, String email, String password) {
    }

    private Seeded seedUser(String tag) {
        String email = "sync-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org sync " + tag, email, "Owner " + tag,
                "Sync " + tag + " SA de CV", null, null,
                "Sucursal Centro", "CDMX"));
        return new Seeded(org.organizationId(), email, password);
    }

    private Cookie sessionOf(Seeded seeded) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(
                                seeded.email(), seeded.password())))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = login.getResponse().getCookie("pos_session");
        assertThat(session).isNotNull();
        return session;
    }

    private UUID seedVariant(Seeded seeded, String tag, String price, String taxRateCode)
            throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"name":"Producto sync %s","baseUnit":"PZ","variants":[
                                  {"sku":"t14-%s","unit":"PZ","price":"%s","taxRateCode":"%s"}]}
                                """.formatted(tag, tag, price, taxRateCode)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.variants[0].id"));
    }

    private UUID registerDevice(Seeded seeded, String code, String name) throws Exception {
        MvcResult created = mockMvc.perform(put("/api/v1/sync/devices/{code}", code)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id"));
    }

    private String batchBody(UUID clientBatchId, UUID deviceId, String operations) {
        return """
                {"clientBatchId":"%s","deviceId":"%s","operations":[%s]}
                """.formatted(clientBatchId, deviceId, operations);
    }

    private String saleOperation(UUID operationId, UUID variantId, String quantity,
                                 String unitPrice, String method, String amount) {
        return """
                {"operationId":"%s","type":"SALE","occurredAt":"2026-11-26T10:00:00Z",
                 "payload":{"lines":[{"variantId":"%s","quantity":%s,"unitPrice":"%s"}],
                            "payments":[{"method":"%s","amount":"%s"}]}}
                """.formatted(operationId, variantId, quantity, unitPrice, method, amount);
    }

    private long salesCount(Seeded seeded) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                list.getResponse().getContentAsString(), "$.length()")).longValue();
    }

    // ---- tests ----

    @Test
    void syncFailsClosedWithoutSessionOrVerifiedTenant() throws Exception {
        var seeded = seedUser("closed");

        mockMvc.perform(get("/api/v1/sync/devices"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sync/devices").cookie(sessionOf(seeded)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/sync/devices/POS-1")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"name\":\"Caja\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deviceRegistrationIsIdempotentPerTenantCode() throws Exception {
        var seeded = seedUser("device");
        String tenant = seeded.organizationId().toString();

        UUID first = registerDevice(seeded, "POS-CENTRO", "Caja centro");
        UUID again = registerDevice(seeded, "POS-CENTRO", "Caja centro 1");
        assertThat(again).isEqualTo(first);

        mockMvc.perform(get("/api/v1/sync/devices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("POS-CENTRO"))
                .andExpect(jsonPath("$[0].name").value("Caja centro 1"));

        // same code in another tenant is a DIFFERENT device (RLS + unique per tenant)
        var other = seedUser("device-b");
        UUID otherDevice = registerDevice(other, "POS-CENTRO", "Caja centro B");
        assertThat(otherDevice).isNotEqualTo(first);
    }

    @Test
    void batchAppliesOfflineSaleOnceAndReplaysSafely() throws Exception {
        var seeded = seedUser("apply");
        String tenant = seeded.organizationId().toString();
        UUID device = registerDevice(seeded, "POS-A", "Caja A");
        UUID variant = seedVariant(seeded, "apply", "100.0000", "IVA16");

        UUID operationId = UUID.randomUUID();
        UUID clientBatchId = UUID.randomUUID();
        String body = batchBody(clientBatchId, device,
                saleOperation(operationId, variant, "1", "100.0000", "CASH", "116.00"));

        MvcResult applied = mockMvc.perform(post("/api/v1/sync/batches")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.batch.status").value("APPLIED"))
                .andExpect(jsonPath("$.batch.appliedCount").value(1))
                .andExpect(jsonPath("$.batch.operations[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.batch.operations[0].result.total").value("116.00"))
                .andReturn();

        String saleId = com.jayway.jsonpath.JsonPath.read(
                applied.getResponse().getContentAsString(),
                "$.batch.operations[0].result.saleId");
        long folio = ((Number) com.jayway.jsonpath.JsonPath.read(
                applied.getResponse().getContentAsString(),
                "$.batch.operations[0].result.folio")).longValue();
        assertThat(folio).isPositive();
        assertThat(salesCount(seeded)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/sales/{id}", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("116.00"))
                .andExpect(jsonPath("$.idempotencyKey").value("sync:" + operationId));

        // SAME clientBatchId after a timeout -> 200, stored answer, no new sale
        String appliedBatchId = com.jayway.jsonpath.JsonPath.read(
                applied.getResponse().getContentAsString(), "$.batch.id");
        mockMvc.perform(post("/api/v1/sync/batches")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true))
                .andExpect(jsonPath("$.batch.id").value(appliedBatchId));
        assertThat(salesCount(seeded)).isEqualTo(1);

        // SAME operationId in a NEW batch -> duplicate result, still ONE sale
        mockMvc.perform(post("/api/v1/sync/batches")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(batchBody(UUID.randomUUID(), device,
                                saleOperation(operationId, variant, "1", "100.0000", "CASH", "116.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("APPLIED"))
                .andExpect(jsonPath("$.batch.duplicateCount").value(1))
                .andExpect(jsonPath("$.batch.operations[0].duplicate").value(true))
                .andExpect(jsonPath("$.batch.operations[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.batch.operations[0].result.total").value("116.00"));
        assertThat(salesCount(seeded)).isEqualTo(1);
    }

    @Test
    void oneInvalidIntentDoesNotPoisonTheBatch() throws Exception {
        var seeded = seedUser("partial");
        String tenant = seeded.organizationId().toString();
        UUID device = registerDevice(seeded, "POS-B", "Caja B");
        UUID variant = seedVariant(seeded, "partial", "100.0000", "IVA16");

        String operations = saleOperation(UUID.randomUUID(), variant, "1",
                "100.0000", "CASH", "116.00")
                + ","
                + saleOperation(UUID.randomUUID(), UUID.randomUUID(), "1",
                "50.0000", "CASH", "58.00");

        mockMvc.perform(post("/api/v1/sync/batches")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(batchBody(UUID.randomUUID(), device, operations)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("PARTIAL"))
                .andExpect(jsonPath("$.batch.appliedCount").value(1))
                .andExpect(jsonPath("$.batch.rejectedCount").value(1))
                .andExpect(jsonPath("$.batch.operations[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.batch.operations[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.batch.operations[1].errorCode").value("NOT_FOUND"));

        assertThat(salesCount(seeded)).isEqualTo(1);
    }

    @Test
    void serverRejectsAnIntentWhosePaymentDoesNotMatchItsOwnTotal() throws Exception {
        var seeded = seedUser("money");
        String tenant = seeded.organizationId().toString();
        UUID device = registerDevice(seeded, "POS-C", "Caja C");
        UUID variant = seedVariant(seeded, "money", "100.0000", "IVA16");

        mockMvc.perform(post("/api/v1/sync/batches")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(batchBody(UUID.randomUUID(), device,
                                saleOperation(UUID.randomUUID(), variant, "1",
                                        "100.0000", "CASH", "100.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("REJECTED"))
                .andExpect(jsonPath("$.batch.rejectedCount").value(1))
                .andExpect(jsonPath("$.batch.operations[0].errorCode").value("VALIDATION_ERROR"));

        assertThat(salesCount(seeded)).isZero();
    }

    @Test
    void tenantIsolationCoversDevicesBatchesAndOperations() throws Exception {
        var tenantA = seedUser("iso-a");
        var tenantB = seedUser("iso-b");
        UUID deviceA = registerDevice(tenantA, "POS-ISO", "Caja A");
        UUID variantA = seedVariant(tenantA, "iso", "100.0000", "IVA16");

        MvcResult batchA = mockMvc.perform(post("/api/v1/sync/batches")
                        .cookie(sessionOf(tenantA))
                        .header("X-Tenant-Id", tenantA.organizationId().toString())
                        .contentType("application/json")
                        .content(batchBody(UUID.randomUUID(), deviceA,
                                saleOperation(UUID.randomUUID(), variantA, "1",
                                        "100.0000", "CASH", "116.00"))))
                .andExpect(status().isCreated())
                .andReturn();
        String batchId = com.jayway.jsonpath.JsonPath.read(
                batchA.getResponse().getContentAsString(), "$.batch.id");

        // B cannot deliver to A's device (device lookup is tenant-scoped)
        mockMvc.perform(post("/api/v1/sync/batches")
                        .cookie(sessionOf(tenantB))
                        .header("X-Tenant-Id", tenantB.organizationId().toString())
                        .contentType("application/json")
                        .content(batchBody(UUID.randomUUID(), deviceA,
                                saleOperation(UUID.randomUUID(), variantA, "1",
                                        "100.0000", "CASH", "116.00"))))
                .andExpect(status().isNotFound());

        // B cannot read A's batch or device
        mockMvc.perform(get("/api/v1/sync/batches/{id}", batchId)
                        .cookie(sessionOf(tenantB))
                        .header("X-Tenant-Id", tenantB.organizationId().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/sync/devices")
                        .cookie(sessionOf(tenantB))
                        .header("X-Tenant-Id", tenantB.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // A still sees its own batch
        mockMvc.perform(get("/api/v1/sync/batches")
                        .cookie(sessionOf(tenantA))
                        .header("X-Tenant-Id", tenantA.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(batchId));
    }
}
