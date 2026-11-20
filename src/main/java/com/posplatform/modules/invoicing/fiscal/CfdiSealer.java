package com.posplatform.modules.invoicing.fiscal;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;

/**
 * CFDI SEALER (v0.12 · ADR-022): turns a built CFDI into a signed one using
 * the OFFICIAL SAT cadena-original XSLT:
 *
 * <ol>
 *   <li>serialize the unsigned DOM (Certificado/NoCertificado already set);</li>
 *   <li>transform it with {@code cadenaoriginal_4_0.xslt} (the resource is the
 *       SAT stylesheet, trimmed to its two real includes: utilerias + Pagos20);</li>
 *   <li>sign the cadena with {@code SHA256withRSA} using the CSD key;</li>
 *   <li>inject {@code Sello} and return the final XML.</li>
 * </ol>
 *
 * <p>Never invents a canonicalization: the cadena is whatever the official
 * XSLT produces, which is the only thing the SAT verifies. The XSLT is
 * compiled ONCE (thread-safe {@link Templates}).</p>
 */
@Component
public class CfdiSealer {

    private static final String XSLT = "/fiscal/cadenaoriginal_4_0.xslt";

    private final Templates templates;

    public CfdiSealer() {
        this.templates = compile();
    }

    /** Signed document plus the audit pieces (cadena + sello). */
    public record SealedCfdi(String xml, String cadenaOriginal, String sello) {
    }

    public SealedCfdi seal(Document document, CsdMaterial csd) {
        String cadena = cadenaOriginal(document);
        String sello = sign(cadena, csd);
        Element root = document.getDocumentElement();
        root.setAttribute("Sello", sello);
        return new SealedCfdi(CfdiXmlWriter.serialize(document), cadena, sello);
    }

    /** Runs the official SAT XSLT against the document (unsigned DOM). */
    public String cadenaOriginal(Document document) {
        try {
            Transformer transformer = templates.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString().trim();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "No fue posible generar la cadena original del CFDI", ex);
        }
    }

    private static String sign(String cadena, CsdMaterial csd) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(csd.privateKey());
            signature.update(cadena.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible sellar el CFDI con el CSD", ex);
        }
    }

    private static Templates compile() {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            // Includes are resolved from OUR classpath only (never the network).
            factory.setURIResolver((href, base) -> {
                String clean = href.startsWith("/") ? href : "/fiscal/" + href;
                InputStream in = CfdiSealer.class.getResourceAsStream(clean);
                if (in == null) {
                    throw new IllegalStateException(
                            "No existe el recurso XSLT requerido: " + clean);
                }
                return new StreamSource(in, clean);
            });
            try (InputStream xslt = CfdiSealer.class.getResourceAsStream(XSLT)) {
                if (xslt == null) {
                    throw new IllegalStateException("No existe el XSLT oficial " + XSLT);
                }
                return factory.newTemplates(new StreamSource(xslt, XSLT));
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "No fue posible compilar el XSLT de cadena original", ex);
        }
    }
}
