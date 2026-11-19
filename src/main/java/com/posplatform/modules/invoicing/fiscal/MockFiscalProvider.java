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
 * MOCK PAC (v0.11 · ADR-016 + ADR-021): a sandbox FiscalProvider. It validates
 * the document SHAPE (as a real PAC would), assigns a folio fiscal (UUID) and
 * returns a CFDI 4.0-style XML PREVIEW inside {@code detailJson} — never a
 * signed trek: no certificate, no SAT connection, no credentials.
 *
 * Selected by {@code posplatform.fiscal.provider: mock} (application.yml). A
 * real PAC adapter swaps this bean via the same property — the invoicing
 * lifecycle machine and every business rule stay unchanged.
 */
@Component
@ConditionalOnProperty(name = "posplatform.fiscal.provider", havingValue = "mock", matchIfMissing = true)
public class MockFiscalProvider implements FiscalProvider {

    /** RFC genérico de "PÚBLICO EN GENERAL" (AE-2.7.1.10) — the Mock never treks it as a real taxpayer. */
    public static final String RFC_PUBLICO_GENERAL = "XAXX010101000";
    /** RFC genérico de operaciones con extranjeros; kept for future phases. */
    public static final String RFC_EXTRANJERO = "XEXX010101000";

    /** Clave ProdServ del concepto "Pago" del complemento (Anexo 20). */
    static final String PAGO_CONCEPTO = "84111506";

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public StampResult stamp(StampRequest request) {
        validateDocumentType(request);
        validateReceptor(request);
        if ("P".equals(request.documentType())) {
            validatePayment(request);
        } else {
            validateTotals(request);
        }
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
                    "El comprobante requiere TipoDeComprobante (I, E o P)");
        }
        if (!("I".equals(request.documentType()) || "E".equals(request.documentType())
                || "P".equals(request.documentType()))) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "TipoDeComprobante no soportado: " + request.documentType()
                            + " (soportados: I, E, P)");
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

    /** CFDI tipo P (complemento de pago): un concepto, SubTotal = Monto = Total. */
    private static void validatePayment(StampRequest request) {
        if (request.lines() == null || request.lines().size() != 1) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El complemento de pago lleva UN solo concepto");
        }
        BigDecimal monto = request.subtotal() == null
                ? BigDecimal.ZERO : request.subtotal();
        if (monto.signum() <= 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El monto del complemento de pago debe ser positivo");
        }
        if (request.taxTotal() == null || request.taxTotal().signum() != 0
                || request.discountTotal() == null
                || request.discountTotal().signum() != 0
                || (request.withholdings() != null && !request.withholdings().isEmpty())) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El CFDI tipo P no lleva impuestos, descuentos ni retenciones");
        }
        if (monto.compareTo(request.total() == null ? BigDecimal.ZERO : request.total()) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "En el tipo P el Total ES el monto pagado");
        }
    }

    private static void validateTotals(StampRequest request) {
        // CFDI 4.0 semantics: concepto Importe = cantidad x valorUnitario (gross);
        // Descuento is separate, and SubTotal - Descuento + Traslados
        // - Retenciones = Total.
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
        // ADR-018 + ADR-021 (v0.11): per-line SNAPSHOT taxes must be present
        // and coherent. IVA base is the IEPS-INCLUSIVE net; the IEPS slot is
        // computed on the plain net. The Sandbox tolerance absorbs the
        // rounding drift of GLOBAL invoices one cent at a time (a folded
        // concept sums already-rounded sale-line taxes and is NOT re-rounded)
        // — a real PAC applies the provider's own validation behind the port.
        BigDecimal tolerance = BigDecimal.valueOf(0.01)
                .multiply(BigDecimal.valueOf(Math.max(1, request.lines().size())));
        BigDecimal lineTaxes = BigDecimal.ZERO;
        for (StampLine line : request.lines()) {
            BigDecimal net = line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal();
            BigDecimal ieps = line.iepsTotal() == null
                    ? BigDecimal.ZERO : line.iepsTotal();
            // IVA slot (scheme from the catalog decides which traslado it is —
            // a legacy line whose single code was IEPS still validates here).
            boolean ivaSlotTaxed = line.taxRateCode() != null && !line.taxRateCode().isBlank()
                    && line.taxRate() != null && line.taxRate().signum() > 0
                    && "TASA".equals(line.taxFactor());
            BigDecimal iva = line.taxTotal() == null ? BigDecimal.ZERO : line.taxTotal();
            if (ivaSlotTaxed) {
                if (iva.signum() < 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "El impuesto del concepto '" + line.description()
                                    + "' no puede ser negativo");
                }
                BigDecimal base = "IEPS".equals(line.taxScheme())
                        ? net : net.add(ieps);
                BigDecimal expected = base.multiply(line.taxRate())
                        .setScale(2, RoundingMode.HALF_UP);
                if (iva.subtract(expected).abs().compareTo(tolerance) > 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "El impuesto del concepto '" + line.description()
                                    + "' (" + iva.toPlainString()
                                    + ") no es coherente con su base y tasa ("
                                    + expected.toPlainString() + ")");
                }
            } else if (iva.signum() != 0) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "El concepto '" + line.description()
                                + "' trae impuesto sin tasa activa");
            }
            // IEPS slot (v0.11 · ADR-021).
            boolean iepsSlotTaxed = line.iepsRateCode() != null
                    && !line.iepsRateCode().isBlank()
                    && line.iepsRate() != null && line.iepsRate().signum() > 0
                    && "TASA".equals(line.iepsFactor());
            if (iepsSlotTaxed) {
                BigDecimal expectedIeps = net.multiply(line.iepsRate())
                        .setScale(2, RoundingMode.HALF_UP);
                if (ieps.subtract(expectedIeps).abs().compareTo(tolerance) > 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "El IEPS del concepto '" + line.description()
                                    + "' (" + ieps.toPlainString()
                                    + ") no es coherente con su base y tasa ("
                                    + expectedIeps.toPlainString() + ")");
                }
            } else if (ieps.signum() != 0) {
                throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                        "El concepto '" + line.description()
                                + "' trae IEPS sin tasa activa");
            }
            lineTaxes = lineTaxes.add(iva).add(ieps);
        }
        if (lineTaxes.compareTo(taxes) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "La suma de impuestos de concepto (" + lineTaxes.toPlainString()
                            + ") no coincide con los impuestos del documento");
        }
        // Retenciones (v0.11 · ADR-021): document-level snapshot validation.
        BigDecimal withholdingTotal = BigDecimal.ZERO;
        if (request.withholdings() != null) {
            BigDecimal net = subtotal.subtract(discountTotal).setScale(2, RoundingMode.HALF_UP);
            for (StampWithholding wh : request.withholdings()) {
                if (wh.amount() == null || wh.amount().signum() <= 0
                        || wh.rate() == null || wh.rate().signum() <= 0
                        || !"TASA".equals(wh.factor())) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "La retención " + wh.code() + " no es coherente");
                }
                BigDecimal expected = net.multiply(wh.rate())
                        .setScale(2, RoundingMode.HALF_UP);
                if (wh.amount().subtract(expected).abs().compareTo(tolerance) > 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "La retención " + wh.code() + " ("
                                    + wh.amount().toPlainString()
                                    + ") no es coherente con la base neta del documento ("
                                    + expected.toPlainString() + ")");
                }
                if ("IVA".equals(wh.scheme()) && taxes.signum() <= 0) {
                    throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                            "La retención de " + wh.code() + " exige IVA trasladado");
                }
                withholdingTotal = withholdingTotal.add(wh.amount());
            }
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
        if (subtotal.subtract(discountTotal).add(taxes).subtract(withholdingTotal)
                .compareTo(total) != 0) {
            throw new ConflictException("FISCAL_INVALID_DOCUMENT",
                    "El total del documento no es coherente (subtotal - descuento "
                            + "+ impuestos - retenciones)");
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
                xml.append(" FormaPago=\"99\"")  // preview default; per-document forma arrives with the tax domain
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
                boolean taxed = line.taxRateCode() != null && !line.taxRateCode().isBlank();
                boolean iepsTaxed = line.iepsRateCode() != null && !line.iepsRateCode().isBlank();
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
                    if (line.taxRateCode() != null && !line.taxRateCode().isBlank()) {
                        xml.append("<cfdi:Traslado")
                                .append(" Base=\"").append(
                                        "IEPS".equals(line.taxScheme()) ? net : net.add(ieps))
                                .append("\"")
                                .append(" Impuesto=\"").append(satImpuestoOf(line.taxScheme()))
                                .append("\"")
                                .append(" TipoFactor=\"").append(satFactorOf(line.taxFactor()))
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
                    if (line.iepsRateCode() != null && !line.iepsRateCode().isBlank()) {
                        xml.append("<cfdi:Traslado")
                                .append(" Base=\"").append(net).append("\"")
                                .append(" Impuesto=\"").append(satImpuestoOf(line.iepsScheme()))
                                .append("\"")
                                .append(" TipoFactor=\"").append(satFactorOf(line.iepsFactor()))
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
                            .append(" Impuesto=\"").append(satImpuestoOf(wh.scheme()))
                            .append("\"")
                            .append(" TipoFactor=\"").append(satFactorOf(wh.factor()))
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

        /** Scheme of the catalog -> SAT Impuesto key (Anexo 20). */
        private static String satImpuestoOf(String scheme) {
            if ("IEPS".equals(scheme)) return "003";
            if ("ISR".equals(scheme)) return "001";
            return "002"; // IVA
        }

        /** Factor of the catalog -> SAT TipoFactor (Anexo 20). */
        private static String satFactorOf(String factor) {
            return "EXENTO".equals(factor) ? "Exento" : "Tasa";
        }
    }
}
