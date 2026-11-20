package com.posplatform.modules.invoicing.fiscal;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * Generates a disposable CSD for the phase-12 tests: a self-signed RSA
 * certificate (X.509 DER) and its password-encrypted PKCS#8 key — the exact
 * container shape of a SAT .cer/.key pair, so {@link CsdService} exercises
 * the REAL decryption path (BouncyCastle) instead of a stub.
 *
 * <p>Never a production certificate: serial 20261119, 10-year validity,
 * subject RFC in x500UniqueIdentifier (2.5.4.45).</p>
 */
final class TestCsdFactory {

    static final String PASSWORD = "Prueba-12345678";

    private TestCsdFactory() {
    }

    static Fixture generate(Path dir, String rfc) throws Exception {
        return generate(dir, rfc, true);
    }

    static Fixture generatePlain(Path dir, String rfc) throws Exception {
        return generate(dir, rfc, false);
    }

    private static Fixture generate(Path dir, String rfc, boolean encrypted) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, "Emisor Pruebas SA de CV")
                .addRDN(BCStyle.O, "Emisor Pruebas")
                .addRDN(BCStyle.C, "MX")
                .addRDN(new ASN1ObjectIdentifier("2.5.4.45"), rfc)
                .build();
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger("20261119"),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(3650, ChronoUnit.DAYS)),
                subject,
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));

        Files.createDirectories(dir);
        Path cer = dir.resolve("emisor.cer");
        Files.write(cer, certificate.getEncoded());

        Path key = dir.resolve("emisor.key");
        Files.write(key, encrypted
                ? encrypt(keyPair.getPrivate().getEncoded(), PASSWORD)
                : keyPair.getPrivate().getEncoded());

        return new Fixture(cer, key, certificate,
                Base64.getEncoder().encodeToString(certificate.getEncoded()),
                certificate.getSerialNumber().toString(), rfc);
    }

    /** PBES1 + 3DES, the legacy SAT container (BouncyCastle opens it). */
    private static byte[] encrypt(byte[] pkcs8, String password) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBEWithSHA1AndDESede");
        SecretKey secret = factory.generateSecret(new PBEKeySpec(password.toCharArray()));
        Cipher cipher = Cipher.getInstance("PBEWithSHA1AndDESede");
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        byte[] encrypted = cipher.doFinal(pkcs8);
        return new javax.crypto.EncryptedPrivateKeyInfo(cipher.getParameters(), encrypted)
                .getEncoded();
    }

    record Fixture(Path cer, Path key, X509Certificate certificate,
                   String certificadoBase64, String noCertificado, String rfc) {
    }
}
