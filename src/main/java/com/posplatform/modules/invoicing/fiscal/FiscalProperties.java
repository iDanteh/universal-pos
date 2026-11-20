package com.posplatform.modules.invoicing.fiscal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * FISCAL CONFIGURATION (v0.12 · ADR-022): PAC selection plus the two
 * credential sets a real timbrado needs.
 *
 * <p>Everything is env-overridable and EMPTY by default: the platform boots
 * and stamps with the Mock PAC without a CSD. Selecting
 * {@code posplatform.fiscal.provider=http} makes {@link HttpPacFiscalProvider}
 * fail fast at startup if the CSD or the PAC gateway data is missing — a
 * half-configured fiscal path never silently stamps unsigned documents.</p>
 */
@Component
@ConfigurationProperties(prefix = "posplatform.fiscal")
public class FiscalProperties {

    /** "mock" (default) or "http". */
    private String provider = "mock";

    /** CFDI {@code Fecha} time zone (SAT expects local Mexico time). */
    private String timeZone = "America/Mexico_City";

    private final Csd csd = new Csd();

    private final Pac pac = new Pac();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public Csd getCsd() {
        return csd;
    }

    public Pac getPac() {
        return pac;
    }

    /** Certificado de Sello Digital (.cer/.key DER, password). */
    public static class Csd {

        /** Path or {@code classpath:} location of the SAT .cer (X.509 DER). */
        private String certificate = "";

        /** Path or {@code classpath:} location of the SAT .key (encrypted PKCS#8 DER). */
        private String privateKey = "";

        /** .key password. NEVER logged. */
        private String password = "";

        public String getCertificate() {
            return certificate;
        }

        public void setCertificate(String certificate) {
            this.certificate = certificate;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * Generic PAC GATEWAY contract (ADR-022): a JSON/HTTPS endpoint that
     * accepts a sealed CFDI and returns the stamped XML. Vendor-specific
     * adapters (SW Sapien, Facturama, Finkok...) plug in by implementing the
     * same two calls behind this gateway or by translating them.
     */
    public static class Pac {

        private String baseUrl = "";
        private String token = "";
        private String stampPath = "/stamp";
        private String cancelPath = "/cancel";
        private int timeoutSeconds = 20;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getStampPath() {
            return stampPath;
        }

        public void setStampPath(String stampPath) {
            this.stampPath = stampPath;
        }

        public String getCancelPath() {
            return cancelPath;
        }

        public void setCancelPath(String cancelPath) {
            this.cancelPath = cancelPath;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
