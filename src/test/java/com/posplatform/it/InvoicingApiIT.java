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
 * INVOICING REST (fase 6 · ADR-006/016): CFDI 4.0 from completed sales.
 * Emisor fiscal face (incomplete -> 400), sat_catalog validation (400), the
 * global invoice of a period (XAXX010101000 + one ACTIVE per month), the
 * lifecycle (issued STAMPED via Mock PAC), cancellation with SAT motives
 * (motive 04 rejected) and re-issue of the same sale AFTER cancelling.
 */
@AutoConfigureMockMvc
class InvoicingApiIT extends AbstractDatabaseIT {

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

    private static final String REGEX_RFC = "GME800410HM2";

    private Seeded seedUser(String tag) {
        String email = "fiscal-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org fiscal " + tag,
                email,
                "Owner " + tag,
                "Fiscales " + tag + " SA de CV",
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
                                  {"sku":"skf-%s","unit":"PZ","price":"%s"}]}
                                """.formatted(tag, tag, price)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        return new Variant(
                UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].id")),
                com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].sku"));
    }

    private String createSale(Seeded seeded, Variant variant, int quantity, String key)
            throws Exception {
        MvcResult posted = mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":%d}],
                                 "payments":[{"method":"CASH","amount":"%s"}]}
                                """.formatted(variant.variantId(), quantity,
                                "25.5000".replace("5\\.", "5."))))
                .andReturn();
        assertThat(posted.getResponse().getStatus()).isEqualTo(201);
        return com.jayway.jsonpath.JsonPath.read(
                posted.getResponse().getContentAsString(), "$.id");
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

    /** Completes the fiscal face so the tenant can emit (RFC + régimen + CP). */
    private void completeEmisor(Seeded seeded) throws Exception {
        mockMvc.perform(put("/api/v1/invoices/emisor/{id}", seeded.legalEntityId())
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"taxId":"%s","fiscalRegime":"601","zipCode":"03100"}
                                """.formatted(REGEX_RFC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.rfc").value(REGEX_RFC))
                .andExpect(jsonPath("$.fiscalRegime").value("601"));
    }

    private String receptorBody(String tag) {
        // a well-formed RFC for the receptor: 4 letters + 6 digits + 3 alnum —
        // digits derived from the tag so repeated calls differ without colliding
        // with the emisor's RFC (MexicanTaxId is the shared format guard).
        String rfc = "RECE" + String.format("%06d", Math.abs(tag.hashCode()) % 1_000_000) + "X1A";
        return """
                {"rfc":"%s","name":"Cliente %s SA de CV","regime":"601",
                 "use":"G01","zip":"06600"}
                """.formatted(rfc, tag);
    }

    // ---- tests ----

    @Test
    void invoicingEndpointsFailClosedWithoutAVerifiedTenant() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(get("/api/v1/invoices"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/invoices").cookie(sessionOf(seeded)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void issuingWithoutEmisorFiscalFaceFailsFast() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        Variant variant = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8), "25.5000");
        String saleId = createSaleExact(seeded, variant, 1, "fa-" + UUID.randomUUID(), "25.50");

        // emisor exists but is fiscal-INCOMPLETE (no RFC/régimen/CP yet)
        mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":%s}
                                """.formatted(saleId, receptorBody(UUID.randomUUID().toString())))
                        .characterEncoding("UTF-8"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMISOR_INCOMPLETE"));
    }

    @Test
    void satCatalogCodesAreValidatedAndMockStampCompletesTheLifecycle() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        completeEmisor(seeded);
        Variant variant = seedVariant(seeded, "cfd", "25.5000");
        String saleId = createSaleExact(seeded, variant, 1, "cf-" + UUID.randomUUID(), "25.50");

        // invalid SAT regime for the receptor -> 400 (sat_catalog is the guard)
        mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":{"rfc":"%s","name":"Cliente","regime":"999",
                                 "use":"G01","zip":"06600"}}
                                """.formatted(saleId, "RECE9012XX")))
                .andExpect(status().isBadRequest());

        // malformed receiver RFC -> 400 via the shared MexicanTaxId guard
        mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":{"rfc":"BADRFC","name":"Cliente","regime":"601",
                                 "use":"G01","zip":"06600"}}
                                """.formatted(saleId)))
                .andExpect(status().isBadRequest());

        // FIRST valid issuance: 201 + STAMPED + folio fiscal assigned (Mock PAC)
        MvcResult issued = mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":%s}
                                """.formatted(saleId, receptorBody(UUID.randomUUID().toString())))
                        .characterEncoding("UTF-8"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folioLabel").value("FA-000001"))
                .andExpect(jsonPath("$.kind").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.saleId").value(saleId))
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.fiscalId").isNotEmpty())
                .andExpect(jsonPath("$.lines[0].unitSat").value("H87"))
                .andExpect(jsonPath("$.total").value("25.50"))
                .andReturn();
        String body = issued.getResponse().getContentAsString();
        String invoiceId = com.jayway.jsonpath.JsonPath.read(body, "$.id");

        // duplicate for the SAME sale -> 409 with the existing folio
        mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":%s}
                                """.formatted(saleId, receptorBody(UUID.randomUUID().toString())))
                        .characterEncoding("UTF-8"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("FA-000001")));

        // cancellation with motive 04 -> rejected by domain (no longer allowed)
        mockMvc.perform(post("/api/v1/invoices/{id}/cancel", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"motive\":\"04\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CANCEL_MOTIVE"));

        // valid cancellation with motivo SAT 01 -> 200 CANCELED
        mockMvc.perform(post("/api/v1/invoices/{id}/cancel", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"motive\":\"01\",\"note\":\"Errores de captura\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.cancelMotive").value("01"))
                .andExpect(jsonPath("$.cancelNote").value("Errores de captura"));

        // double cancel -> 409
        mockMvc.perform(post("/api/v1/invoices/{id}/cancel", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"motive\":\"02\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICE_ALREADY_CANCELED"));

        // AFTER cancellation the sale is issueable again (new folio = 2)
        mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":%s}
                                """.formatted(saleId, receptorBody(UUID.randomUUID().toString())))
                        .characterEncoding("UTF-8"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folioLabel").value("FA-000002"));
    }

    @Test
    void globalInvoiceConsolidatesThePeriodAndIsUniquePerMonth() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        completeEmisor(seeded);

        Variant a = seedVariant(seeded, "glA", "10.0000");
        Variant b = seedVariant(seeded, "glB", "5.0000");
        String saleA = createSaleExact(seeded, a, 2, "gl-a-" + UUID.randomUUID(), "20.00");
        String saleB = createSaleExact(seeded, b, 1, "gl-b-" + UUID.randomUUID(), "5.00");
        assertThat(saleA).isNotEmpty();
        assertThat(saleB).isNotEmpty();

        // empty candidate month (NEXT month, no sales there) -> 409 shape
        mockMvc.perform(post("/api/v1/invoices/global")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"periodYear\":2098,\"periodMonth\":\"1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_INVOICEABLE_SALES"));

        // consolidate the CURRENT month -> 201 global with XAXX010101000
        String currentPeriod = "{" +
                "\"periodYear\":" + java.time.LocalDate.now().getYear() + "," +
                "\"periodMonth\":\"" + java.time.LocalDate.now().getMonthValue() + "\"}";

        MvcResult issued = mockMvc.perform(post("/api/v1/invoices/global")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(currentPeriod))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("GLOBAL"))
                .andExpect(jsonPath("$.receptorRfc").value("XAXX010101000"))
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.total").value("25.00"))
                .andReturn();
        String body = issued.getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.periodYear"))
                .isEqualTo(java.time.LocalDate.now().getYear());

        // second global for the SAME period -> 409 GLOBAL_ALREADY_EXISTS
        mockMvc.perform(post("/api/v1/invoices/global")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(currentPeriod))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GLOBAL_ALREADY_EXISTS"));
    }

    @Test
    void satCatalogsAreReadableAndCrossTenantInvoicesInvisible() throws Exception {
        var ownerA = seedUser("invA");
        var ownerB = seedUser("invB");
        completeEmisor(ownerA);
        completeEmisor(ownerB);

        // sat_catalog is WORLD-readable reference data
        mockMvc.perform(get("/api/v1/invoices/sat/catalogs")
                        .param("catalog", "USO_CFDI")
                        .cookie(sessionOf(ownerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].code").value("G01"));

        // tenant B cannot READ tenant A's documents
        String tenantA = ownerA.organizationId().toString();
        Variant variantA = seedVariant(ownerA, "xti", "12.0000");
        String saleA = createSaleExact(ownerA, variantA, 1, "xt-" + UUID.randomUUID(), "12.00");
        MvcResult issued = mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(ownerA))
                        .header("X-Tenant-Id", tenantA)
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":%s}
                                """.formatted(saleA, receptorBody(UUID.randomUUID().toString())))
                        .characterEncoding("UTF-8"))
                .andExpect(status().isCreated())
                .andReturn();
        String invoiceA = com.jayway.jsonpath.JsonPath.read(
                issued.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceA)
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", ownerB.organizationId().toString()))
                .andExpect(status().isNotFound());

        // EVEN KNOWING tenant A's legal entity id, B cannot WRITE over it
        mockMvc.perform(put("/api/v1/invoices/emisor/{id}", ownerA.legalEntityId())
                        .cookie(sessionOf(ownerB))
                        .header("X-Tenant-Id", ownerB.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"fiscalRegime\":\"601\",\"zipCode\":\"00000\"}"))
                .andExpect(status().isNotFound());
    }
}
