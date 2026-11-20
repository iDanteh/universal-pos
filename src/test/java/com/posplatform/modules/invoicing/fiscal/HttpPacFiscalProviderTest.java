package com.posplatform.modules.invoicing.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.posplatform.shared.api.ConflictException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PAC adapter against a local HTTP stub (v0.12 · ADR-022): the gateway
 * contract, the Bearer auth, the base64 XML round-trip, cancel acceptance and
 * the fail-closed mappings are all verified without touching the SAT.
 */
class HttpPacFiscalProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path dir;

    private static HttpServer server;
    private static TestCsdFactory.Fixture fixture;
    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> lastSealedXml = new AtomicReference<>();
    private static final AtomicReference<String> lastCancelBody = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws Exception {
        fixture = TestCsdFactory.generate(dir, "AAA010101AAA");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stamp", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            String xml = new String(Base64.getDecoder().decode(body.path("xml").asText()),
                    StandardCharsets.UTF_8);
            lastSealedXml.set(xml);
            UUID uuid = UUID.fromString("33333333-3333-4333-8333-333333333333");
            respond(exchange, 200, JSON.writeValueAsString(java.util.Map.of(
                    "uuid", uuid.toString(),
                    "fechaTimbrado", "2026-11-19T16:00:05Z",
                    "xml", Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)),
                    "selloSAT", "c2VsbG8tc2F0",
                    "noCertificadoSAT", "00001000000500000000")));
        });
        server.createContext("/cancel-ok", exchange -> {
            lastCancelBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"accepted\":true,\"estatusCancelacion\":\"201\"}");
        });
        server.createContext("/cancel-reject", exchange ->
                respond(exchange, 200, "{\"accepted\":false,\"estatusCancelacion\":\"205\"}"));
        server.createContext("/fail", exchange ->
                respond(exchange, 500, "{\"code\":\"PAC_DOWN\",\"detail\":\"Servicio no disponible\"}"));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void stampSendsTheSealedXmlAndStoresTheStampedDocument() throws Exception {
        HttpPacFiscalProvider provider = provider("/stamp", "/cancel-ok");

        FiscalProvider.StampResult result = provider.stamp(incomeRequest());

        assertThat(result.fiscalId())
                .isEqualTo(UUID.fromString("33333333-3333-4333-8333-333333333333"));
        assertThat(lastAuthorization.get()).isEqualTo("Bearer test-token");
        assertThat(lastSealedXml.get()).contains("<cfdi:Comprobante");
        assertThat(lastSealedXml.get()).contains("Sello=\"");
        assertThat(lastSealedXml.get()).contains("NoCertificado=\"" + fixture.noCertificado() + "\"");
        assertThat(lastSealedXml.get()).contains("Exportacion=\"01\"");

        JsonNode detail = JSON.readTree(result.detailJson());
        assertThat(detail.path("provider").asText()).isEqualTo("HTTP");
        assertThat(detail.path("signed").asBoolean()).isTrue();
        assertThat(detail.path("uuid").asText()).isEqualTo(result.fiscalId().toString());
        assertThat(detail.path("xml").asText()).contains("cfdi:Comprobante");
        assertThat(detail.path("sello").asText()).isNotBlank();
        assertThat(detail.path("cadenaOriginal").asText()).startsWith("||4.0|FA|");
        assertThat(detail.path("selloSAT").asText()).isEqualTo("c2VsbG8tc2F0");
        assertThat(result.stampedAt()).isEqualTo(Instant.parse("2026-11-19T16:00:05Z"));
    }

    @Test
    void cancelSendsTheUuidAndMotive() throws Exception {
        HttpPacFiscalProvider provider = provider("/stamp", "/cancel-ok");
        UUID uuid = UUID.fromString("44444444-4444-4444-8444-444444444444");

        provider.cancel(uuid, "02");

        JsonNode body = JSON.readTree(lastCancelBody.get());
        assertThat(body.path("uuid").asText()).isEqualTo(uuid.toString());
        assertThat(body.path("motivo").asText()).isEqualTo("02");
    }

    @Test
    void rejectedCancellationIsAConflict() throws Exception {
        HttpPacFiscalProvider provider = provider("/stamp", "/cancel-reject");

        assertThatThrownBy(() -> provider.cancel(UUID.randomUUID(), "02"))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAC_CANCEL_REJECTED"));
    }

    @Test
    void providerHttpErrorKeepsThePacCode() throws Exception {
        HttpPacFiscalProvider provider = provider("/fail", "/cancel-ok");

        assertThatThrownBy(() -> provider.stamp(incomeRequest()))
                .isInstanceOfSatisfying(ConflictException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("PAC_DOWN");
                    assertThat(ex.getMessage()).isEqualTo("Servicio no disponible");
                });
    }

    // ---- fixtures ----

    private static HttpPacFiscalProvider provider(String stampPath, String cancelPath) {
        FiscalProperties properties = new FiscalProperties();
        properties.getCsd().setCertificate(fixture.cer().toString());
        properties.getCsd().setPrivateKey(fixture.key().toString());
        properties.getCsd().setPassword(TestCsdFactory.PASSWORD);
        properties.getPac().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getPac().setToken("test-token");
        properties.getPac().setStampPath(stampPath);
        properties.getPac().setCancelPath(cancelPath);
        properties.getPac().setTimeoutSeconds(10);
        return new HttpPacFiscalProvider(properties, JSON, new CfdiSealer());
    }

    private static FiscalProvider.StampRequest incomeRequest() {
        FiscalProvider.StampLine line = new FiscalProvider.StampLine(
                "Producto IEPS", "F12-1", "H87", BigDecimal.ONE, new BigDecimal("100.0000"),
                BigDecimal.ZERO, new BigDecimal("100.00"),
                "IVA16", "IVA", "TASA", new BigDecimal("0.160000"), new BigDecimal("20.24"),
                "IEPS265", "IEPS", "TASA", new BigDecimal("0.265000"), new BigDecimal("26.50"));
        return new FiscalProvider.StampRequest(
                "FA", 9, "I", "AAA010101AAA", "Emisor Pruebas SA de CV", "601", "03100",
                "RECE123456X1A", "Cliente Pruebas SA de CV", "601", "G01", "06600",
                List.of(line), new BigDecimal("100.00"), BigDecimal.ZERO,
                new BigDecimal("46.74"), new BigDecimal("136.74"), null,
                List.of(new FiscalProvider.StampWithholding("RETISR10", "ISR", "TASA",
                        new BigDecimal("0.100000"), new BigDecimal("10.00"))),
                "01", null, null);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
