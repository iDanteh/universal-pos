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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FISCAL TRAMO 2 (fase 11 · ADR-021): IEPS->IVA chain (IEPS on the net, IVA
 * on the IEPS-inclusive base, tax_total = SUM of traslados), document-level
 * federal retenciones (snapshot + total formula), and the payment complement
 * (CFDI tipo P) with the outstanding-balance guard.
 *
 * Exact numbers used throughout: price 100.00, IVA16 + IEPS265 ->
 * IEPS 26.50, IVA (100+26.50)x16% = 20.24, taxes 46.74, total 146.74.
 */
@AutoConfigureMockMvc
class FiscalTramo2ApiIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String REGEX_RFC = "GME800410HM2";

    private record Seeded(UUID organizationId, UUID legalEntityId, String email,
                          String password) {
    }

    private record Variant(UUID variantId, String sku) {
    }

    private Seeded seedUser(String tag) {
        String email = "tramo2-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org tramo2 " + tag,
                email,
                "Owner " + tag,
                "Fiscales tramo2 " + tag + " SA de CV",
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

    private void completeEmisor(Seeded seeded) throws Exception {
        mockMvc.perform(put("/api/v1/invoices/emisor/{id}", seeded.legalEntityId())
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"taxId":"%s","fiscalRegime":"601","zipCode":"03100"}
                                """.formatted(REGEX_RFC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true));
    }

    /** Variant with IVA (required) + OPTIONAL IEPS slot (v0.11 · ADR-021). */
    private Variant seedVariant(Seeded seeded, String tag, String price,
                                String taxRateCode, String iepsRateCode) throws Exception {
        String ieps = iepsRateCode == null
                ? "" : ",\"iepsRateCode\":\"" + iepsRateCode + "\"";
        String tax = taxRateCode == null
                ? "" : ",\"taxRateCode\":\"" + taxRateCode + "\"";
        MvcResult created = mockMvc.perform(post("/api/v1/catalog/products")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"name":"Producto %s","baseUnit":"PZ","variants":[
                                  {"sku":"t2-%s","unit":"PZ","price":"%s"%s%s}]}
                                """.formatted(tag, tag, price, tax, ieps)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        return new Variant(
                UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].id")),
                com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].sku"));
    }

    private MvcResult createSale(Seeded seeded, Variant variant, int quantity,
                                 String key, String cash) throws Exception {
        return mockMvc.perform(post("/api/v1/sales")
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
    }

    private String receptorBody(String tag) {
        String rfc = "RECE" + String.format("%06d", Math.abs(tag.hashCode()) % 1_000_000) + "X1A";
        return """
                {"rfc":"%s","name":"Cliente %s SA de CV","regime":"601",
                 "use":"G01","zip":"06600"}
                """.formatted(rfc, tag);
    }

    private MvcResult issueInvoice(Seeded seeded, String saleId, String withholdingsJson)
            throws Exception {
        String withholdings = withholdingsJson == null
                ? "" : ",\"withholdings\":" + withholdingsJson;
        return mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":%s%s}
                                """.formatted(saleId,
                                receptorBody(UUID.randomUUID().toString()), withholdings))
                        .characterEncoding("UTF-8"))
                .andReturn();
    }

    /** XML preview of an invoice, read inside an RLS-bound transaction. The
     * column is jsonb ({provider, xml, signed}) — JsonPath unwraps the xml. */
    private String satDocument(UUID tenantId, String invoiceId) {
        String json = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
            return jdbc.queryForObject(
                    "SELECT sat_document FROM invoice WHERE id = ?::uuid",
                    String.class, invoiceId);
        });
        return com.jayway.jsonpath.JsonPath.read(json, "$.xml");
    }

    // ---- tests ----

    @Test
    void iepsChainsIntoIvaAndTheWithholdingSnapshotLowersTheTotal() throws Exception {
        var seeded = seedUser("chain");
        String tenant = seeded.organizationId().toString();
        completeEmisor(seeded);

        Variant variant = seedVariant(seeded, "ieps", "100.0000", "IVA16", "IEPS265");
        MvcResult sale = createSale(seeded, variant, 1, "t2-chain-" + UUID.randomUUID(),
                "146.74");
        String saleId = com.jayway.jsonpath.JsonPath.read(
                sale.getResponse().getContentAsString(), "$.id");

        // ADR-021 chain: IEPS 26.50 on the net, IVA 20.24 on 126.50.
        mockMvc.perform(get("/api/v1/sales/{id}", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value("100.00"))
                .andExpect(jsonPath("$.taxTotal").value("46.74"))
                .andExpect(jsonPath("$.total").value("146.74"))
                .andExpect(jsonPath("$.lines[0].iepsRateCode").value("IEPS265"))
                .andExpect(jsonPath("$.lines[0].iepsTotal").value("26.50"))
                .andExpect(jsonPath("$.lines[0].taxRateCode").value("IVA16"))
                .andExpect(jsonPath("$.lines[0].taxTotal").value("46.74"));

        // FA with a federal ISR retention: net 100 x 10% = 10.00 off the total.
        MvcResult issued = issueInvoice(seeded, saleId, "[\"RETISR10\"]");
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        String invoiceId = com.jayway.jsonpath.JsonPath.read(
                issued.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.subtotal").value("100.00"))
                .andExpect(jsonPath("$.taxTotal").value("46.74"))
                .andExpect(jsonPath("$.withholdingTotal").value("10.00"))
                .andExpect(jsonPath("$.total").value("136.74"))
                .andExpect(jsonPath("$.outstanding").value("136.74"))
                .andExpect(jsonPath("$.withholdings.length()").value(1))
                .andExpect(jsonPath("$.withholdings[0].code").value("RETISR10"))
                .andExpect(jsonPath("$.withholdings[0].scheme").value("ISR"))
                .andExpect(jsonPath("$.withholdings[0].rate").value("0.100000"))
                .andExpect(jsonPath("$.withholdings[0].amount").value("10.00"))
                .andExpect(jsonPath("$.lines[0].iepsRateCode").value("IEPS265"))
                .andExpect(jsonPath("$.lines[0].iepsTotal").value("26.50"))
                .andExpect(jsonPath("$.lines[0].taxTotal").value("20.24"));

        // The XML preview carries BOTH traslados + the Retenciones block.
        String xml = satDocument(seeded.organizationId(), invoiceId);
        assertThat(xml).contains("TipoDeComprobante=\"I\"");
        assertThat(xml).contains("TotalImpuestosTrasladados=\"46.74\"");
        assertThat(xml).contains("<cfdi:Traslado Base=\"100.00\" Impuesto=\"003\"");
        assertThat(xml).contains("<cfdi:Traslado Base=\"126.50\" Impuesto=\"002\"");
        assertThat(xml).contains("TotalImpuestosRetenidos=\"10.00\"");
        assertThat(xml).contains("<cfdi:Retencion Base=\"100.00\" Impuesto=\"001\"");
    }

    @Test
    void withholdingCatalogIsExposedAndFailClosedRulesHold() throws Exception {
        var seeded = seedUser("rules");
        String tenant = seeded.organizationId().toString();
        completeEmisor(seeded);

        // federal catalog (V19): 3 seeds, readable by the tenant session
        // (order: scheme, rate DESC -> ISR 10%, ISR 1.25%, IVA 2/3)
        mockMvc.perform(get("/api/v1/catalog/withholdings")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("RETISR10"))
                .andExpect(jsonPath("$[1].code").value("RETISR125"))
                .andExpect(jsonPath("$[2].code").value("RETIVA23"))
                .andExpect(jsonPath("$[2].rate").value("0.106667"));

        // IVA0 sale: RETIVA23 requires a documento WITH transferred IVA -> 409
        Variant zero = seedVariant(seeded, "zero", "100.0000", "IVA0", null);
        String zeroSale = com.jayway.jsonpath.JsonPath.read(
                createSale(seeded, zero, 1, "t2-zero-" + UUID.randomUUID(), "100.00")
                        .getResponse().getContentAsString(), "$.id");
        MvcResult noIva = issueInvoice(seeded, zeroSale, "[\"RETIVA23\"]");
        assertThat(noIva.getResponse().getStatus()).isEqualTo(409);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(
                noIva.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("WITHHOLDING_REQUIRES_IVA");

        // unknown code -> 400; duplicated code -> 400 (both IllegalArgumentException)
        Variant taxed = seedVariant(seeded, "dup", "50.0000", "IVA16", null);
        String taxedSale = com.jayway.jsonpath.JsonPath.read(
                createSale(seeded, taxed, 1, "t2-dup-" + UUID.randomUUID(), "58.00")
                        .getResponse().getContentAsString(), "$.id");
        assertThat(issueInvoice(seeded, taxedSale, "[\"RETNOPE\"]")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(issueInvoice(seeded, taxedSale, "[\"RETISR10\",\"RETISR10\"]")
                .getResponse().getStatus()).isEqualTo(400);

        // a global invoice NEVER carries retenciones (only the FA command does)
        MvcResult global = mockMvc.perform(post("/api/v1/invoices/global")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"periodYear\":" + java.time.LocalDate.now().getYear()
                                + ",\"periodMonth\":\""
                                + java.time.LocalDate.now().getMonthValue() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<?>>read(
                global.getResponse().getContentAsString(), "$.withholdings")).isEmpty();
    }

    @Test
    void paymentComplementSettlesTheBalanceAndCancelReleasesIt() throws Exception {
        var seeded = seedUser("pag");
        String tenant = seeded.organizationId().toString();
        completeEmisor(seeded);

        Variant variant = seedVariant(seeded, "pag", "100.0000", "IVA16", null);
        String saleId = com.jayway.jsonpath.JsonPath.read(
                createSale(seeded, variant, 1, "t2-pag-" + UUID.randomUUID(), "116.00")
                        .getResponse().getContentAsString(), "$.id");

        MvcResult issued = issueInvoice(seeded, saleId, null);
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        String invoiceId = com.jayway.jsonpath.JsonPath.read(
                issued.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(jsonPath("$.total").value("116.00"))
                .andExpect(jsonPath("$.outstanding").value("116.00"));

        // partial PAG 50.00 (forma 03 transferencia) -> tipo P stamped, serie PG
        MvcResult partial = mockMvc.perform(post("/api/v1/invoices/{id}/payments", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"amount\":\"50.00\",\"method\":\"03\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PAYMENT"))
                .andExpect(jsonPath("$.serie").value("PG"))
                .andExpect(jsonPath("$.folioLabel").value("PG-000001"))
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.paymentAmount").value("50.00"))
                .andExpect(jsonPath("$.paymentMethod").value("03"))
                .andExpect(jsonPath("$.total").value("50.00"))
                .andReturn();
        String pagId = com.jayway.jsonpath.JsonPath.read(
                partial.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(jsonPath("$.outstanding").value("66.00"));

        // over the balance -> 409 PAG_OVER_BALANCE
        mockMvc.perform(post("/api/v1/invoices/{id}/payments", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"amount\":\"66.01\",\"method\":\"03\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAG_OVER_BALANCE"));

        // a PAG is not a payment TARGET -> 409 PAG_TARGET_INVALID
        mockMvc.perform(post("/api/v1/invoices/{id}/payments", pagId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"amount\":\"10.00\",\"method\":\"03\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAG_TARGET_INVALID"));

        // settle the exact remainder -> outstanding 0.00
        mockMvc.perform(post("/api/v1/invoices/{id}/payments", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"amount\":\"66.00\",\"method\":\"01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folioLabel").value("PG-000002"));
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(jsonPath("$.outstanding").value("0.00"));

        // cancelling the FIRST PAG releases its amount (balance back to 50.00)
        mockMvc.perform(post("/api/v1/invoices/{id}/cancel", pagId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"motive\":\"02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(jsonPath("$.outstanding").value("50.00"));

        // the tipo P XML names the pago concept (84111506) and no taxes
        String xml = satDocument(seeded.organizationId(),
                (String) com.jayway.jsonpath.JsonPath.read(
                        partial.getResponse().getContentAsString(), "$.id"));
        assertThat(xml).contains("TipoDeComprobante=\"P\"");
        assertThat(xml).contains("ClaveProdServ=\"84111506\"");
        assertThat(xml).doesNotContain("FormaPago=");
    }

    @Test
    void paymentComplementRequiresAStampedTarget() throws Exception {
        var seeded = seedUser("nostamp");
        String tenant = seeded.organizationId().toString();
        completeEmisor(seeded);

        Variant variant = seedVariant(seeded, "ns", "80.0000", "IVA16", null);
        String saleId = com.jayway.jsonpath.JsonPath.read(
                createSale(seeded, variant, 1, "t2-ns-" + UUID.randomUUID(), "92.80")
                        .getResponse().getContentAsString(), "$.id");
        String invoiceId = com.jayway.jsonpath.JsonPath.read(
                issueInvoice(seeded, saleId, null).getResponse().getContentAsString(), "$.id");

        // cancel the income CFDI -> it is no longer a valid payment target
        mockMvc.perform(post("/api/v1/invoices/{id}/cancel", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"motive\":\"02\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/invoices/{id}/payments", invoiceId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"amount\":\"10.00\",\"method\":\"03\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAG_TARGET_NOT_STAMPED"));

        // unknown forma de pago -> 400 (sat_catalog guard)
        String otherSale = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/v1/sales")
                                .cookie(sessionOf(seeded))
                                .header("X-Tenant-Id", tenant)
                                .header("Idempotency-Key", "t2-ns2-" + UUID.randomUUID())
                                .contentType("application/json")
                                .content("""
                                        {"lines":[{"variantId":"%s","quantity":1}],
                                         "payments":[{"method":"CASH","amount":"92.80"}]}
                                        """.formatted(variant.variantId())))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(), "$.id");
        String otherInvoice = com.jayway.jsonpath.JsonPath.read(
                issueInvoice(seeded, otherSale, null).getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/api/v1/invoices/{id}/payments", otherInvoice)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"amount\":\"10.00\",\"method\":\"77\"}"))
                .andExpect(status().isBadRequest());
    }
}
