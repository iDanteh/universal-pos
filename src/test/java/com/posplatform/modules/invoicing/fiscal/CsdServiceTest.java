package com.posplatform.modules.invoicing.fiscal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CSD loader (v0.12 · ADR-022): the SAT container shapes must open with the
 * right password and be rejected (fail-closed) otherwise.
 */
class CsdServiceTest {

    @TempDir
    Path dir;

    @Test
    void loadsEncryptedKeyAndDerivesCfdiAttributes() throws Exception {
        TestCsdFactory.Fixture fixture = TestCsdFactory.generate(dir, "AAA010101AAA");

        FiscalProperties.Csd config = new FiscalProperties.Csd();
        config.setCertificate(fixture.cer().toString());
        config.setPrivateKey(fixture.key().toString());
        config.setPassword(TestCsdFactory.PASSWORD);

        CsdMaterial material = new CsdService().load(config);

        assertThat(material.noCertificado()).isEqualTo(fixture.noCertificado());
        assertThat(material.certificadoBase64()).isEqualTo(fixture.certificadoBase64());
        assertThat(material.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(material.rfc()).isEqualTo("AAA010101AAA");
        assertThat(material.certificate().getPublicKey().getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void acceptsUnencryptedPkcs8() throws Exception {
        TestCsdFactory.Fixture fixture = TestCsdFactory.generatePlain(dir, "BBB010101BBB");

        CsdMaterial material = new CsdService()
                .load(java.nio.file.Files.readAllBytes(fixture.cer()),
                        java.nio.file.Files.readAllBytes(fixture.key()),
                        "cualquier-password".toCharArray());

        assertThat(material.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(material.rfc()).isEqualTo("BBB010101BBB");
    }

    @Test
    void wrongPasswordFailsClosed() throws Exception {
        TestCsdFactory.Fixture fixture = TestCsdFactory.generate(dir, "CCC010101CCC");

        assertThatThrownBy(() -> new CsdService().load(
                java.nio.file.Files.readAllBytes(fixture.cer()),
                java.nio.file.Files.readAllBytes(fixture.key()),
                "password-incorrecto".toCharArray()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password incorrecto");
    }

    @Test
    void missingConfigurationFailsClosed() {
        FiscalProperties.Csd empty = new FiscalProperties.Csd();

        assertThatThrownBy(() -> new CsdService().load(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("posplatform.fiscal.csd.certificate");
    }
}
