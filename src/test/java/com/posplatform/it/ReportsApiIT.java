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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REPORTS (fase 13 · ADR-023): read-only projections over the persisted
 * ledger. Exact numbers: price 100.00 + IVA16 + IEPS265 -> IEPS 26.50, IVA
 * 20.24, taxes 46.74, total 146.74.
 *
 * The scenario rings TWO sales, returns ONE unit (CASH) and stamps ONE
 * invoice with RETISR10, so every aggregate has a positive and a negative
 * term: gross 293.48 - refunds 146.74 = net 146.74; NET taxes 46.74 (one
 * unit transferred back); withholding 10.00.
 */
@AutoConfigureMockMvc
class ReportsApiIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AppUserRepository appUserRepository;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String REGEX_RFC = "GME800410HM2";

    private record Seeded(UUID organizationId, UUID legalEntityId, String email,
                          String password) {
    }

    private record Variant(UUID variantId, String sku) {
    }

    private Seeded seedUser(String tag) {
        String email = "reports-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org reportes " + tag,
                email,
                "Owner " + tag,
                "Reportes " + tag + " SA de CV",
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
                .andExpect(status().isOk());
    }

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
                                  {"sku":"t13-%s","unit":"PZ","price":"%s"%s%s}]}
                                """.formatted(tag, tag, price, tax, ieps)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        return new Variant(
                UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].id")),
                com.jayway.jsonpath.JsonPath.read(body, "$.variants[0].sku"));
    }

    private String createSale(Seeded seeded, Variant variant, int quantity,
                              String key, String method, String amount) throws Exception {
        MvcResult posted = mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":%d}],
                                 "payments":[{"method":"%s","amount":"%s"}]}
                                """.formatted(variant.variantId(), quantity, method, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                posted.getResponse().getContentAsString(), "$.id");
    }

    private String firstSaleLine(Seeded seeded, String saleId) throws Exception {
        MvcResult sale = mockMvc.perform(get("/api/v1/sales/{id}", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                sale.getResponse().getContentAsString(), "$.lines[0].id");
    }

    private void createReturn(Seeded seeded, String saleId, String saleLineId,
                              String quantity, String method) throws Exception {
        mockMvc.perform(post("/api/v1/returns/{saleId}", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .header("Idempotency-Key", "rt-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"saleLineId":"%s","quantity":%s}],
                                 "refundMethod":"%s"}
                                """.formatted(saleLineId, quantity, method)))
                .andExpect(status().isCreated());
    }

    private void issueWithholdingInvoice(Seeded seeded, String saleId) throws Exception {
        mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .characterEncoding("UTF-8")
                        .content("""
                                {"saleId":"%s","receptor":{
                                  "rfc":"RECE010101AB1","name":"Cliente Reportes SA de CV",
                                  "regime":"601","use":"G01","zip":"06600"},
                                 "withholdings":["RETISR10"]}
                                """.formatted(saleId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.withholdingTotal").value("10.00"));
    }

    private String range(LocalDate from, LocalDate to) {
        return "?from=%s&to=%s".formatted(from, to);
    }

    // ---- tests ----

    @Test
    void reportsFailClosedWithoutAVerifiedTenant() throws Exception {
        var seeded = seedUser("closed");
        String tenant = seeded.organizationId().toString();
        String range = range(LocalDate.now().minusDays(1), LocalDate.now());

        // no session -> 401 on every report
        mockMvc.perform(get("/api/v1/reports/sales" + range))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/taxes" + range))
                .andExpect(status().isUnauthorized());

        // authenticated without tenant -> 404 (never a guess)
        mockMvc.perform(get("/api/v1/reports/sales" + range)
                        .cookie(sessionOf(seeded)))
                .andExpect(status().isNotFound());

        // random tenant header -> not a member -> 404
        mockMvc.perform(get("/api/v1/reports/taxes" + range)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        // unknown drawer -> 404 inside the verified tenant
        mockMvc.perform(get("/api/v1/reports/z/{id}", UUID.randomUUID())
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsRejectInvalidOrOversizedRanges() throws Exception {
        var seeded = seedUser("range");
        String tenant = seeded.organizationId().toString();
        Cookie session = sessionOf(seeded);
        LocalDate today = LocalDate.now();

        mockMvc.perform(get("/api/v1/reports/sales" + range(today, today.minusDays(1)))
                        .cookie(session)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());

        mockMvc.perform(get("/api/v1/reports/taxes"
                                + range(today.minusDays(400), today))
                        .cookie(session)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void salesAndTaxesReportsAggregateSalesReturnsAndWithholdings() throws Exception {
        var seeded = seedUser("ledger");
        String tenant = seeded.organizationId().toString();
        Cookie session = sessionOf(seeded);
        completeEmisor(seeded);

        Variant variant = seedVariant(seeded, "ledger", "100.0000", "IVA16", "IEPS265");
        String firstSale = createSale(seeded, variant, 1, "rep-1-" + UUID.randomUUID(),
                "CASH", "146.74");
        createSale(seeded, variant, 1, "rep-2-" + UUID.randomUUID(), "CASH", "146.74");
        createReturn(seeded, firstSale, firstSaleLine(seeded, firstSale), "1", "CASH");
        issueWithholdingInvoice(seeded, firstSale);

        LocalDate today = LocalDate.now();
        String window = range(today.minusDays(1), today.plusDays(1));

        mockMvc.perform(get("/api/v1/reports/sales" + window)
                        .cookie(session)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.timeZone").value("America/Mexico_City"))
                // Two completed sales, one return: gross 293.48, net 146.74.
                .andExpect(jsonPath("$.salesCount").value(2))
                .andExpect(jsonPath("$.canceledCount").value(0))
                .andExpect(jsonPath("$.refundsCount").value(1))
                .andExpect(jsonPath("$.subtotal").value("200.00"))
                .andExpect(jsonPath("$.discountTotal").value("0.00"))
                .andExpect(jsonPath("$.taxTotal").value("93.48"))
                .andExpect(jsonPath("$.iepsTotal").value("53.00"))
                .andExpect(jsonPath("$.ivaTotal").value("40.48"))
                .andExpect(jsonPath("$.withheldTotal").value("10.00"))
                .andExpect(jsonPath("$.grossSales").value("293.48"))
                .andExpect(jsonPath("$.refundsTotal").value("146.74"))
                .andExpect(jsonPath("$.netSales").value("146.74"))
                .andExpect(jsonPath("$.averageTicket").value("146.74"))
                // Payment mix is the gross inflow (returns are separate).
                .andExpect(jsonPath("$.byPaymentMethod[0].method").value("CASH"))
                .andExpect(jsonPath("$.byPaymentMethod[0].count").value(2))
                .andExpect(jsonPath("$.byPaymentMethod[0].amount").value("293.48"))
                // Top products ranks the snapshot name/sku of SOLD units.
                .andExpect(jsonPath("$.topProducts[0].sku").value("T13-LEDGER"))
                .andExpect(jsonPath("$.topProducts[0].quantity").value("2"))
                .andExpect(jsonPath("$.topProducts[0].amount").value("200.00"))
                .andExpect(jsonPath("$.byDay[0].salesCount").value(2))
                .andExpect(jsonPath("$.byDay[0].total").value("293.48"))
                .andExpect(jsonPath("$.byDay[0].refunds").value("146.74"));

        mockMvc.perform(get("/api/v1/reports/taxes" + window)
                        .cookie(session)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.from").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.range.to").value(today.plusDays(1).toString()))
                // NET transferred: 1 unit effectively left the business.
                .andExpect(jsonPath("$.transferred.length()").value(2))
                .andExpect(jsonPath("$.transferred[0].code").value("IEPS265"))
                .andExpect(jsonPath("$.transferred[0].scheme").value("IEPS"))
                .andExpect(jsonPath("$.transferred[0].rate").value("0.265"))
                .andExpect(jsonPath("$.transferred[0].base").value("100.00"))
                .andExpect(jsonPath("$.transferred[0].amount").value("26.50"))
                .andExpect(jsonPath("$.transferred[1].code").value("IVA16"))
                .andExpect(jsonPath("$.transferred[1].scheme").value("IVA"))
                .andExpect(jsonPath("$.transferred[1].base").value("126.50"))
                .andExpect(jsonPath("$.transferred[1].amount").value("20.24"))
                .andExpect(jsonPath("$.transferredTotal").value("46.74"))
                .andExpect(jsonPath("$.withheld[0].code").value("RETISR10"))
                .andExpect(jsonPath("$.withheld[0].scheme").value("ISR"))
                .andExpect(jsonPath("$.withheld[0].amount").value("10.00"))
                .andExpect(jsonPath("$.withheldTotal").value("10.00"));
    }

    @Test
    void zReportReconcilesTheDrawerAndKeepsForeignSessionsOut() throws Exception {
        var seeded = seedUser("zdemo");
        var stranger = seedUser("zstranger");
        String tenant = seeded.organizationId().toString();
        Cookie session = sessionOf(seeded);

        MvcResult opened = mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(session)
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"openingFloat\":\"50.00\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String cashSessionId = com.jayway.jsonpath.JsonPath.read(
                opened.getResponse().getContentAsString(), "$.id");

        Variant variant = seedVariant(seeded, "zdemo", "100.0000", "IVA16", "IEPS265");
        String saleId = createSale(seeded, variant, 1, "rep-z-" + UUID.randomUUID(),
                "CASH", "146.74");

        // Open drawer: expected = float 50.00 + CASH sale 146.74.
        mockMvc.perform(get("/api/v1/reports/z/{id}", cashSessionId)
                        .cookie(session)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingFloat").value("50.00"))
                .andExpect(jsonPath("$.salesCount").value(1))
                .andExpect(jsonPath("$.grossSales").value("146.74"))
                .andExpect(jsonPath("$.refundsTotal").value("0.00"))
                .andExpect(jsonPath("$.netSales").value("146.74"))
                .andExpect(jsonPath("$.taxTotal").value("46.74"))
                .andExpect(jsonPath("$.iepsTotal").value("26.50"))
                .andExpect(jsonPath("$.ivaTotal").value("20.24"))
                .andExpect(jsonPath("$.cashSales").value("146.74"))
                .andExpect(jsonPath("$.cashRefunds").value("0.00"))
                .andExpect(jsonPath("$.expectedCash").value("196.74"))
                .andExpect(jsonPath("$.countedCash").isEmpty())
                .andExpect(jsonPath("$.byPaymentMethod[0].method").value("CASH"))
                .andExpect(jsonPath("$.sales[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.sales[0].paymentMethods").value("CASH"));

        mockMvc.perform(post("/api/v1/cash/sessions/{id}/close", cashSessionId)
                        .cookie(session)
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"countedCash\":\"196.74\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.difference").value("0.00"));

        // Closed drawer: the stored reconciliation travels in the corte.
        mockMvc.perform(get("/api/v1/reports/z/{id}", cashSessionId)
                        .cookie(session)
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.expectedCash").value("196.74"))
                .andExpect(jsonPath("$.countedCash").value("196.74"))
                .andExpect(jsonPath("$.difference").value("0.00"));

        // A member of ANOTHER tenant cannot read this drawer (RLS fail-closed).
        mockMvc.perform(get("/api/v1/reports/z/{id}", cashSessionId)
                        .cookie(sessionOf(stranger))
                        .header("X-Tenant-Id", stranger.organizationId().toString()))
                .andExpect(status().isNotFound());
    }
}
