package com.posplatform.it;

import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INVENTORY REST (fase 5 · ADR-004): warehouses, immutable movements with
 * FEFO lot consumption, vertical stock policies (INSUFFICIENT_STOCK 409),
 * mirror-pair transfers and the sale↔inventory seam (sale consumes in the
 * same transaction; cancel returns stock compensatorily). Fail-closed tenant
 * everywhere; ProblemDetails in business errors.
 */
@AutoConfigureMockMvc
class InventoryApiIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AppUserRepository appUserRepository;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private record Seeded(UUID organizationId, String email, String password) {
    }

    private record Variant(UUID variantId, String sku) {
    }

    private Seeded seedUser(String tag) {
        String email = "inv-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org inv " + tag,
                email,
                "Owner " + tag,
                "Inventario " + tag + " SA de CV",
                null, null,
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

    private Seeded switchVertical(Seeded seeded, String verticalCode) throws Exception {
        mockMvc.perform(post("/api/v1/organizations/current/vertical")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"verticalCode\":\"%s\"}".formatted(verticalCode)))
                .andExpect(status().isOk());
        return seeded;
    }

    private Variant seedVariant(Seeded seeded, String tag, String price, String attrsJson)
            throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"name":"Producto %s","baseUnit":"PZ","variants":[
                                  {"sku":"skv-%s","unit":"PZ","price":"%s","attributes":%s}]}
                                """.formatted(tag, tag, price, attrsJson)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        return new Variant(UUID.fromString(JsonPath.read(body, "$.variants[0].id")),
                JsonPath.read(body, "$.variants[0].sku"));
    }

    private MvcResult createWarehouse(Seeded seeded, String code, String name)
            throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/warehouses")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"code\":\"%s\",\"name\":\"%s\"}".formatted(code, name)))
                .andReturn();
    }

    private MvcResult receive(Seeded seeded, String body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/receipts")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content(body))
                .andReturn();
    }

    @Test
    void inventoryEndpointsFailClosedWithoutAVerifiedTenant() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        // no session
        mockMvc.perform(get("/api/v1/inventory/warehouses"))
                .andExpect(status().isUnauthorized());

        // session without tenant header -> 404
        mockMvc.perform(get("/api/v1/inventory/warehouses").cookie(sessionOf(seeded)))
                .andExpect(status().isNotFound());

        // tenant the user does NOT belong to -> 404
        mockMvc.perform(get("/api/v1/inventory/warehouses")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void warehousesAreUniquePerTenantAndBalancesListOwnOnly() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var seeded = seedUser(tag);
        String tenant = seeded.organizationId().toString();
        String warehouseTag = UUID.randomUUID().toString().substring(0, 8);

        MvcResult created = createWarehouse(seeded, warehouseTag, "Almacen " + warehouseTag);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String warehouseId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        // code normalizes to uppercase
        assertThat(JsonPath.<String>read(created.getResponse().getContentAsString(), "$.code"))
                .isEqualToIgnoringCase(warehouseTag);

        // duplicate code (same tenant) -> 409 WAREHOUSE_DUPLICATE
        mockMvc.perform(post("/api/v1/inventory/warehouses")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"code\":\"%s\",\"name\":\"Otro\"}".formatted(warehouseTag.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_DUPLICATE"));

        // listing shows exactly the tenant's warehouse
        mockMvc.perform(get("/api/v1/inventory/warehouses")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // balances of a FOREIGN warehouse id -> 404 (fail-closed)
        mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .param("warehouseId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        // balances of the REAL warehouse -> empty (no movements yet)
        mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .param("warehouseId", warehouseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void pharmacyLotsAreRequiredOnReceiveAndSalesConsumeFefo() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var seeded = switchVertical(seedUser(tag), "PHARMACY"); // LOT+EXPIRY, stock FORBIDDEN
        String tenant = seeded.organizationId().toString();
        Variant variant = seedVariant(seeded, "fx" + tag.substring(0, 4), "10.0000",
                "{\"activeIngredient\":\"Farma X\"}");

        MvcResult warehouseRes = createWarehouse(seeded, "main" + tag.substring(0, 5), "Almacen principal " + tag);
        assertThat(warehouseRes.getResponse().getStatus()).isEqualTo(201);
        String warehouseId = JsonPath.read(warehouseRes.getResponse().getContentAsString(), "$.id");

        // receiving WITHOUT lot -> 400 (PHARMACY tracks LOT)
        mockMvc.perform(post("/api/v1/inventory/receipts")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"warehouseId":"%s","lines":[{"variantId":"%s","quantity":5}]}
                                """.formatted(warehouseId, variant.variantId())))
                .andExpect(status().isBadRequest());

        // receiving a lot WITHOUT expiry -> 400 (PHARMACY tracks EXPIRY)
        MvcResult noExpiry = receive(seeded, """
                {"warehouseId":"%s","lines":[{"variantId":"%s","quantity":5,"lotCode":"L1"}]}
                """.formatted(warehouseId, variant.variantId()));
        assertThat(noExpiry.getResponse().getStatus()).isEqualTo(400);

        // proper receipt: two lots, LOTE-B expiring first (FEFO source)
        MvcResult received = receive(seeded, """
                {"warehouseId":"%s","lines":[
                  {"variantId":"%s","quantity":3,"lotCode":"LOTE-B","expiryDate":"2026-12-01"},
                  {"variantId":"%s","quantity":7,"lotCode":"LOTE-A","expiryDate":"2027-06-30"}],
                 "reference":"OC-123"}
                """.formatted(warehouseId, variant.variantId(), variant.variantId()));
        assertThat(received.getResponse().getStatus()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(received.getResponse().getContentAsString(), "$.movements.length()"))
                .isEqualTo(2);

        // balances aggregate 10 with BOTH lots visible
        MvcResult balances = mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .param("warehouseId", warehouseId))
                .andExpect(status().isOk())
                .andReturn();
        String balanceBody = balances.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(balanceBody, "$[0].quantity")).isEqualTo("10.0000");
        assertThat((Integer) JsonPath.read(balanceBody, "$[0].lots.length()")).isEqualTo(2);

        // SELL 4 via the SALES api: FEFO consumes LOTE-B (earlier expiry) first
        MvcResult sale = mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "sale-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":4}],
                                 "payments":[{"method":"CASH","amount":"40.00"}]}
                                """.formatted(variant.variantId())))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = JsonPath.read(sale.getResponse().getContentAsString(), "$.id");

        MvcResult movements = mockMvc.perform(get("/api/v1/inventory/movements")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .param("variantId", variant.variantId().toString()))
                .andExpect(status().isOk())
                .andReturn();
        String movementBody = movements.getResponse().getContentAsString();
        // 2 PURCHASE (one per lot) + 2 SALE OUT consumed FEFO (split 3 B + 1 A)
        assertThat(movementBody).contains("\"cause\":\"SALE\"");
        assertThat(movementBody).contains("\"cause\":\"PURCHASE\"");
        assertThat((Integer) JsonPath.read(movementBody, "$.length()")).isEqualTo(4);

        // balance consumed by the sale: 6 left, LOTE-B went 3 -> some may have
        // spilled into LOTE-A when LOTE-B lacked stock (4 > 3): 3 from B + 1 from A
       MvcResult balancesAfter = mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .param("warehouseId", warehouseId))
                .andExpect(status().isOk())
                .andReturn();
        String afterBody = balancesAfter.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(afterBody, "$[0].quantity")).isEqualTo("6.0000");

        // cancel the sale: stock returns via compensatory SALE_RETURN (IN)
        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"reason\":\"Cancelada para pruebas\"}"))
                .andExpect(status().isOk());

        MvcResult balancesRestored = mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .param("warehouseId", warehouseId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(JsonPath.<String>read(
                balancesRestored.getResponse().getContentAsString(), "$[0].quantity"))
                .isEqualTo("10.0000");

        // without stock the FORBIDDEN policy refuses the sale (409, nothing saved)
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "sale-nf-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":999}],
                                 "payments":[{"method":"CASH","amount":"9990.00"}]}
                                """.formatted(variant.variantId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        mockMvc.perform(get("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)); // still only the CANCELED one
    }

    @Test
    void transfersCreateLinkedMirrorPairsAndNeverGoNegative() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var seeded = seedUser(tag); // CUSTOM: no lots, check policy ALLOWED_WITH_ALERT
        String tenant = seeded.organizationId().toString();
        Variant variant = seedVariant(seeded, "tr" + tag.substring(0, 4), "5.0000", "{}");

        MvcResult whA = createWarehouse(seeded, "A" + tag.substring(0, 5), "Origen " + tag);
        MvcResult whB = createWarehouse(seeded, "B" + tag.substring(0, 5), "Destino " + tag);
        String wa = JsonPath.read(whA.getResponse().getContentAsString(), "$.id");
        String wb = JsonPath.read(whB.getResponse().getContentAsString(), "$.id");

        // receive 8 at source
        MvcResult received = receive(seeded, """
                {"warehouseId":"%s","lines":[{"variantId":"%s","quantity":8}]}
                """.formatted(wa, variant.variantId()));
        assertThat(received.getResponse().getStatus()).isEqualTo(201);

        // transfer of stock that does NOT exist -> 409 even with a permissive policy
        mockMvc.perform(post("/api/v1/inventory/transfers")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"fromWarehouseId":"%s","toWarehouseId":"%s",
                                 "lines":[{"variantId":"%s","quantity":100}]}
                                """.formatted(wa, wb, variant.variantId())))
                .andExpect(status().isConflict());

        // real transfer 5 A -> B: mirror pair (OUT linked to IN)
        MvcResult transfer = mockMvc.perform(post("/api/v1/inventory/transfers")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"fromWarehouseId":"%s","toWarehouseId":"%s",
                                 "lines":[{"variantId":"%s","quantity":5}]}
                                """.formatted(wa, wb, variant.variantId())))
                .andExpect(status().isCreated())
                .andReturn();
        String transferBody = transfer.getResponse().getContentAsString();
        // the view lists the IN first: it is LINKED to the OUT (mirror pair)
        assertThat(JsonPath.<String>read(transferBody, "$.movements[0].cause"))
                .isEqualTo("TRANSFER_IN");
        assertThat(JsonPath.<String>read(transferBody, "$.movements[1].cause"))
                .isEqualTo("TRANSFER_OUT");
        assertThat(JsonPath.<String>read(transferBody, "$.movements[0].relatedMovementId"))
                .isEqualTo(JsonPath.read(transferBody, "$.movements[1].id"));

        // balances: origin 3, destination 5 — RLS lists own tenant only
        MvcResult balancesA = mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .param("warehouseId", wa))
                .andExpect(status().isOk()).andReturn();
        assertThat(JsonPath.<String>read(balancesA.getResponse().getContentAsString(), "$[0].quantity"))
                .isEqualTo("3.0000");

        MvcResult balancesB = mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .param("warehouseId", wb))
                .andExpect(status().isOk()).andReturn();
        assertThat(JsonPath.<String>read(balancesB.getResponse().getContentAsString(), "$[0].quantity"))
                .isEqualTo("5.0000");
    }

    @Test
    void crossTenantStockCompletelyInvisible() throws Exception {
        var ownerA = seedUser("xA");
        var ownerB = seedUser("xB");
        Variant variantA = seedVariant(ownerA, "xt" + UUID.randomUUID().toString().substring(0, 4), "2.0000", "{}");
        String tenantA = ownerA.organizationId().toString();
        String tenantB = ownerB.organizationId().toString();

        MvcResult whA = createWarehouse(ownerA, "w" + UUID.randomUUID().toString().substring(0, 5), "Almacen A");
        String wa = JsonPath.read(whA.getResponse().getContentAsString(), "$.id");
        MvcResult received = receive(ownerA, """
                {"warehouseId":"%s","lines":[{"variantId":"%s","quantity":4}]}
                """.formatted(wa, variantA.variantId()));
        assertThat(received.getResponse().getStatus()).isEqualTo(201);

        // tenant B does NOT see tenant A's balance (foreign w/h -> 404)
        mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", tenantB)
                        .param("warehouseId", wa))
                .andExpect(status().isNotFound());

        // tenant B cannot RECEIVE tenant A's variant -> 404 (fail-closed)
        mockMvc.perform(post("/api/v1/inventory/receipts")
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", tenantB)
                        .contentType("application/json")
                        .content("""
                                {"warehouseId":"%s","lines":[{"variantId":"%s","quantity":1}]}
                                """.formatted(wa, variantA.variantId())))
                .andExpect(status().isNotFound());
    }
}
