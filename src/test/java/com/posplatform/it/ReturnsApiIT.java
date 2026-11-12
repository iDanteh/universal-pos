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
 * RETURNS + CREDIT NOTES REST (v0.9 · ADR-019): full return restores the stock
 * and refunds EXACTLY what was charged, partial returns prorate the SNAPSHOT
 * (never re-rate), quantity guards fail closed, the drawer close expects less
 * after CASH refunds and the CFDI tipo E mirrors the return 1:1 relating the
 * original FA (TipoRelacion 01).
 */
@AutoConfigureMockMvc
class ReturnsApiIT extends AbstractDatabaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private com.posplatform.shared.tenancy.TenantTransactionRunner tenantRunner;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String EMISOR_RFC = "GME800410HM2";

    private record Seeded(UUID organizationId, UUID legalEntityId, String email,
                          String password) {
    }

    private record Variant(UUID variantId, String sku) {
    }

    private Seeded seedUser(String tag) {
        String email = "ret-%s@example.com".formatted(tag);
        String password = "S3gura-%s!".formatted(tag);

        AppUser user = new AppUser(email, "Owner " + tag);
        user.setPasswordHash(ENCODER.encode(password));
        appUserRepository.save(user);

        OnboardOrganizationResult org = onboardingService.onboard(new OnboardOrganizationCommand(
                "Org ret " + tag,
                email,
                "Owner " + tag,
                "Devoluciones " + tag + " SA de CV",
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
        String variant = "{\"sku\":\"skr-%s\",\"unit\":\"PZ\",\"price\":\"%s\""
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

    private String createWarehouse(Seeded seeded, String code) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/inventory/warehouses")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"code\":\"%s\",\"name\":\"Almacen %s\"}".formatted(code, code)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id");
    }

    private void receive(Seeded seeded, String warehouseId, Variant variant,
                         int quantity) throws Exception {
        mockMvc.perform(post("/api/v1/inventory/receipts")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"warehouseId":"%s","lines":[
                                  {"variantId":"%s","quantity":%d}],
                                 "reference":"OC-RET"}
                                """.formatted(warehouseId, variant.variantId(), quantity)))
                .andExpect(status().isCreated());
    }

    private String balanceOf(Seeded seeded, String warehouseId, Variant variant)
            throws Exception {
        MvcResult balances = mockMvc.perform(get("/api/v1/inventory/balances")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .param("warehouseId", warehouseId))
                .andExpect(status().isOk())
                .andReturn();
        String body = balances.getResponse().getContentAsString();
        java.util.List<?> rows = com.jayway.jsonpath.JsonPath.read(body, "$");
        for (Object row : rows) {
            String rowVariant = com.jayway.jsonpath.JsonPath.read(row, "$.variantId");
            if (rowVariant.equals(variant.variantId().toString())) {
                return com.jayway.jsonpath.JsonPath.read(row, "$.quantity");
            }
        }
        return "0.0000";
    }

    /** Creates a COMPLETED sale and returns its sale id. */
    private String createSale(Seeded seeded, Variant variant, int quantity,
                              String key, String method, String cash) throws Exception {
        MvcResult posted = mockMvc.perform(post("/api/v1/sales")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"variantId":"%s","quantity":%d}],
                                 "payments":[{"method":"%s","amount":"%s"}]}
                                """.formatted(variant.variantId(), quantity, method, cash)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                posted.getResponse().getContentAsString(), "$.id");
    }

    private String firstSaleLineId(Seeded seeded, String saleId) throws Exception {
        MvcResult sale = mockMvc.perform(get("/api/v1/sales/{id}", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                sale.getResponse().getContentAsString(), "$.lines[0].id");
    }

    private org.springframework.test.web.servlet.ResultActions postReturn(
            Seeded seeded, String saleId, String saleLineId,
            String quantity, String refundMethod, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/returns/{saleId}", saleId)
                .cookie(sessionOf(seeded))
                .header("X-Tenant-Id", seeded.organizationId().toString())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("""
                        {"lines":[{"saleLineId":"%s","quantity":%s}],
                         "refundMethod":"%s","note":"Devolucion de prueba"}
                        """.formatted(saleLineId, quantity, refundMethod)));
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

    /** Issues the individual CFDI of one sale; returns its body. */
    private String issueInvoice(Seeded seeded, String saleId) throws Exception {
        String rfc = "RECE" + String.format("%06d",
                Math.abs(saleId.hashCode()) % 1_000_000) + "X1A";
        MvcResult issued = mockMvc.perform(post("/api/v1/invoices")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("""
                                {"saleId":"%s","receptor":{"rfc":"%s",
                                 "name":"Cliente Dev SA de CV","regime":"601",
                                 "use":"G01","zip":"06600"}}
                                """.formatted(saleId, rfc)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andReturn();
        return issued.getResponse().getContentAsString();
    }

    private String openCash(Seeded seeded, String floatAmount) throws Exception {
        MvcResult opened = mockMvc.perform(post("/api/v1/cash/sessions")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"openingFloat\":\"%s\"}".formatted(floatAmount)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                opened.getResponse().getContentAsString(), "$.id");
    }

    // ---- tests ----

    @Test
    void fullReturnRestoresStockRefundsExactlyAndTheCloseExpectsLess() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        String warehouseId = createWarehouse(seeded, "RW" + UUID.randomUUID()
                .toString().substring(0, 6).toUpperCase());
        Variant taxed = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8),
                "100.0000", "IVA16");
        receive(seeded, warehouseId, taxed, 10);

        String sessionId = openCash(seeded, "0.00");
        String saleId = createSale(seeded, taxed, 2, "rt-" + UUID.randomUUID(),
                "CASH", "232.00");
        assertThat(balanceOf(seeded, warehouseId, taxed)).isEqualTo("8.0000");

        String saleLineId = firstSaleLineId(seeded, saleId);
        postReturn(seeded, saleId, saleLineId, "2", "CASH", "ret-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folioLabel").value("DV-000001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.saleId").value(saleId))
                .andExpect(jsonPath("$.subtotal").value("200.00"))
                .andExpect(jsonPath("$.taxTotal").value("32.00"))
                .andExpect(jsonPath("$.total").value("232.00"))
                .andExpect(jsonPath("$.refundMethod").value("CASH"))
                .andExpect(jsonPath("$.refundAmount").value("232.00"))
                .andExpect(jsonPath("$.lines[0].quantity").value("2.0000"))
                .andExpect(jsonPath("$.lines[0].taxTotal").value("32.00"));

        // the compensatory SALE_RETURN movements put the stock back
        assertThat(balanceOf(seeded, warehouseId, taxed)).isEqualTo("10.0000");

        // a second attempt exceeds the (now zero) returnable budget -> 409
        postReturn(seeded, saleId, saleLineId, "1", "CASH", "ret2-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDS"));

        // the drawer close: float 0 + 232 CASH sale - 232 CASH refund = 0.00
        mockMvc.perform(post("/api/v1/cash/sessions/{id}/close", sessionId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("""
                                {"countedCash":"0.00","countedCard":"0.00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCash").value("0.00"))
                .andExpect(jsonPath("$.difference").value("0.00"))
                .andExpect(jsonPath("$.refundsCount").value(1))
                .andExpect(jsonPath("$.cashRefunds").value("232.00"));
    }

    @Test
    void partialReturnProratesTheSnapshotAndExceedingFailsClosed() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        Variant taxed = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8),
                "100.0000", "IVA16");
        String saleId = createSale(seeded, taxed, 3, "rt-" + UUID.randomUUID(),
                "CARD", "348.00");
        String saleLineId = firstSaleLineId(seeded, saleId);

        // 1 of 3: net = 100.00, tax = 16.00 (the EXACT charge of one unit)
        String returnId = com.jayway.jsonpath.JsonPath.read(postReturn(
                        seeded, saleId, saleLineId, "1", "CARD", "pr-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value("100.00"))
                .andExpect(jsonPath("$.taxTotal").value("16.00"))
                .andExpect(jsonPath("$.total").value("116.00"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // asking 3 when only 2 remain -> 409, nothing written
        postReturn(seeded, saleId, saleLineId, "3", "CARD", "px-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_QUANTITY_EXCEEDS"));

        // the remaining 2 go through, then the budget is exhausted
        postReturn(seeded, saleId, saleLineId, "2", "CARD", "pr2-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value("232.00"));
        postReturn(seeded, saleId, saleLineId, "1", "CARD", "pr3-" + UUID.randomUUID())
                .andExpect(status().isConflict());

        // both returns list under the sale and in the tenant feed
        mockMvc.perform(get("/api/v1/returns/{saleId}", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/returns")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void returnsAreIdempotentReplays() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        Variant taxed = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8),
                "100.0000", "IVA16");
        String saleId = createSale(seeded, taxed, 1, "rt-" + UUID.randomUUID(),
                "CASH", "116.00");
        String saleLineId = firstSaleLineId(seeded, saleId);
        String key = "idem-" + UUID.randomUUID();

        String created = postReturn(seeded, saleId, saleLineId, "1", "CASH", key)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String createdId = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        // the SAME key replays: 200 with the SAME return (never a double refund)
        postReturn(seeded, saleId, saleLineId, "1", "CASH", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId));
    }

    @Test
    void onlyCompletedSalesAreReturnableAndForeignDataFailsClosed() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        String tenant = seeded.organizationId().toString();
        Variant taxed = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8),
                "100.0000", "IVA16");
        String saleId = createSale(seeded, taxed, 1, "rt-" + UUID.randomUUID(),
                "CASH", "116.00");
        String saleLineId = firstSaleLineId(seeded, saleId);

        // canceled sale: its stock already went back -> 409
        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json")
                        .content("{\"reason\":\"Cancelada\"}"))
                .andExpect(status().isOk());
        postReturn(seeded, saleId, saleLineId, "1", "CASH", "cn-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_NOT_COMPLETE"));

        // another tenant cannot even SEE the sale -> 404 (never a leak)
        var other = seedUser(UUID.randomUUID().toString().substring(0, 8));
        mockMvc.perform(post("/api/v1/returns/{saleId}", saleId)
                        .cookie(sessionOf(other))
                        .header("X-Tenant-Id", other.organizationId().toString())
                        .header("Idempotency-Key", "cn-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"lines":[{"saleLineId":"%s","quantity":1}],
                                 "refundMethod":"CASH"}
                                """.formatted(saleLineId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void creditNoteRequiresTheSaleInvoicedAndStamped() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        Variant taxed = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8),
                "100.0000", "IVA16");
        String saleId = createSale(seeded, taxed, 1, "rt-" + UUID.randomUUID(),
                "CASH", "116.00");
        String saleLineId = firstSaleLineId(seeded, saleId);
        String returnId = com.jayway.jsonpath.JsonPath.read(postReturn(
                        seeded, saleId, saleLineId, "1", "CASH", "nc-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        // no FA of the sale yet -> 409 RETURN_NOT_INVOICED
        mockMvc.perform(post("/api/v1/invoices/credit-notes")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"returnId\":\"%s\"}".formatted(returnId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_NOT_INVOICED"));
    }

    @Test
    void creditNoteMirrorsTheReturnAndRelatesTheOriginalInvoice() throws Exception {
        var seeded = seedUser(UUID.randomUUID().toString().substring(0, 8));
        completeEmisor(seeded);
        Variant taxed = seedVariant(seeded, UUID.randomUUID().toString().substring(0, 8),
                "100.0000", "IVA16");
        String saleId = createSale(seeded, taxed, 2, "rt-" + UUID.randomUUID(),
                "CASH", "232.00");
        String saleLineId = firstSaleLineId(seeded, saleId);

        String invoiceBody = issueInvoice(seeded, saleId);
        String invoiceId = com.jayway.jsonpath.JsonPath.read(invoiceBody, "$.id");
        String invoiceFiscalId = com.jayway.jsonpath.JsonPath.read(invoiceBody, "$.fiscalId");

        String returnId = com.jayway.jsonpath.JsonPath.read(postReturn(
                        seeded, saleId, saleLineId, "2", "CASH", "nc-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditNoteIssuable").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");

        MvcResult note = mockMvc.perform(post("/api/v1/invoices/credit-notes")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"returnId\":\"%s\"}".formatted(returnId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("CREDIT_NOTE"))
                .andExpect(jsonPath("$.serie").value("NC"))
                .andExpect(jsonPath("$.folioLabel").value("NC-000001"))
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.saleId").value(
                        org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.returnId").value(returnId))
                .andExpect(jsonPath("$.relatedInvoiceId").value(invoiceId))
                // the NC reproduces EXACTLY the taxes the sale charged
                .andExpect(jsonPath("$.subtotal").value("200.00"))
                .andExpect(jsonPath("$.taxTotal").value("32.00"))
                .andExpect(jsonPath("$.total").value("232.00"))
                .andExpect(jsonPath("$.lines[0].taxRateCode").value("IVA16"))
                .andExpect(jsonPath("$.lines[0].taxTotal").value("32.00"))
                .andReturn();

        // the XML preview carries the egreso semantics: TipoDeComprobante E and
        // the RELATED income CFDI (TipoRelacion 01, the FA's folio fiscal)
        String noteId = com.jayway.jsonpath.JsonPath.read(
                note.getResponse().getContentAsString(), "$.id");
        assertThat(noteId).isNotBlank();
        String xml = tenantRunner.run(seeded.organizationId(), () -> jdbc.queryForObject(
                "SELECT sat_document::text FROM invoice WHERE id::text = ?",
                String.class, noteId));
        assertThat(xml).contains("TipoDeComprobante=\\\"E\\\"");
        assertThat(xml).contains("TipoRelacion=\\\"01\\\"");
        assertThat(xml).contains("UUID=\\\"" + invoiceFiscalId + "\\\"");

        // a duplicate NC for the same return -> 409 (the partial unique backs it)
        mockMvc.perform(post("/api/v1/invoices/credit-notes")
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString())
                        .contentType("application/json")
                        .content("{\"returnId\":\"%s\"}".formatted(returnId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDIT_NOTE_ALREADY_EXISTS"));

        // the return's view now shows the issued NC
        mockMvc.perform(get("/api/v1/returns/{saleId}", saleId)
                        .cookie(sessionOf(seeded))
                        .header("X-Tenant-Id", seeded.organizationId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].creditNoteLabel").value("NC-000001"))
                .andExpect(jsonPath("$[0].creditNoteIssuable").value(false));
    }
}
