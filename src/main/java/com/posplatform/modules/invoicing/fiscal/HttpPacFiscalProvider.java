package com.posplatform.modules.invoicing.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.posplatform.shared.api.ConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REAL PAC ADAPTER (v0.12 · ADR-022), selected with
 * {@code posplatform.fiscal.provider=http}.
 *
 * <p>It seals the CFDI locally with the CSD (the Sello is OURS, exactly like a
 * desktop stamping flow) and sends the signed XML to a PAC GATEWAY over HTTPS.
 * The gateway contract is deliberately small and vendor-neutral:</p>
 *
 * <pre>
 * POST {baseUrl}{stampPath}   Authorization: Bearer {token}
 * {"xml":"&lt;base64 sealed cfdi&gt;","rfc":"...","serie":"FA","folio":1}
 * 200 {"uuid":"...","fechaTimbrado":"2026-11-19T13:00:00Z",
 *      "xml":"&lt;base64 stamped cfdi with TimbreFiscalDigital&gt;",
 *      "selloSAT":"...","noCertificadoSAT":"..."}
 *
 * POST {baseUrl}{cancelPath}  Authorization: Bearer {token}
 * {"uuid":"...","rfc":"...","motivo":"02"}
 * 200 {"accepted":true,"estatusCancelacion":"201"}
 * </pre>
 *
 * <p>Vendor adapters (SW Sapien, Facturama, Finkok...) implement this gateway
 * or translate these two calls. The invoicing lifecycle is untouched: the
 * provider still returns {@link StampResult} and never persists anything.</p>
 */
@Component
@ConditionalOnProperty(name = "posplatform.fiscal.provider", havingValue = "http")
public class HttpPacFiscalProvider implements FiscalProvider {

    private final FiscalProperties properties;
    private final ObjectMapper objectMapper;
    private final CfdiSealer sealer;
    private final CsdMaterial csd;
    private final ZoneId zone;
    private final HttpClient httpClient;

    public HttpPacFiscalProvider(FiscalProperties properties, ObjectMapper objectMapper,
                                 CfdiSealer sealer) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sealer = sealer;
        this.csd = new CsdService().load(properties.getCsd());
        this.zone = ZoneId.of(properties.getTimeZone());
        requirePac(properties.getPac());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getPac().getTimeoutSeconds()))
                .build();
        if (sealer == null) {
            throw new IllegalStateException("CfdiSealer no disponible para el PAC real");
        }
    }

    @Override
    public String name() {
        return "HTTP";
    }

    @Override
    public StampResult stamp(StampRequest request) {
        FiscalDocumentValidator.validate(request);
        if (csd.rfc() != null && !csd.rfc().equalsIgnoreCase(request.emisorRfc())) {
            throw new ConflictException("CSD_RFC_MISMATCH",
                    "El CSD cargado pertenece al RFC " + csd.rfc()
                            + " y el comprobante se emite con " + request.emisorRfc());
        }
        Instant now = Instant.now();
        Document document = CfdiXmlWriter.build(request, csd,
                request.paymentForm(), now, zone);
        CfdiSealer.SealedCfdi sealed = sealer.seal(document, csd);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("xml", Base64.getEncoder().encodeToString(
                sealed.xml().getBytes(StandardCharsets.UTF_8)));
        body.put("rfc", request.emisorRfc());
        body.put("serie", request.serie());
        body.put("folio", request.folio());
        body.put("tipo", request.documentType());

        JsonNode response = post(properties.getPac().getStampPath(), body);
        UUID fiscalId = responseUuid(response);
        String stampedXml = decodeRequired(response, "xml");
        Instant stampedAt = responseInstant(response.path("fechaTimbrado"), now);
        String selloSAT = response.path("selloSAT").asText(null);
        String noCertificadoSAT = response.path("noCertificadoSAT").asText(null);

        Map<String, Object> documentJson = new LinkedHashMap<>();
        documentJson.put("provider", name());
        documentJson.put("xml", stampedXml);
        documentJson.put("signed", true);
        documentJson.put("uuid", fiscalId.toString());
        documentJson.put("sello", sealed.sello());
        documentJson.put("cadenaOriginal", sealed.cadenaOriginal());
        if (selloSAT != null && !selloSAT.isBlank()) {
            documentJson.put("selloSAT", selloSAT);
        }
        if (noCertificadoSAT != null && !noCertificadoSAT.isBlank()) {
            documentJson.put("noCertificadoSAT", noCertificadoSAT);
        }
        documentJson.put("stampedAt", stampedAt.toString());

        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(documentJson);
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible serializar el documento timbrado", ex);
        }
        return new StampResult(fiscalId, stampedAt, detailJson);
    }

    @Override
    public void cancel(UUID fiscalId, String motive) {
        if (fiscalId == null) {
            throw new ConflictException("INVOICE_NOT_STAMPED",
                    "No hay folio fiscal que cancelar");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uuid", fiscalId.toString());
        body.put("motivo", motive);
        JsonNode response = post(properties.getPac().getCancelPath(), body);
        if (!response.path("accepted").asBoolean(false)) {
            String status = response.path("estatusCancelacion").asText("");
            throw new ConflictException("PAC_CANCEL_REJECTED",
                    "El PAC no aceptó la cancelación del CFDI " + fiscalId
                            + (status.isBlank() ? "" : " (estatus " + status + ")"));
        }
    }

    // ---- HTTP ----

    private JsonNode post(String path, Map<String, Object> body) {
        FiscalProperties.Pac pac = properties.getPac();
        String url = stripTrailingSlash(pac.getBaseUrl())
                + (path.startsWith("/") ? path : "/" + path);
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(pac.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + pac.getToken())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw providerError(response.statusCode(), response.body());
            }
            JsonNode node = objectMapper.readTree(response.body());
            if (node == null || !node.isObject()) {
                throw new ConflictException("PAC_INVALID_RESPONSE",
                        "El PAC respondió un cuerpo que no es un objeto JSON");
            }
            return node;
        } catch (ConflictException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ConflictException("PAC_UNAVAILABLE",
                    "La llamada al PAC fue interrumpida");
        } catch (Exception ex) {
            throw new ConflictException("PAC_UNAVAILABLE",
                    "No fue posible contactar al PAC (" + safeMessage(ex) + ")");
        }
    }

    /** Maps a PAC error body ({"code","detail"}) to a business conflict. */
    private ConflictException providerError(int status, String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null ? "" : body);
            String code = node.path("code").asText(null);
            String detail = node.path("detail").asText(null);
            if (code != null && !code.isBlank()) {
                return new ConflictException(code, detail == null
                        ? "El PAC rechazó la operación (HTTP " + status + ")"
                        : detail);
            }
            if (node.path("message").asText("").isBlank() == false) {
                return new ConflictException("PAC_REJECTED", node.path("message").asText());
            }
        } catch (Exception ignored) {
            // not JSON: fall through to the generic status error
        }
        return new ConflictException("PAC_HTTP_ERROR",
                "El PAC respondió HTTP " + status);
    }

    private static UUID responseUuid(JsonNode response) {
        String raw = response.path("uuid").asText(null);
        if (raw == null || raw.isBlank()) {
            throw new ConflictException("PAC_INVALID_RESPONSE",
                    "El PAC no devolvió el folio fiscal (uuid)");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ConflictException("PAC_INVALID_RESPONSE",
                    "El folio fiscal devuelto por el PAC no es un UUID válido");
        }
    }

    private static String decodeRequired(JsonNode response, String field) {
        String encoded = response.path(field).asText(null);
        if (encoded == null || encoded.isBlank()) {
            throw new ConflictException("PAC_INVALID_RESPONSE",
                    "El PAC no devolvió el XML timbrado (" + field + ")");
        }
        try {
            return new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new ConflictException("PAC_INVALID_RESPONSE",
                    "El campo " + field + " del PAC no es base64 válido");
        }
    }

    private static Instant responseInstant(JsonNode node, Instant fallback) {
        String raw = node.asText(null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception ignored) {
            // some PACs send "2026-11-19 13:00:00" or a bare instant
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void requirePac(FiscalProperties.Pac pac) {
        if (pac == null || pac.getBaseUrl() == null || pac.getBaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "Falta posplatform.fiscal.pac.base-url para el PAC real");
        }
        if (pac.getToken() == null || pac.getToken().isBlank()) {
            throw new IllegalStateException(
                    "Falta posplatform.fiscal.pac.token para el PAC real");
        }
        try {
            URI.create(stripTrailingSlash(pac.getBaseUrl()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "posplatform.fiscal.pac.base-url no es una URL válida", ex);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName() : message;
    }
}
