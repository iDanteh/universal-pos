package com.posplatform.modules.invoicing.fiscal;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Loaded CSD material (v0.12 · ADR-022): the SAT certificate plus its private
 * key, with the two CFDI attributes already derived:
 *
 * <ul>
 *   <li>{@code NoCertificado} — certificate serial in DECIMAL (no leading
 *       zeros), exactly as SAT prints it.</li>
 *   <li>{@code Certificado} — the whole X.509 DER, base64 (single line).</li>
 * </ul>
 *
 * Immutable and safe to cache for the process lifetime; the private key is
 * NEVER serialized or logged.
 */
public record CsdMaterial(X509Certificate certificate,
                          PrivateKey privateKey,
                          String noCertificado,
                          String certificadoBase64,
                          String rfc) {
}
