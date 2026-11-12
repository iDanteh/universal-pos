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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SALES REST (fase 4 · sales-payments.md): completed sales with SNAPSHOT
 * lines, exact payments and per-tenant idempotency. Fail-closed tenant
 * (404), cross-tenant variants invisible (404), payments MUST sum the total
 * (400), folio increments per tenant, cancel only once (409), replays return
 * the SAME sale (200) — ProblemDetails everywhere.
 */
@AutoConfigureMockMvc
class SalesApiIT extends AbstractDatabaseIT {

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
        String email = "sales-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org ventas " + tag,
                email,
                "Owner " + tag,
                "Ventas " + tag + " SA de CV",
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

    /** Creates one priced variant via the CATALOG REST of the seeded tenant. */
    private Variant seedVariant(Seeded seeded, String tag, String price, boolean withPrice)
            throws Exception {
        String priceJson = withPrice ? "\"price\":\"%s\"".formatted(price) : "\"price\":null";
        MvcResult created = mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"name":"Producto %s","baseUnit":"PZ","variants":[
                                  {"sku":"skv-%s","unit":"PZ",%s}]}
                                """.formatted(tag, tag, priceJson)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        return new Variant(UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].id")),
                com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].sku"));
    }

    private String saleBody(Variant variant, int quantity, String cash) {
        return """
                {"lines":[{"variantId":"%s","quantity":%d}],
                 "payments":[{"method":"CASH","amount":"%s"}]}
                """.formatted(variant.variantId(), quantity, cash);
    }

    private MvcResult createSale(Seeded seeded, Variant variant, int quantity, String key, String cash)
            throws Exception {
        return mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(saleBody(variant, quantity, cash)))
                .andReturn();
    }

    @Test
    void salesEndpointsFailClosedWithoutAVerifiedTenant() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        // no session at all
        mockMvc.perform(get("/api/v1/sales"))
                .andExpect(status().isUnauthorized());

        // session without tenant header: authenticated but tenant-less -> 404
        mockMvc.perform(get("/api/v1/sales").cookie(sessionOf(seeded)))
                .andExpect(status().isNotFound());

        // tenant the user does NOT belong to: refused by the filter -> 404
        mockMvc.perform(get("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createSaleSnapshotsCatalogPriceAndReplaysIdempotently() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Variant variant = seedVariant(seeded, tag, "25.5000", true);

        MvcResult posted = createSale(seeded, variant, 2, "key-" + tag, "51.00");
        assertThat(posted.getResponse().getStatus()).isEqualTo(201);
        String body = posted.getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.folio")).isEqualTo(1);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.subtotal")).isEqualTo("51.00");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.total")).isEqualTo("51.00");
        // SNAPSHOT: the line carries the CATALOG price at sale time
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.lines[0].unitPrice"))
                .isEqualTo("25.5000");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.lines[0].sku"))
                .isEqualToIgnoringCase("SKV-" + tag);

        // REPLAY with the same key: same sale, 200, no duplication
        MvcResult replay = createSale(seeded, variant, 2, "key-" + tag, "51.00");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(body);

        // payments must sum EXACTLY the total
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "key-mm-" + tag)
                        .contentType("application/json")
                        .content(saleBody(variant, 2, "60.00")))
                .andExpect(status().isBadRequest());

        // missing required Idempotency-Key header -> 400
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(saleBody(variant, 1, "25.50")))
                .andExpect(status().isBadRequest());

        // listing holds exactly ONE sale (the idempotent replay did not duplicate)
        mockMvc.perform(get("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void foliosIncrementPerTenantAndUnitPriceOverrideSnapshots() throws Exception {
        var tenantA = seedUser("fola");
        var tenantB = seedUser("folb");
        Variant variantA = seedVariant(tenantA,
                UUID.randomUUID().toString().substring(0, 8), "100.0000", false);

        // folio 1: explicit unitPrice because the variant has NO catalog price
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(tenantA))
                        .header("X-Tenant-Id", tenantA.organizationId().toString())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":1,"unitPrice":10.0}],
                                 "payments":[{"method":"CASH","amount":"10.00"}]}
                                """.formatted(variantA.variantId().toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folio").value(1))
                .andExpect(jsonPath("$.total").value("10.00"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value("10.0000"));

        // folio 2 same tenant
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(tenantA))
                        .header("X-Tenant-Id", tenantA.organizationId().toString())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":1,"unitPrice":5.0}],
                                 "payments":[{"method":"CARD","amount":"5.00","reference":"T1"}]}
                                """.formatted(variantA.variantId().toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folio").value(2));

        // tenant B folio restarts at 1
        Variant variantB = seedVariant(tenantB,
                UUID.randomUUID().toString().substring(0, 8), "7.0000", true);
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(tenantB))
                        .header("X-Tenant-Id", tenantB.organizationId().toString())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(saleBody(variantB, 1, "7.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folio").value(1));
    }

    @Test
    void cancelRequiresReasonAndOnlyWorksOnce() throws Exception {
        var seeded = seedUser("cncr");
        Variant variant = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8), "9.0000", true);
        String tenant = seeded.organizationId().toString();

        MvcResult posted = createSale(seeded, variant, 1, "cnc-" + UUID.randomUUID(), "9.00");
        assertThat(posted.getResponse().getStatus()).isEqualTo(201);
        String saleId = com.jayway.jsonpath.JsonPath.read(
                posted.getResponse().getContentAsString(), "$.id");

        // blank reason -> 400
        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());

        // cancel -> 200 CANCELED with reason persisted
        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"reason\":\"Cliente se arrepintio\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.statusReason").value("Cliente se arrepintio"));

        // second cancel -> 409 SALE_ALREADY_CANCELED
        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"reason\":\"Otra vez\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Business rule conflict"))
                .andExpect(jsonPath("$.code").value("SALE_ALREADY_CANCELED"));
    }

    @Test
    void crossTenantVariantsAndSalesAreInvisible() throws Exception {
        var ownerA = seedUser("xtA");
        var ownerB = seedUser("xtB");
        Variant variantA = seedVariant(ownerA, "xtxa", "12.0000", true);

        // tenant B cannot SELL tenant A's variant -> 404 (fail-closed)
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", ownerB.organizationId().toString())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(saleBody(variantA, 1, "12.00")))
                .andExpect(status().isNotFound());

        // tenant A sells; tenant B cannot READ that sale
        MvcResult posted = createSale(ownerA, variantA, 1, "xt-" + UUID.randomUUID(), "12.00");
        assertThat(posted.getResponse().getStatus()).isEqualTo(201);
        String saleId = com.jayway.jsonpath.JsonPath.read(
                posted.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/sales/{id}", saleId)
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", ownerB.organizationId().toString()))
                .andExpect(status().isNotFound());

        // and B's listing is empty
        mockMvc.perform(get("/api/v1/sales")
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", ownerB.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
