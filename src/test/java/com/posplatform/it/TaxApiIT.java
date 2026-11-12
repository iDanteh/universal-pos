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
 * TAX domain REST (v0.8 · ADR-018): variant-level tax_rate_code from the
 * GLOBAL catalog, per-line snapshot tax (11.00 -> tax on the line net),
 * legacy behavior untouched (no code = tax 0), and the CFDI mirroring the
 * taxes exactly as charged (individual + global paths).
 */
@AutoConfigureMockMvc
class TaxApiIT extends AbstractDatabaseIT {

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

    private static final String EMISOR_RFC = "GME800410HM2";

    private Seeded seedUser(String tag) {
        String email = "tax-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org tax " + tag,
                email,
                "Owner " + tag,
                "Impuestos " + tag + " SA de CV",
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

    private Variant seedVariant(Seeded seeded, String tag, String price,
                                String taxRateCode) throws Exception {
        String variant = "{\"sku\":\"%s\",\"unit\":\"PZ\",\"price\":\"%s\""
                .formatted(tag, price)
                + (taxRateCode == null ? "" : ",\"taxRateCode\":\"" + taxRateCode + "\"")
                + "}";
        MvcResult created = mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"name\":\"Producto %s\",\"baseUnit\":\"PZ\",\"variants\":[%s]}"
                                .formatted(tag, variant)))
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

    /** Completes the fiscal face so the tenant can emit (RFC + régimen + CP). */
    private void completeEmisor(Seeded seeded) throws Exception {
        mockMvc.perform(put("/api/v1/invoices/emisor/{id}", seeded.legalEntityId())
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"taxId":"%s","fiscalRegime":"601","zipCode":"03100"}
                                """.formatted(EMISOR_RFC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true));
    }

    private String receptorBody(String tag) {
        String rfc = "RECE" + String.format("%06d", Math.abs(tag.hashCode()) % 1_000_000) + "X1A";
        return """
                {"rfc":"%s","name":"Cliente %s SA de CV","regime":"601",
                 "use":"G01","zip":"06600"}
                """.formatted(rfc, tag);
    }

    /** Issues the individual CFDI of one sale and returns the invoice body. */
    private String issueInvoice(Seeded seeded, String saleId) throws Exception {
        MvcResult issued = mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":%s}
                                """.formatted(saleId,
                                receptorBody(UUID.randomUUID().toString())))
                        .characterEncoding("UTF-8"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andReturn();
        return issued.getResponse().getContentAsString();
    }

    // ---- tests ----

    @Test
    void saleWithTaxedVariantSnapshotsTheLineTaxAndLegacyVariantsStayZero() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();

        Variant taxed = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8),
                "100.0000", "IVA16");
        Variant legacy = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8),
                "50.0000", null);

        // taxed line: tax computed ONCE on the line net (100.00 x 0.16 = 16.00)
        MvcResult sale = mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "tx-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":1}],
                                 "payments":[{"method":"CASH","amount":"116.00"}]}
                                """.formatted(taxed.variantId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value("100.00"))
                .andExpect(jsonPath("$.discountTotal").value("0.00"))
                .andExpect(jsonPath("$.taxTotal").value("16.00"))
                .andExpect(jsonPath("$.total").value("116.00"))
                .andExpect(jsonPath("$.lines[0].taxRateCode").value("IVA16"))
                .andExpect(jsonPath("$.lines[0].taxTotal").value("16.00"))
                .andReturn();
        String saleIdTaxed = com.jayway.jsonpath.JsonPath.read(
                sale.getResponse().getContentAsString(), "$.id");
        assertThat(saleIdTaxed).isNotEmpty();

        // payments must cover the taxed total: 100.00 for a 116.00 sale -> 400
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "tx-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":1}],
                                 "payments":[{"method":"CASH","amount":"100.00"}]}
                                """.formatted(taxed.variantId())))
                .andExpect(status().isBadRequest());

        // legacy variant (no code): totals EXACTLY as before v0.8
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "tx-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":1}],
                                 "payments":[{"method":"CASH","amount":"50.00"}]}
                                """.formatted(legacy.variantId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxTotal").value("0.00"))
                .andExpect(jsonPath("$.total").value("50.00"))
                .andExpect(jsonPath("$.lines[0].taxRateCode").doesNotExist())
                .andExpect(jsonPath("$.lines[0].taxTotal").value("0.00"));

        // mixed cart (taxed + legacy) sums the already-rounded line taxes
        mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "tx-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":1},
                                          {"variantId":"%s","quantity":1}],
                                 "payments":[{"method":"CARD","amount":"166.00"}]}
                                """.formatted(taxed.variantId(), legacy.variantId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value("150.00"))
                .andExpect(jsonPath("$.taxTotal").value("16.00"))
                .andExpect(jsonPath("$.total").value("166.00"));
    }

    @Test
    void unknownTaxRateCodeIsRejectedAgainstTheCatalog() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        // a blank code is legacy-compatible (sin impuesto)
        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"name":"Sin impuesto","baseUnit":"PZ","variants":[
                                  {"sku":"skx-blank","unit":"PZ","price":"10.0000","taxRateCode":"  "}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.variants[0].taxRateCode").doesNotExist());

        // a non-catalog code FAILS CLOSED (rates live in migrations, not logic)
        mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"name":"Mal impuesto","baseUnit":"PZ","variants":[
                                  {"sku":"skx-bad","unit":"PZ","price":"10.0000","taxRateCode":"IVA99"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("IVA99")));
    }

    @Test
    void taxRatesEndpointListsTheGlobalCatalog() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(get("/api/v1/catalog/taxes")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[?(@.code=='IVA16')].scheme").value(
                        org.hamcrest.Matchers.hasItem("IVA")))
                .andExpect(jsonPath("$[?(@.code=='IVA16')].rate").value(
                        org.hamcrest.Matchers.hasItem("0.1600")));
    }

    @Test
    void individualInvoiceMirrorsTheSaleTaxesExactly() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        completeEmisor(seeded);
        Variant taxed = seedVariant(seeded, "ivx", "100.0000", "IVA16");
        String saleId = createSaleExact(seeded, taxed, 1, "iv-" + UUID.randomUUID(), "116.00");

        String body = issueInvoice(seeded, saleId);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.taxTotal"))
                .isEqualTo("16.00");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.total"))
                .isEqualTo("116.00");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.lines[0].taxRateCode"))
                .isEqualTo("IVA16");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.lines[0].taxTotal"))
                .isEqualTo("16.00");
    }

    @Test
    void globalInvoiceAggregatesThePeriodTaxesWithoutRerounding() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        completeEmisor(seeded);

        Variant a = seedVariant(seeded, "glxA", "100.0000", "IVA16");
        Variant b = seedVariant(seeded, "glxB", "100.0000", "IVA16");
        createSaleExact(seeded, a, 1, "glx-a-" + UUID.randomUUID(), "116.00");
        createSaleExact(seeded, b, 1, "glx-b-" + UUID.randomUUID(), "116.00");

        String currentPeriod = "{" +
                "\"periodYear\":" + java.time.LocalDate.now().getYear() + "," +
                "\"periodMonth\":\"" + java.time.LocalDate.now().getMonthValue() + "\"}";

        mockMvc.perform(post("/api/v1/invoices/global")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content(currentPeriod))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("GLOBAL"))
                .andExpect(jsonPath("$.receptorRfc").value("XAXX010101000"))
                .andExpect(jsonPath("$.status").value("STAMPED"))
                // subtotal 200.00, tax = SUM of the per-line snapshots (32.00)
                .andExpect(jsonPath("$.subtotal").value("200.00"))
                .andExpect(jsonPath("$.taxTotal").value("32.00"))
                .andExpect(jsonPath("$.total").value("232.00"))
                .andExpect(jsonPath("$.lines[0].taxRateCode").value("IVA16"))
                .andExpect(jsonPath("$.lines[0].taxTotal").value("16.00"))
                .andExpect(jsonPath("$.lines[1].taxTotal").value("16.00"));
    }
}
