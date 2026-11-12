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
 * CASH REST (fase 7 · ADR-017): the drawer lifecycle end to end.
 * Fail-closed entry (401/404/404), open -> current -> sale attach -> close
 * with EXACT cash reconciliation (expected = float + attached CASH),
 * ONE-open-per-tenant (409 both in the service and reopen-after-close OK),
 * and the counted-vs-cash difference (short/over drawer) as a plain number.
 */
@AutoConfigureMockMvc
class CashApiIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AppUserRepository appUserRepository;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private record Seeded(UUID organizationId, UUID legalEntityId, String email,
                          String password) {
    }

    private record Variant(UUID variantId, String sku) {
    }

    private Seeded seedUser(String tag) {
        String email = "cash-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org caja " + tag,
                email,
                "Owner " + tag,
                "Caja " + tag + " SA de CV",
                null, null,
                "Sucursal Centro", "CDMX"));
        return new Seeded(org.organizationId(), org.legalEntityId(), email, password);
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

    private Variant seedVariant(Seeded seeded, String tag, String price) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"name":"Producto %s","baseUnit":"PZ","variants":[
                                  {"sku":"skc-%s","unit":"PZ","price":"%s"}]}
                                """.formatted(tag, tag, price)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        return new Variant(
                UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].id")),
                com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].sku"));
    }

    private String createSaleExact(Seeded seeded, Variant variant, int quantity,
                                   String key, String cash) throws Exception {
        MvcResult posted = mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":%d}],
                                 "payments":[{"method":"CASH","amount":"%s"}]}
                                """.formatted(variant.variantId(), quantity, cash)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                posted.getResponse().getContentAsString(), "$.id");
    }

    // ---- tests ----

    @Test
    void cashEndpointsFailClosedWithoutAVerifiedTenant() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        // no session cookie at all -> 401
        mockMvc.perform(get("/api/v1/cash/sessions"))
                .andExpect(status().isUnauthorized());

        // authenticated but tenant-less -> 404 (fail-closed, never a guess)
        mockMvc.perform(get("/api/v1/cash/sessions").cookie(sessionOf(seeded)))
                .andExpect(status().isNotFound());

        // random tenant header -> not a member -> 404
        mockMvc.perform(get("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        // unknown session id inside the tenant -> 404
        mockMvc.perform(get("/api/v1/cash/sessions/{id}", UUID.randomUUID())
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isNotFound());
    }

    @Test
    void openCurrentCloseReconcilesCashOnlyWithAttachedSales() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        Variant variant = seedVariant(seeded, "caja", "12.5000");

        // a sale collected BEFORE the shift exists must NOT be reconciled
        createSaleExact(seeded, variant, 1, "pre-" + UUID.randomUUID(), "12.50");

        // open with a physical float
        MvcResult opened = mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"openingFloat":"500.00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingFloat").value("500.00"))
                .andExpect(jsonPath("$.cashTotal").value("0.00"))
                .andExpect(jsonPath("$.salesCount").value(0))
                .andReturn();
        String sessionId = com.jayway.jsonpath.JsonPath.read(
                opened.getResponse().getContentAsString(), "$.id");

        // current mirrors the open session
        mockMvc.perform(get("/api/v1/cash/sessions/current")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // a sale DURING the shift attaches to it
        MvcResult posted = mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "in-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":2}],
                                 "payments":[{"method":"CASH","amount":"25.00"}]}
                                """.formatted(variant.variantId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashSessionId").value(sessionId))
                .andReturn();
        com.jayway.jsonpath.JsonPath.read(
                posted.getResponse().getContentAsString(), "$.id");

        // close counts the drawer: 500.00 float + 25.00 cash of the shift
        MvcResult closed = mockMvc.perform(post("/api/v1/cash/sessions/{id}/close", sessionId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"countedCash":"525.00","countedCard":"0.00",
                                 "note":"Cierre cuadrado"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.expectedCash").value("525.00"))
                .andExpect(jsonPath("$.difference").value("0.00"))
                .andExpect(jsonPath("$.salesCount").value(1))
                .andExpect(jsonPath("$.cashTotal").value("25.00"))
                .andExpect(jsonPath("$.closeNote").value("Cierre cuadrado"))
                .andReturn();

        assertThat(closed.getResponse().getContentAsString()).isNotBlank();

        // after closing there is NO open drawer: current -> 404
        mockMvc.perform(get("/api/v1/cash/sessions/current")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isNotFound());

        // history shows both sessions? (the closed one with stored numbers)
        mockMvc.perform(get("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sessionId))
                .andExpect(jsonPath("$[0].countedCash").value("525.00"));
    }

    @Test
    void aCanceledSaleStopsCountingForTheDrawerExpected() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        Variant variant = seedVariant(seeded, "cxl", "50.0000");

        // empty drawer: both CASH sales below attach to it
        MvcResult opened = mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"openingFloat\":\"0.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        String sessionId = com.jayway.jsonpath.JsonPath.read(
                opened.getResponse().getContentAsString(), "$.id");

        createSaleExact(seeded, variant, 2, "keep-" + UUID.randomUUID(), "100.00");
        String goneId = createSaleExact(seeded, variant, 1, "gone-" + UUID.randomUUID(), "50.00");

        // canceling a sale gives its cash back to the customer: the drawer
        // does NOT hold that 50.00 anymore
        mockMvc.perform(post("/api/v1/sales/{id}/cancel", goneId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"reason\":\"Cliente se arrepintio\"}"))
                .andExpect(status().isOk());

        // the shift shows both attached sales but counts ONLY the cash of
        // the COMPLETED one (counting the canceled one invents a phantom
        // shortage of 50.00 at close)
        mockMvc.perform(get("/api/v1/cash/sessions/current")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount").value(2))
                .andExpect(jsonPath("$.cashTotal").value("100.00"));

        // close: float 0 + 100.00 = 100.00 expected, difference 0.00
        mockMvc.perform(post("/api/v1/cash/sessions/{id}/close", sessionId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"countedCash\":\"100.00\",\"countedCard\":\"0.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCash").value("100.00"))
                .andExpect(jsonPath("$.difference").value("0.00"))
                .andExpect(jsonPath("$.cashTotal").value("100.00"));
    }

    @Test
    void aSecondOpenIsRejectedWhileOneSessionIsActive() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        MvcResult opened = mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"openingFloat":"100.00"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionId = com.jayway.jsonpath.JsonPath.read(
                opened.getResponse().getContentAsString(), "$.id");

        // second drawer -> 409 (the ADDITIONAL guard of V14 partial index)
        mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"openingFloat":"200.00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_ALREADY_OPEN"));

        // closing twice -> 409
        mockMvc.perform(post("/api/v1/cash/sessions/{id}/close", sessionId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"countedCash":"100.00"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cash/sessions/{id}/close", sessionId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"countedCash":"100.00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_SESSION_ALREADY_CLOSED"));

        // once closed, a new shift IS allowed (one OPEN at a time, not forever)
        mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.openingFloat").value("0.00"));
    }

    @Test
    void countedVsCashSurfacesTheDrawerDifference() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        // float 300.00, no sales yet: an honest short drawer of a missing coin
        MvcResult opened = mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"openingFloat":"300.00"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionId = com.jayway.jsonpath.JsonPath.read(
                opened.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/cash/sessions/{id}/close", sessionId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"countedCash":"299.50",
                                 "note":"Falta una moneda de 50 centavos"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCash").value("300.00"))
                .andExpect(jsonPath("$.difference").value("-0.50"));

        // negative counts are refused (400), they don't invent money
        mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"openingFloat":"-1.00"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
