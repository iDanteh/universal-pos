package com.posplatform.modules.invoicing.fiscal;

import com.posplatform.shared.api.ConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * MOCK PAC (v0.11 · ADR-016 + ADR-021): a sandbox FiscalProvider. It validates
 * the document SHAPE through the shared {@link FiscalDocumentValidator} (the
 * same guard the real PAC uses, v0.12), assigns a folio fiscal (UUID) and
 * returns a CFDI 4.0-style XML PREVIEW inside {@code detailJson} — never a
 * signed trek: no certificate, no SAT connection, no credentials.
 *
 * Selected by {@code posplatform.fiscal.provider: mock} (application.yml). The
 * real adapter ({@link HttpPacFiscalProvider}) swaps this bean via the same
 * property — the invoicing lifecycle machine and every business rule stay
 * unchanged.
 */
@Component
@ConditionalOnProperty(name = "posplatform.fiscal.provider", havingValue = "mock", matchIfMissing = true)
public class MockFiscalProvider implements FiscalProvider {

    /** RFC genérico de "PÚBLICO EN GENERAL" (AE-2.7.1.10) — the Mock never treks it as a real taxpayer. */
    public static final String RFC_PUBLICO_GENERAL = "XAXX010101000";
    /** RFC genérico de operaciones con extranjeros; kept for future phases. */
    public static final String RFC_EXTRANJERO = "XEXX010101000";

    /** Clave ProdServ del concepto "Pago" del complemento (Anexo 20). */
    static final String PAGO_CONCEPTO = CfdiXmlWriter.PAGO_CONCEPTO;

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public StampResult stamp(StampRequest request) {
        // v0.12: the SAME shape guard as the real PAC — one source of truth.
        FiscalDocumentValidator.validate(request);
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
                    .append(" TipoDeComprobante=\"").append(request.documentType()).append("\"");
            if ("P".equals(request.documentType())) {
                // tipo P: FormaPago/MetodoPago no aplican; el pago viaja en el
                // complemento (preview: atributos explícitos sin firmar).
                xml.append(" Moneda=\"MXN\"")
                        .append(" SubTotal=\"").append(request.subtotal().toPlainString()).append("\"")
                        .append(" Total=\"").append(request.total().toPlainString()).append("\"")
                        .append(" PagoMonto=\"").append(request.subtotal().toPlainString()).append("\"")
                        .append(" FolioFiscal=\"").append(fiscalId).append("\">");
            } else {
                xml.append(" FormaPago=\"").append(
                                request.paymentForm() == null || request.paymentForm().isBlank()
                                        ? "99" : request.paymentForm()).append("\"")
                        .append(" MetodoPago=\"PUE\"")
                        .append(" Moneda=\"MXN\"")
                        .append(" SubTotal=\"").append(request.subtotal().toPlainString()).append("\"")
                        .append(" Descuento=\"").append(request.discountTotal().toPlainString()).append("\"")
                        .append(" Total=\"").append(request.total().toPlainString()).append("\"")
                        .append(" FolioFiscal=\"").append(fiscalId).append("\">");
            }
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
            boolean anyIeps = false;
            for (StampLine line : request.lines()) {
                boolean taxed = CfdiXmlWriter.hasIva(line);
                boolean iepsTaxed = CfdiXmlWriter.hasIeps(line);
                anyTaxed = anyTaxed || taxed;
                anyIeps = anyIeps || iepsTaxed;
                xml.append("<cfdi:Concepto ClaveProdServ=\"")
                        .append("P".equals(request.documentType()) ? PAGO_CONCEPTO : "01010101")
                        .append("\"")
                        .append(" Descripcion=\"").append(line.description()).append("\"")
                        .append(" ObjetoImpuesto=\"").append(taxed || iepsTaxed ? "02" : "01").append("\"")
                        .append(" ClaveUnidad=\"").append(line.unitSat()).append("\"");
                if (line.sku() != null) {
                    xml.append(" NoIdentificacion=\"").append(line.sku()).append("\"");
                }
                xml.append(" Cantidad=\"").append(line.quantity().toPlainString()).append("\"")
                        .append(" ValorUnitario=\"").append(line.unitPrice().toPlainString()).append("\"")
                        .append(" Descuento=\"").append(line.discount().toPlainString()).append("\"")
                        .append(" Importe=\"").append(
                                line.quantity().multiply(line.unitPrice())
                                        .setScale(2, RoundingMode.HALF_UP).toPlainString())
                        .append("\"")
                        .append("</cfdi:Concepto>");
            }
            xml.append("</cfdi:Conceptos>");
            if (anyTaxed || anyIeps) {
                // Traslados per concept and SCHEME (ADR-021): IVA Base is the
                // IEPS-INCLUSIVE net; the IEPS traslado's Base is the plain
                // net. Importe = SNAPSHOT de la venta (never recomputed).
                BigDecimal taxes = request.taxTotal() == null
                        ? BigDecimal.ZERO : request.taxTotal();
                xml.append("<cfdi:Impuestos TotalImpuestosTrasladados=\"")
                        .append(taxes.toPlainString()).append("\">")
                        .append("<cfdi:Traslados>");
                for (StampLine line : request.lines()) {
                    BigDecimal net = line.lineTotal() == null
                            ? BigDecimal.ZERO : line.lineTotal();
                    BigDecimal ieps = line.iepsTotal() == null
                            ? BigDecimal.ZERO : line.iepsTotal();
                    if (CfdiXmlWriter.hasIva(line)) {
                        xml.append("<cfdi:Traslado")
                                .append(" Base=\"").append(
                                        "IEPS".equals(line.taxScheme()) ? net : net.add(ieps))
                                .append("\"")
                                .append(" Impuesto=\"").append(CfdiXmlWriter.satImpuesto(line.taxScheme()))
                                .append("\"")
                                .append(" TipoFactor=\"").append(CfdiXmlWriter.satFactor(line.taxFactor()))
                                .append("\"");
                        if (line.taxRate() != null && line.taxRate().signum() > 0) {
                            xml.append(" TasaOCuota=\"").append(
                                    line.taxRate().setScale(4, RoundingMode.HALF_UP)
                                            .toPlainString()).append("\"");
                        }
                        xml.append(" Importe=\"").append(
                                line.taxTotal() == null ? BigDecimal.ZERO : line.taxTotal())
                                .append("\"/></cfdi:Traslado>");
                    }
                    if (CfdiXmlWriter.hasIeps(line)) {
                        xml.append("<cfdi:Traslado")
                                .append(" Base=\"").append(net).append("\"")
                                .append(" Impuesto=\"").append(CfdiXmlWriter.satImpuesto(line.iepsScheme()))
                                .append("\"")
                                .append(" TipoFactor=\"").append(CfdiXmlWriter.satFactor(line.iepsFactor()))
                                .append("\"");
                        if (line.iepsRate() != null && line.iepsRate().signum() > 0) {
                            xml.append(" TasaOCuota=\"").append(
                                    line.iepsRate().setScale(4, RoundingMode.HALF_UP)
                                            .toPlainString()).append("\"");
                        }
                        xml.append(" Importe=\"").append(ieps)
                                .append("\"/></cfdi:Traslado>");
                    }
                }
                xml.append("</cfdi:Traslados></cfdi:Impuestos>");
            }
            if (request.withholdings() != null && !request.withholdings().isEmpty()) {
                BigDecimal whTotal = request.withholdings().stream()
                        .map(StampWithholding::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                xml.append("<cfdi:Impuestos TotalImpuestosRetenidos=\"")
                        .append(whTotal.toPlainString()).append("\">")
                        .append("<cfdi:Retenciones>");
                for (StampWithholding wh : request.withholdings()) {
                    xml.append("<cfdi:Retencion Base=\"")
                            .append(request.subtotal()
                                    .subtract(request.discountTotal()))
                            .append("\"")
                            .append(" Impuesto=\"").append(CfdiXmlWriter.satImpuesto(wh.scheme()))
                            .append("\"")
                            .append(" TipoFactor=\"").append(CfdiXmlWriter.satFactor(wh.factor()))
                            .append("\"")
                            .append(" TasaOCuota=\"").append(
                                    wh.rate().setScale(6, RoundingMode.HALF_UP).toPlainString())
                            .append("\"")
                            .append(" Importe=\"").append(wh.amount().toPlainString())
                            .append("\"/></cfdi:Retencion>");
                }
                xml.append("</cfdi:Retenciones></cfdi:Impuestos>");
            }
            xml.append("</cfdi:Comprobante>");
            return xml.toString();
        }
    }
}
