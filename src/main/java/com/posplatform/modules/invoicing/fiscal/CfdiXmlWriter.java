package com.posplatform.modules.invoicing.fiscal;

import com.posplatform.modules.invoicing.fiscal.FiscalProvider.GlobalInfo;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.PaymentInfo;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampLine;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampRequest;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampWithholding;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * CFDI 4.0 WRITER (v0.12 · ADR-022): builds the real, schema-conformant
 * {@code cfdi:Comprobante} for I (ingreso), E (egreso) and P (pago), including
 * the per-concept taxes, the document-level summary, the federal retenciones,
 * the {@code CfdiRelacionados} of a credit note and the {@code Pagos 2.0}
 * complement.
 *
 * <p>The DOM is built WITHOUT the {@code Sello}: the sealing step
 * ({@link CfdiSealer}) transforms it into the SAT cadena original, signs it
 * and injects the attribute. Everything is deterministic for the same
 * snapshot + timestamp, which keeps the unit tests exact.</p>
 *
 * <p>Money is NEVER recomputed: Importe/Base/Importe de impuesto come from the
 * sale SNAPSHOT already validated by {@link FiscalDocumentValidator}.</p>
 */
public final class CfdiXmlWriter {

    public static final String CFDI_NS = "http://www.sat.gob.mx/cfd/4";
    public static final String PAGO20_NS = "http://www.sat.gob.mx/Pagos20";

    /** Clave ProdServ del concepto "Pago" del complemento (Anexo 20). */
    public static final String PAGO_CONCEPTO = "84111506";

    static final String RFC_PUBLICO_GENERAL = "XAXX010101000";

    private static final DateTimeFormatter CFDI_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    private CfdiXmlWriter() {
    }

    /**
     * Builds the CFDI root. {@code fecha} is the document date in the fiscal
     * time zone (SAT expects local Mexico time, no offset).
     */
    public static Document build(StampRequest request, CsdMaterial csd,
                                 String paymentForm, Instant fecha, ZoneId zone) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = factory.newDocumentBuilder().newDocument();

            Element root = element(doc, CFDI_NS, "cfdi:Comprobante");
            doc.appendChild(root);

            boolean payment = "P".equals(request.documentType());
            root.setAttribute("Version", "4.0");
            root.setAttribute("Serie", request.serie());
            root.setAttribute("Folio", String.valueOf(request.folio()));
            root.setAttribute("Fecha", CFDI_DATE.format(fecha.atZone(zone)));
            if (!payment) {
                root.setAttribute("FormaPago",
                        blankTo(paymentForm, "99"));
                root.setAttribute("MetodoPago", "PUE");
            }
            root.setAttribute("NoCertificado", csd.noCertificado());
            root.setAttribute("Certificado", csd.certificadoBase64());
            root.setAttribute("SubTotal", money(request.subtotal()));
            if (!payment && request.discountTotal() != null
                    && request.discountTotal().signum() > 0) {
                root.setAttribute("Descuento", money(request.discountTotal()));
            }
            root.setAttribute("Moneda", "MXN");
            root.setAttribute("Total", money(request.total()));
            root.setAttribute("TipoDeComprobante", request.documentType());
            root.setAttribute("Exportacion", "01");
            root.setAttribute("LugarExpedicion", request.emisorZip());

            GlobalInfo global = request.global();
            if (global != null) {
                Element info = element(doc, CFDI_NS, "cfdi:InformacionGlobal");
                info.setAttribute("Periodicidad", "04"); // mensual
                info.setAttribute("Meses", String.format(Locale.ROOT, "%02d", global.month()));
                info.setAttribute("Año", String.valueOf(global.year()));
                root.appendChild(info);
            }

            if ("E".equals(request.documentType()) && request.relatedFiscalId() != null) {
                Element related = element(doc, CFDI_NS, "cfdi:CfdiRelacionados");
                related.setAttribute("TipoRelacion", "01");
                Element cfdiRelated = element(doc, CFDI_NS, "cfdi:CfdiRelacionado");
                cfdiRelated.setAttribute("UUID", request.relatedFiscalId().toString());
                related.appendChild(cfdiRelated);
                root.appendChild(related);
            }

            root.appendChild(emisor(doc, request));
            root.appendChild(receptor(doc, request, payment));
            root.appendChild(conceptos(doc, request, payment));

            Element impuestos = impuestos(doc, request, payment);
            if (impuestos != null) {
                root.appendChild(impuestos);
            }
            if (payment) {
                root.appendChild(pago20(doc, request, zone));
            }
            return doc;
        } catch (ConflictDocumentException ex) {
            throw ex.conflict;
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible construir el CFDI 4.0", ex);
        }
    }

    // ---- sections ----

    private static Element emisor(Document doc, StampRequest request) {
        Element emisor = element(doc, CFDI_NS, "cfdi:Emisor");
        emisor.setAttribute("Rfc", request.emisorRfc());
        emisor.setAttribute("Nombre", request.emisorName());
        emisor.setAttribute("RegimenFiscal", request.emisorRegime());
        return emisor;
    }

    private static Element receptor(Document doc, StampRequest request, boolean payment) {
        Element receptor = element(doc, CFDI_NS, "cfdi:Receptor");
        receptor.setAttribute("Rfc", request.receptorRfc());
        receptor.setAttribute("Nombre", request.receptorName());
        receptor.setAttribute("DomicilioFiscalReceptor", request.receptorZip());
        receptor.setAttribute("RegimenFiscalReceptor", request.receptorRegime());
        // A payment complement is always "Pagos" (CP01), regardless of the
        // related income CFDI's use.
        receptor.setAttribute("UsoCFDI", payment ? "CP01"
                : blankTo(request.receptorUse(), "G01"));
        return receptor;
    }

    private static Element conceptos(Document doc, StampRequest request, boolean payment) {
        Element conceptos = element(doc, CFDI_NS, "cfdi:Conceptos");
        for (StampLine line : request.lines()) {
            boolean iva = hasIva(line);
            boolean ieps = hasIeps(line);
            boolean taxed = iva || ieps;

            Element concepto = element(doc, CFDI_NS, "cfdi:Concepto");
            concepto.setAttribute("ClaveProdServ", payment ? PAGO_CONCEPTO : "01010101");
            if (line.sku() != null && !line.sku().isBlank()) {
                concepto.setAttribute("NoIdentificacion", line.sku());
            }
            concepto.setAttribute("Cantidad", plain(line.quantity()));
            concepto.setAttribute("ClaveUnidad", line.unitSat());
            concepto.setAttribute("Descripcion", line.description());
            concepto.setAttribute("ValorUnitario", money(line.unitPrice()));
            concepto.setAttribute("Importe", money(importe(line)));
            if (line.discount() != null && line.discount().signum() > 0) {
                concepto.setAttribute("Descuento", money(line.discount()));
            }
            concepto.setAttribute("ObjetoImpuesto", taxed ? "02" : "01");

            if (taxed) {
                Element impuestos = element(doc, CFDI_NS, "cfdi:Impuestos");
                Element traslados = element(doc, CFDI_NS, "cfdi:Traslados");
                if (ieps) {
                    traslados.appendChild(traslado(doc, line, true));
                }
                if (iva) {
                    traslados.appendChild(traslado(doc, line, false));
                }
                impuestos.appendChild(traslados);
                concepto.appendChild(impuestos);
            }
            conceptos.appendChild(concepto);
        }
        return conceptos;
    }

    private static Element traslado(Document doc, StampLine line, boolean ieps) {
        BigDecimal net = line.lineTotal() == null ? BigDecimal.ZERO : line.lineTotal();
        BigDecimal iepsAmount = line.iepsTotal() == null
                ? BigDecimal.ZERO : line.iepsTotal();
        boolean ivaSlot = !ieps;
        String scheme = ivaSlot ? line.taxScheme() : line.iepsScheme();
        String factor = ivaSlot ? line.taxFactor() : line.iepsFactor();
        BigDecimal rate = ivaSlot ? line.taxRate() : line.iepsRate();
        BigDecimal amount = ivaSlot
                ? (line.taxTotal() == null ? BigDecimal.ZERO : line.taxTotal())
                : iepsAmount;
        // IVA base is the IEPS-INCLUSIVE net when the scheme says so (ADR-021);
        // IEPS is always computed over the plain net.
        BigDecimal base = ivaSlot && !"IEPS".equals(line.taxScheme())
                ? net.add(iepsAmount) : net;

        Element traslado = element(doc, CFDI_NS, "cfdi:Traslado");
        traslado.setAttribute("Base", money(base));
        traslado.setAttribute("Impuesto", satImpuesto(scheme));
        traslado.setAttribute("TipoFactor", satFactor(factor));
        if (rate != null && rate.signum() > 0) {
            traslado.setAttribute("TasaOCuota",
                    rate.setScale(6, RoundingMode.HALF_UP).toPlainString());
        }
        traslado.setAttribute("Importe", money(amount));
        return traslado;
    }

    private static Element impuestos(Document doc, StampRequest request, boolean payment) {
        if (payment) {
            return null; // tipo P: no taxes at document level
        }
        BigDecimal taxes = request.taxTotal() == null ? BigDecimal.ZERO : request.taxTotal();
        BigDecimal retained = request.withholdings() == null ? BigDecimal.ZERO
                : request.withholdings().stream().map(StampWithholding::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (taxes.signum() == 0 && retained.signum() == 0) {
            return null;
        }
        Element impuestos = element(doc, CFDI_NS, "cfdi:Impuestos");
        if (retained.signum() > 0) {
            impuestos.setAttribute("TotalImpuestosRetenidos", money(retained));
        }
        if (taxes.signum() > 0) {
            impuestos.setAttribute("TotalImpuestosTrasladados", money(taxes));
        }
        if (retained.signum() > 0) {
            BigDecimal net = nz(request.subtotal()).subtract(nz(request.discountTotal()));
            Element retenciones = element(doc, CFDI_NS, "cfdi:Retenciones");
            for (StampWithholding wh : request.withholdings()) {
                Element retencion = element(doc, CFDI_NS, "cfdi:Retencion");
                retencion.setAttribute("Base", money(net));
                retencion.setAttribute("Impuesto", satImpuesto(wh.scheme()));
                retencion.setAttribute("TipoFactor", satFactor(wh.factor()));
                retencion.setAttribute("TasaOCuota",
                        wh.rate().setScale(6, RoundingMode.HALF_UP).toPlainString());
                retencion.setAttribute("Importe", money(wh.amount()));
                retenciones.appendChild(retencion);
            }
            impuestos.appendChild(retenciones);
        }
        if (taxes.signum() > 0) {
            // CFDI 4.0 XSD order: Retenciones first, then Traslados.
            Element traslados = element(doc, CFDI_NS, "cfdi:Traslados");
            for (StampLine line : request.lines()) {
                if (hasIeps(line)) {
                    traslados.appendChild(traslado(doc, line, true));
                }
                if (hasIva(line)) {
                    traslados.appendChild(traslado(doc, line, false));
                }
            }
            impuestos.appendChild(traslados);
        }
        return impuestos;
    }

    private static Element pago20(Document doc, StampRequest request, ZoneId zone) {
        PaymentInfo payment = request.payment();
        if (payment == null) {
            throw new ConflictDocumentException(new com.posplatform.shared.api.ConflictException(
                    "FISCAL_INVALID_DOCUMENT",
                    "El CFDI tipo P requiere los datos del complemento de pago"));
        }
        Element complemento = element(doc, CFDI_NS, "cfdi:Complemento");
        Element pagos = element(doc, PAGO20_NS, "pago20:Pagos");
        pagos.setAttribute("Version", "2.0");

        Element pago = element(doc, PAGO20_NS, "pago20:Pago");
        pago.setAttribute("FechaPago", CFDI_DATE.format(payment.date().atZone(zone)));
        pago.setAttribute("FormaDePagoP", payment.formaPago());
        pago.setAttribute("MonedaP", "MXN");
        pago.setAttribute("Monto", money(payment.amount()));

        Element docto = element(doc, PAGO20_NS, "pago20:DoctoRelacionado");
        docto.setAttribute("IdDocumento", request.relatedFiscalId().toString());
        docto.setAttribute("MonedaDR", "MXN");
        docto.setAttribute("NumParcialidad", String.valueOf(Math.max(1, payment.partiality())));
        docto.setAttribute("ImpSaldoAnt", money(payment.outstandingBefore()));
        docto.setAttribute("ImpPagado", money(payment.amount()));
        docto.setAttribute("ImpSaldoInsoluto", money(payment.outstandingAfter()));
        docto.setAttribute("ObjetoImpDR", payment.relatedTaxed() ? "02" : "01");

        pago.appendChild(docto);
        pagos.appendChild(pago);
        complemento.appendChild(pagos);
        return complemento;
    }

    // ---- helpers ----

    /** Serializes the DOM (with XML declaration) exactly as it will be sealed. */
    public static String serialize(Document doc) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible serializar el CFDI", ex);
        }
    }

    private static Element element(Document doc, String ns, String qualifiedName) {
        return doc.createElementNS(ns, qualifiedName);
    }

    static boolean hasIva(StampLine line) {
        return line.taxRateCode() != null && !line.taxRateCode().isBlank();
    }

    static boolean hasIeps(StampLine line) {
        return line.iepsRateCode() != null && !line.iepsRateCode().isBlank();
    }

    static BigDecimal importe(StampLine line) {
        return nz(line.quantity()).multiply(nz(line.unitPrice()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Scheme of the catalog -> SAT Impuesto key (Anexo 20). */
    static String satImpuesto(String scheme) {
        if ("IEPS".equals(scheme)) return "003";
        if ("ISR".equals(scheme)) return "001";
        return "002"; // IVA
    }

    /** Factor of the catalog -> SAT TipoFactor (Anexo 20). */
    static String satFactor(String factor) {
        return "EXENTO".equals(factor) ? "Exento" : "Tasa";
    }

    private static String money(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal value) {
        return nz(value).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Internal carrier so the checked XML APIs can throw a business conflict. */
    private static final class ConflictDocumentException extends RuntimeException {
        private final com.posplatform.shared.api.ConflictException conflict;

        private ConflictDocumentException(com.posplatform.shared.api.ConflictException conflict) {
            super(conflict.getMessage());
            this.conflict = conflict;
        }
    }
}
