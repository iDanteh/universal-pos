package com.posplatform.modules.invoicing.fiscal;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSD loader (v0.12 · ADR-022): reads the SAT {@code .cer} (X.509 DER) and
 * {@code .key} (PKCS#8 DER, usually password-encrypted) and derives the CFDI
 * attributes.
 *
 * <p>The encrypted-key path is delegated to BouncyCastle's
 * {@link PKCS8EncryptedPrivateKeyInfo}, which understands both the legacy
 * PBES1 (3DES) SAT files and modern PBES2 (AES) files — the JDK alone only
 * covers part of that matrix.</p>
 *
 * <p>Every failure is fail-closed with an actionable message that NEVER
 * includes the password or key bytes.</p>
 */
public final class CsdService {

    static {
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(
                    new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    /** Subject attribute SAT prints as RFC (x500UniqueIdentifier, OID 2.5.4.45). */
    private static final Pattern RFC_IN_SUBJECT = Pattern.compile(
            "(?:^|,)\\s*(?:OID\\.)?(?:2\\.5\\.4\\.45|x500UniqueIdentifier|UID)\\s*=\\s*([A-Z&Ñ0-9]{12,13})",
            Pattern.CASE_INSENSITIVE);

    public CsdMaterial load(FiscalProperties.Csd config) {
        if (config == null) {
            throw new IllegalStateException("CSD no configurado");
        }
        String certificateLocation = require(config.getCertificate(),
                "posplatform.fiscal.csd.certificate");
        String keyLocation = require(config.getPrivateKey(),
                "posplatform.fiscal.csd.private-key");
        String password = config.getPassword();
        if (password == null) {
            throw new IllegalStateException(
                    "Falta posplatform.fiscal.csd.password (password del .key)");
        }
        return load(read(certificateLocation, "certificado .cer"),
                read(keyLocation, "llave .key"), password.toCharArray());
    }

    public CsdMaterial load(byte[] cer, byte[] key, char[] password) {
        X509Certificate certificate = readCertificate(cer);
        PrivateKey privateKey = readPrivateKey(key, password);
        assertKeyMatchesCertificate(certificate, privateKey);

        String noCertificado = certificate.getSerialNumber().toString();
        String certificadoBase64 = Base64.getEncoder().encodeToString(safeEncoded(certificate));
        String rfc = rfcOf(certificate);
        return new CsdMaterial(certificate, privateKey, noCertificado,
                certificadoBase64, rfc);
    }

    private static X509Certificate readCertificate(byte[] der) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            try (InputStream in = new ByteArrayInputStream(der)) {
                return (X509Certificate) factory.generateCertificate(in);
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "El certificado .cer no es un X.509 DER válido", ex);
        }
    }

    private static PrivateKey readPrivateKey(byte[] der, char[] password) {
        // 1) Plain PKCS#8 (some PAC tooling exports it unencrypted).
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ignored) {
            // fall through to the encrypted container
        }
        // 2) Encrypted PKCS#8 (the normal SAT .key file).
        try {
            PKCS8EncryptedPrivateKeyInfo encrypted = new PKCS8EncryptedPrivateKeyInfo(der);
            InputDecryptorProvider decryptor =
                    new JceOpenSSLPKCS8DecryptorProviderBuilder()
                            .setProvider("BC")
                            .build(password);
            PrivateKeyInfo info = encrypted.decryptPrivateKeyInfo(decryptor);
            return new JcaPEMKeyConverter().setProvider("BC").getPrivateKey(info);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "No fue posible abrir la llave .key: password incorrecto o "
                            + "formato no soportado (PKCS#8 cifrado)", ex);
        }
    }

    private static void assertKeyMatchesCertificate(X509Certificate certificate,
                                                     PrivateKey privateKey) {
        PublicKey publicKey = certificate.getPublicKey();
        if (publicKey instanceof RSAPublicKey rsaPublic
                && privateKey instanceof RSAPrivateCrtKey rsaPrivate) {
            if (!rsaPublic.getModulus().equals(rsaPrivate.getModulus())) {
                throw new IllegalStateException(
                        "El certificado .cer y la llave .key no corresponden "
                                + "(módulo RSA distinto)");
            }
        }
    }

    private static String rfcOf(X509Certificate certificate) {
        // SAT CSDs carry the RFC in x500UniqueIdentifier (2.5.4.45). Java's
        // X500Principal may print unknown OIDs as hex, so read the DER with BC.
        try {
            org.bouncycastle.asn1.x500.X500Name name = org.bouncycastle.asn1.x500.X500Name
                    .getInstance(certificate.getSubjectX500Principal().getEncoded());
            for (org.bouncycastle.asn1.x500.RDN rdn : name.getRDNs()) {
                for (org.bouncycastle.asn1.x500.AttributeTypeAndValue atv
                        : rdn.getTypesAndValues()) {
                    if ("2.5.4.45".equals(atv.getType().getId())) {
                        String value = atv.getValue().toString().toUpperCase(Locale.ROOT);
                        if (value.matches("[A-Z&Ñ0-9]{12,13}")) {
                            return value;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to the string heuristics
        }
        String subject = certificate.getSubjectX500Principal().getName();
        Matcher matcher = RFC_IN_SUBJECT.matcher(subject);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        // Last resort: an RFC-looking token anywhere in the subject.
        Matcher any = Pattern.compile("\\b([A-Z&Ñ]{3,4}[0-9]{6}[A-Z0-9]{3})\\b").matcher(subject);
        if (any.find()) {
            return any.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static byte[] safeEncoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible serializar el certificado", ex);
        }
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta " + property + " para el PAC real");
        }
        return value;
    }

    private static byte[] read(String location, String label) {
        try {
            if (location.startsWith("classpath:")) {
                String resource = location.substring("classpath:".length());
                try (InputStream in = CsdService.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IllegalStateException("No existe el " + label
                                + " en el classpath: " + resource);
                    }
                    return in.readAllBytes();
                }
            }
            Path path = Path.of(location);
            if (!Files.isReadable(path)) {
                throw new IllegalStateException("No se puede leer el " + label
                        + ": " + path.toAbsolutePath());
            }
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new IllegalStateException("No se puede leer el " + label
                    + ": " + location, ex);
        }
    }
}
