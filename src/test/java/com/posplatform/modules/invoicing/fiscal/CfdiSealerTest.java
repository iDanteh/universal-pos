package com.posplatform.modules.invoicing.fiscal;

import com.posplatform.modules.invoicing.fiscal.FiscalProvider.PaymentInfo;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampLine;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampRequest;
import com.posplatform.modules.invoicing.fiscal.FiscalProvider.StampWithholding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Signature;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CFDI 4.0 writer + sealer (v0.12 · ADR-022): the official SAT XSLT produces
 * the cadena, SHA256withRSA produces the Sello, and the resulting XML carries
 * every schema element the PAC needs (per-concept taxes, the document
 * summary, retenciones, Pagos 2.0).
 */
class CfdiSealerTest {

    private static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");
    private static final Instant FECHA = Instant.parse("2026-11-19T16:00:00Z");

    @TempDir
    static Path dir;

    private static CsdMaterial csd;
    private static final CfdiSealer SEALER = new CfdiSealer();

    @BeforeAll
    static void loadCsd() throws Exception {
        TestCsdFactory.Fixture fixture = TestCsdFactory.generate(dir, "AAA010101AAA");
        csd = new CsdService().load(
                java.nio.file.Files.readAllBytes(fixture.cer()),
                java.nio.file.Files.readAllBytes(fixture.key()),
                TestCsdFactory.PASSWORD.toCharArray());
    }

    @Test
    void incomeCfdiIsSealedWithTheOfficialChain() throws Exception {
        StampRequest request = incomeRequest();
        Document doc = CfdiXmlWriter.build(request, csd, "01", FECHA, MEXICO);
        CfdiSealer.SealedCfdi sealed = SEALER.seal(doc, csd);

        // --- cadena original (official XSLT order) ---
        assertThat(sealed.cadenaOriginal()).startsWith(
                "||4.0|FA|7|2026-11-19T10:00:00|01|" + csd.noCertificado()
                        + "|100.00|MXN|136.74|I|01|PUE|03100|");
        assertThat(sealed.cadenaOriginal()).contains("|003|Tasa|0.265000|26.50|");
        assertThat(sealed.cadenaOriginal()).contains("|002|Tasa|0.160000|20.24|");
        assertThat(sealed.cadenaOriginal()).contains("|001|10.00|10.00|");

        // --- the Sello verifies with the certificate public key ---
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(csd.certificate().getPublicKey());
        verifier.update(sealed.cadenaOriginal().getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(sealed.sello()))).isTrue();

        // --- schema shape ---
        String xml = sealed.xml();
        assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"");
        assertThat(xml).contains("Sello=\"" + sealed.sello() + "\"");
        assertThat(xml).contains("NoCertificado=\"" + csd.noCertificado() + "\"");
        assertThat(xml).contains("Certificado=\"");
        assertThat(xml).contains("Exportacion=\"01\"");
        assertThat(xml).contains("MetodoPago=\"PUE\"");
        assertThat(xml).contains("FormaPago=\"01\"");
        assertThat(xml).contains("RegimenFiscalReceptor=\"601\"");
        assertThat(xml).contains("DomicilioFiscalReceptor=\"06600\"");
        assertThat(xml).contains("TipoDeComprobante=\"I\"");
        assertThat(xml).contains("TotalImpuestosTrasladados=\"46.74\"");
        assertThat(xml).contains("TotalImpuestosRetenidos=\"10.00\"");
        assertThat(xml).contains("ClaveProdServ=\"01010101\"");
        assertThat(xml).doesNotContain("FacAtrAdquirente");

        Document parsed = parse(xml);
        Element root = parsed.getDocumentElement();
        // Exactly ONE document-level Impuestos (traslados + retenciones together).
        assertThat(directChildren(root, "Impuestos")).hasSize(1);
        Element impuestos = directChildren(root, "Impuestos").getFirst();
        assertThat(impuestos.getAttribute("TotalImpuestosTrasladados")).isEqualTo("46.74");
        assertThat(impuestos.getAttribute("TotalImpuestosRetenidos")).isEqualTo("10.00");

        // The concept carries its own two traslados (IEPS on the net, IVA on net+IEPS).
        Element concepto = directChildren(
                first(parsed, CfdiXmlWriter.CFDI_NS, "Conceptos"), "Concepto").getFirst();
        Element conceptoImpuestos = directChildren(concepto, "Impuestos").getFirst();
        List<Element> traslados = directChildren(
                firstByTag(conceptoImpuestos, CfdiXmlWriter.CFDI_NS, "Traslados"), "Traslado");
        assertThat(traslados).hasSize(2);
        assertThat(traslados.get(0).getAttribute("Impuesto")).isEqualTo("003");
        assertThat(traslados.get(0).getAttribute("Base")).isEqualTo("100.00");
        assertThat(traslados.get(0).getAttribute("TasaOCuota")).isEqualTo("0.265000");
        assertThat(traslados.get(1).getAttribute("Impuesto")).isEqualTo("002");
        assertThat(traslados.get(1).getAttribute("Base")).isEqualTo("126.50");
        assertThat(traslados.get(1).getAttribute("TasaOCuota")).isEqualTo("0.160000");
        assertThat(traslados.get(1).getAttribute("Importe")).isEqualTo("20.24");
    }

    @Test
    void paymentComplementSealsPagos20() throws Exception {
        UUID related = UUID.fromString("11111111-1111-4111-8111-111111111111");
        PaymentInfo payment = new PaymentInfo("03", new BigDecimal("50.00"),
                Instant.parse("2026-11-19T17:30:00Z"), new BigDecimal("136.74"),
                new BigDecimal("86.74"), 1, true);
        StampRequest request = new StampRequest(
                "PG", 3, "P", "AAA010101AAA", "Emisor Pruebas SA de CV", "601", "03100",
                "RECE123456X1A", "Cliente Pruebas SA de CV", "601", "G01", "06600",
                List.of(new StampLine("Pago", null, "ACT", BigDecimal.ONE, new BigDecimal("50.00"),
                        BigDecimal.ZERO, new BigDecimal("50.00"), null, null, null, null,
                        BigDecimal.ZERO, null, null, null, null, BigDecimal.ZERO)),
                new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("50.00"), related, List.of(), "03", payment, null);

        Document doc = CfdiXmlWriter.build(request, csd, "03", FECHA, MEXICO);
        CfdiSealer.SealedCfdi sealed = SEALER.seal(doc, csd);
        String xml = sealed.xml();

        // tipo P: no FormaPago/MetodoPago root attributes; payment in the complement.
        assertThat(xml).doesNotContain("FormaPago=");
        assertThat(xml).doesNotContain("MetodoPago=");
        assertThat(xml).contains("TipoDeComprobante=\"P\"");
        assertThat(xml).contains("UsoCFDI=\"CP01\"");
        assertThat(xml).contains("ClaveProdServ=\"84111506\"");
        assertThat(xml).contains("xmlns:pago20=\"http://www.sat.gob.mx/Pagos20\"");

        Document parsed = parse(xml);
        NodeList pagos = parsed.getElementsByTagNameNS(CfdiXmlWriter.PAGO20_NS, "Pago");
        assertThat(pagos.getLength()).isEqualTo(1);
        Element pago = (Element) pagos.item(0);
        assertThat(pago.getAttribute("FechaPago")).isEqualTo("2026-11-19T11:30:00");
        assertThat(pago.getAttribute("FormaDePagoP")).isEqualTo("03");
        assertThat(pago.getAttribute("MonedaP")).isEqualTo("MXN");
        assertThat(pago.getAttribute("Monto")).isEqualTo("50.00");

        NodeList doctos = parsed.getElementsByTagNameNS(
                CfdiXmlWriter.PAGO20_NS, "DoctoRelacionado");
        assertThat(doctos.getLength()).isEqualTo(1);
        Element docto = (Element) doctos.item(0);
        assertThat(docto.getAttribute("IdDocumento")).isEqualTo(related.toString());
        assertThat(docto.getAttribute("NumParcialidad")).isEqualTo("1");
        assertThat(docto.getAttribute("ImpSaldoAnt")).isEqualTo("136.74");
        assertThat(docto.getAttribute("ImpPagado")).isEqualTo("50.00");
        assertThat(docto.getAttribute("ImpSaldoInsoluto")).isEqualTo("86.74");
        assertThat(docto.getAttribute("ObjetoImpDR")).isEqualTo("02");

        assertThat(sealed.cadenaOriginal())
                .startsWith("||4.0|PG|3|2026-11-19T10:00:00|" + csd.noCertificado());
        assertThat(sealed.cadenaOriginal()).contains("|2.0|2026-11-19T11:30:00|03|MXN|50.00|");
    }

    @Test
    void creditNoteCarriesTheRelatedUuid() throws Exception {
        UUID related = UUID.fromString("22222222-2222-4222-8222-222222222222");
        StampRequest request = new StampRequest(
                "NC", 2, "E", "AAA010101AAA", "Emisor Pruebas SA de CV", "601", "03100",
                "RECE123456X1A", "Cliente Pruebas SA de CV", "601", "G01", "06600",
                List.of(new StampLine("Devolución SKU-1", "SKU-1", "H87", BigDecimal.ONE,
                        new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                        "IVA16", "IVA", "TASA", new BigDecimal("0.160000"),
                        new BigDecimal("16.00"), null, null, null, null, BigDecimal.ZERO)),
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("16.00"),
                new BigDecimal("116.00"), related, List.of(), "01", null, null);

        Document doc = CfdiXmlWriter.build(request, csd, "01", FECHA, MEXICO);
        CfdiSealer.SealedCfdi sealed = SEALER.seal(doc, csd);

        assertThat(sealed.xml()).contains("TipoDeComprobante=\"E\"");
        assertThat(sealed.xml()).contains("<cfdi:CfdiRelacionados TipoRelacion=\"01\">");
        assertThat(sealed.xml()).contains("<cfdi:CfdiRelacionado UUID=\"" + related + "\"/>");
        assertThat(sealed.cadenaOriginal()).contains("|01|" + related + "|");
    }

    // ---- fixtures ----

    /** Phase-11 money: 100 net, IEPS 26.50, IVA 20.24, retention 10.00, total 136.74. */
    private static StampRequest incomeRequest() {
        StampLine line = new StampLine(
                "Producto IEPS", "F11-1", "H87", BigDecimal.ONE, new BigDecimal("100.0000"),
                BigDecimal.ZERO, new BigDecimal("100.00"),
                "IVA16", "IVA", "TASA", new BigDecimal("0.160000"), new BigDecimal("20.24"),
                "IEPS265", "IEPS", "TASA", new BigDecimal("0.265000"), new BigDecimal("26.50"));
        return new StampRequest(
                "FA", 7, "I", "AAA010101AAA", "Emisor Pruebas SA de CV", "601", "03100",
                "RECE123456X1A", "Cliente Pruebas SA de CV", "601", "G01", "06600",
                List.of(line), new BigDecimal("100.00"), BigDecimal.ZERO,
                new BigDecimal("46.74"), new BigDecimal("136.74"), null,
                List.of(new StampWithholding("RETISR10", "ISR", "TASA",
                        new BigDecimal("0.100000"), new BigDecimal("10.00"))),
                "01", null, null);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> result = new java.util.ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element
                    && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element first(Document doc, String ns, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS(ns, localName);
        assertThat(nodes.getLength()).isGreaterThan(0);
        return (Element) nodes.item(0);
    }

    private static Element firstByTag(Element parent, String ns, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(ns, localName);
        assertThat(nodes.getLength()).isGreaterThan(0);
        return (Element) nodes.item(0);
    }
}
