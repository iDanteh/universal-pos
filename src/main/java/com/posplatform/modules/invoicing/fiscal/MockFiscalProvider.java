package com.posplatform.modules.invoicing.fiscal;

import com.posplatform.shared.api.ConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MOCK PAC (v0.6 · ADR-016): a sandbox FiscalProvider. It validates the
 * document SHAPE (as a real PAC would), assigns a folio fiscal (UUID) and
 * returns a CFDI 4.0-style XML PREVIEW inside {@code detailJson} — never a
 * signed trek: no certificate, no SAT connection, no credentials.
 *
 * Selected by {@code pos.fiscal.provider: mock} (application.yml). A real PAC
 * adapter swaps this bean via the same property — the invoicing lifecycle
 * machine and every business rule stay unchanged.
 */
@Component
@ConditionalOnProperty(name = "posplatform.fiscal.provider", havingValue = "mock", matchIfMissing = true)
public class MockFiscalProvider implements FiscalProvider {

    /** RFC genérico de "PÚBLICO EN GENERAL" (AE-2.7.1.10) — the Mock never treks it as a real taxpayer. */
    public static final String RFC_PUBLICO_GENERAL = "XAXX010101000";
    /** RFC genérico de operaciones con extranjeros; kept for future phases. */
    public static final String RFC_EXTRANJERO = "XEXX010101000";

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public StampResult stamp(StampRequest request) {
        validateDocumentType(request);
        validateReceptor(request);
        validateTotals(request);
        // The mock accepts ANY well-shaped document: folio fiscal = fresh UUID
        // (like a real PAC's stamp), fecha = now UTC, XML preview included.
        UUID fiscalId = UUID.randomUUID();
        String xml = XmlPreview.of(request, fiscalId, Instant.now());
        String detailJson = "{\"provider\":\"MOCK\",\"xml\":\""
                + jsonEscape(xml) + "\",\"signed\":false}";
        return new StampResult(fiscalId, Instant.now(), detailJson);
    }

    @Override
    public void cancel(UUID fiscalId, String motive) {
        // The mock approves every SAT-motive cancellation; a real PAC would
        // return the acceptance state (or a rejection we map to the ERROR state).
        if (fiscalId == null) {
            throw new ConflictException("INVOICE_NOT_STAMPED",
                    "No hay folio fiscal que cancelar");
        }
    }

    // ---- shape guards (the provider is ALLOWED to reject a bad document) ----

    /** TipoDeComprobante is mandatory; an egreso (E) REQUIRES its related CFDI. */
    private static void validateDocumentType(StampRequest request) {
        if (request.documentType() == null || request.documentType().isBlank()) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El comprobante requiere TipoDeComprobante (I o E)");
        }
        if (!("I".equals(request.documentType()) || "E".equals(request.documentType()))) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "TipoDeComprobante no soportado: " + request.documentType()
                            + " (soportados: I, E)");
        }
        if ("E".equals(request.documentType()) && request.relatedFiscalId() == null) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "Una nota de crédito (E) requiere el folio fiscal de la "
                            + "factura relacionada (CfdiRelacionados 01)");
        }
    }

    private static void validateReceptor(StampRequest request) {
        if (request.receptorRfc() == null || request.receptorRfc().isBlank()
                || request.receptorName() == null || request.receptorName().isBlank()) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El CFDI requiere RFC y nombre del receptor");
        }
    }

    private static void validateTotals(StampRequest request) {
        // CFDI 4.0 semantics: concepto Importe = cantidad x valorUnitario (gross);
        // Descuento is separate, and SubTotal - Descuento + Traslados = Total.
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        for (StampLine line : request.lines()) {
            if (line.quantity() == null || line.quantity().signum() <= 0
                    || line.unitSat() == null || line.unitSat().isBlank()) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "Cada concepto requiere cantidad positiva y clave de unidad");
            }
            BigDecimal importe = line.quantity().multiply(line.unitPrice())
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal();
            if (importe.subtract(line.discount() == null ? BigDecimal.ZERO : line.discount())
                    .compareTo(net) != 0) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "El importe neto del concepto '" + line.description()
                                + "' no es coherente (cantidad x valor unitario - descuento)");
            }
            gross = gross.add(importe);
            discounts = discounts.add(line.discount() == null ? BigDecimal.ZERO : line.discount());
        }
        BigDecimal subtotal = request.subtotal() == null ? BigDecimal.ZERO : request.subtotal();
        BigDecimal discountTotal = request.discountTotal() == null
                ? BigDecimal.ZERO : request.discountTotal();
        BigDecimal total = request.total() == null ? BigDecimal.ZERO : request.total();
        BigDecimal taxes = request.taxTotal() == null ? BigDecimal.ZERO : request.taxTotal();
        if (subtotal.signum() < 0 || total.signum() < 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "Los totales del CFDI no pueden ser negativos");
        }
        // ADR-018 (v0.8): per-line snapshot taxes must be present and coherent.
        // The Sandbox tolerance absorbs the rounding drift of GLOBAL invoices
        // one cent at a time (a folded concept sums already-rounded sale-line
        // taxes and is NOT re-rounded) — a real PAC applies the provider's own
        // validation rules behind the same port.
        BigDecimal tolerance = BigDecimal.valueOf(0.01)
                .multiply(BigDecimal.valueOf(Math.max(1, request.lines().size())));
        BigDecimal lineTaxes = BigDecimal.ZERO;
        for (StampLine line : request.lines()) {
            BigDecimal net = line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal();
            BigDecimal tax = line.taxTotal() == null ? BigDecimal.ZERO : line.taxTotal();
            boolean taxed = line.taxRateCode() != null && !line.taxRateCode().isBlank()
                    && line.taxRate() != null && line.taxRate().signum() > 0
                    && "TASA".equals(line.taxFactor());
            if (taxed) {
                if (tax.signum() < 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "El impuesto del concepto '" + line.description()
                                    + "' no puede ser negativo");
                }
                BigDecimal expected = net.multiply(line.taxRate())
                        .setScale(2, RoundingMode.HALF_UP);
                if (tax.subtract(expected).abs().compareTo(tolerance) > 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "El impuesto del concepto '" + line.description()
                                    + "' (" + tax.toPlainString()
                                    + ") no es coherente con su base y tasa ("
                                    + expected.toPlainString() + ")");
                }
            } else if (tax.signum() != 0) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "El concepto '" + line.description()
                                + "' trae impuesto sin tasa activa");
            }
            lineTaxes = lineTaxes.add(tax);
        }
        if (lineTaxes.compareTo(taxes) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "La suma de impuestos de concepto (" + lineTaxes.toPlainString()
                            + ") no coincide con los impuestos del documento");
        }
        if (gross.compareTo(subtotal) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "La suma de conceptos brutos (" + gross.toPlainString()
                            + ") no coincide con el subtotal del documento");
        }
        if (discounts.compareTo(discountTotal) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "La suma de descuentos de concepto (" + discounts.toPlainString()
                            + ") no coincide con el descuento del documento");
        }
        if (subtotal.subtract(discountTotal).add(taxes).compareTo(total) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El total del documento no es coherente (subtotal - descuento + impuestos)");
        }
    }

    private static String jsonEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    /**
     * Simplified but namespace-conformant CFDI 4.0 XML preview: structure,
     * namespace and all captured data — explicitly unsigned, because the
     * SelloSAT only exists at a REAL TIMBRADO provider.
     */
    private static final class XmlPreview {

        private XmlPreview() {
        }

        static String of(StampRequest request, UUID fiscalId, Instant fecha) {
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            xml.append("<cfdi:Comprobante xmlns:cfdi=\"http://www.sat.gob.mx/cfd/4\"")
                    .append(" Version=\"4.0\"")
                    .append(" Serie=\"").append(request.serie()).append("\"")
                    .append(" Folio=\"").append(request.folio()).append("\"")
                    .append(" Fecha=\"").append(fecha.toString()).append("\"")
                    .append(" TipoDeComprobante=\"").append(request.documentType()).append("\"")
                    .append(" FormaPago=\"99\"")  // preview default; per-document forma arrives with the tax domain
                    .append(" MetodoPago=\"PUE\"")
                    .append(" Moneda=\"MXN\"")
                    .append(" SubTotal=\"").append(request.subtotal().toPlainString()).append("\"")
                    .append(" Descuento=\"").append(request.discountTotal().toPlainString()).append("\"")
                    .append(" Total=\"").append(request.total().toPlainString()).append("\"")
                    .append(" FolioFiscal=\"").append(fiscalId).append("\">");
            if ("E".equals(request.documentType())) {
                xml.append("<cfdi:CfdiRelacionados TipoRelacion=\"01\">")
                        .append("<cfdi:CfdiRelacionado UUID=\"")
                        .append(request.relatedFiscalId()).append("\"/>")
                        .append("</cfdi:CfdiRelacionados>");
            }
            xml.append("<cfdi:Emisor Rfc=\"").append(request.emisorRfc()).append("\"")
                    .append(" Nombre=\"").append(request.emisorName()).append("\"")
                    .append(" RegimenFiscal=\"").append(request.emisorRegime()).append("\"")
                    .append(" FacAtrAdquirente=\"").append("MOCK-CSD-12345678-1234-5678-9ABC-DEF012345678").append("\">")
                    .append("</cfdi:Emisor>");
            xml.append("<cfdi:Receptor Rfc=\"").append(request.receptorRfc()).append("\"")
                    .append(" Nombre=\"").append(request.receptorName()).append("\"")
                    .append(" DomicilioFiscalReceptor=\"").append(request.receptorZip()).append("\"")
                    .append(" UsoCFDI=\"").append(request.receptorUse()).append("\">")
                    .append("</cfdi:Receptor>");
            xml.append("<cfdi:Conceptos>");
            boolean anyTaxed = false;
            for (StampLine line : request.lines()) {
                boolean taxed = line.taxRateCode() != null && !line.taxRateCode().isBlank();
                anyTaxed = anyTaxed || taxed;
                xml.append("<cfdi:Concepto ClaveProdServ=\"01010101\"")
                        .append(" Descripcion=\"").append(line.description()).append("\"")
                        .append(" ObjetoImpuesto=\"").append(taxed ? "02" : "01").append("\"")
                        .append(" ClaveUnidad=\"").append(line.unitSat()).append("\"");
                if (line.sku() != null) {
                    xml.append(" NoIdentificacion=\"").append(line.sku()).append("\"");
                }
                xml.append(" Cantidad=\"").append(line.quantity().toPlainString()).append("\"")
                        .append(" ValorUnitario=\"").append(line.unitPrice().toPlainString()).append("\"")
                        .append(" Descuento=\"").append(line.discount().toPlainString()).append("\"")
                        .append(" Importe=\"").append(
                                line.quantity().multiply(line.unitPrice())
                                        .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                        .append("\"")
                        .append("</cfdi:Concepto>");
            }
            xml.append("</cfdi:Conceptos>");
            if (anyTaxed) {
                // Traslados per concept: Base = neto del concepto (CFDI 4.0),
                // Importe = impuesto SNAPSHOT de la venta; TasaOCuota sólo para
                // factor Tasa (Exento va sin tasa e Importe 0).
                BigDecimal taxes = request.taxTotal() == null
                        ? BigDecimal.ZERO : request.taxTotal();
                xml.append("<cfdi:Impuestos TotalImpuestosTrasladados=\"")
                        .append(taxes.toPlainString()).append("\">")
                        .append("<cfdi:Traslados>");
                for (StampLine line : request.lines()) {
                    if (line.taxRateCode() == null || line.taxRateCode().isBlank()) {
                        continue;
                    }
                    xml.append("<cfdi:Traslado")
                            .append(" Base=\"").append(
                                    line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal())
                            .append("\"")
                            .append(" Impuesto=\"").append(satImpuestoOf(line.taxScheme()))
                            .append("\"")
                            .append(" TipoFactor=\"").append(satFactorOf(line.taxFactor()))
                            .append("\"");
                    if (line.taxRate() != null && line.taxRate().signum() > 0) {
                        xml.append(" TasaOCuota=\"").append(
                                line.taxRate().setScale(4, java.math.RoundingMode.HALF_UP)
                                        .toPlainString()).append("\"");
                    }
                    xml.append(" Importe=\"").append(
                            line.taxTotal() == null ? BigDecimal.ZERO : line.taxTotal())
                            .append("\"/></cfdi:Traslado>");
                }
                xml.append("</cfdi:Traslados></cfdi:Impuestos>");
            }
            xml.append("</cfdi:Comprobante>");
            return xml.toString();
        }

        /** Scheme of the catalog -> SAT Impuesto key (Anexo 20). */
        private static String satImpuestoOf(String scheme) {
            if ("IEPS".equals(scheme)) return "003";
            return "002"; // IVA
        }

        /** Factor of the catalog -> SAT TipoFactor (Anexo 20). */
        private static String satFactorOf(String factor) {
            return "EXENTO".equals(factor) ? "Exento" : "Tasa";
        }
    }
}
