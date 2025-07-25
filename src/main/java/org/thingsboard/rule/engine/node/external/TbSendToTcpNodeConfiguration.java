package org.thingsboard.rule.engine.node.external;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

@Data
public class TbSendToTcpNodeConfiguration implements NodeConfiguration<TbSendToTcpNodeConfiguration> {
    /**
     * Templatized key for the target host (e.g., "${metadata.tcpHost}").
     */
    private String hostKey;
    /**
     * Templatized key for the target port (e.g., "${metadata.tcpPort}").
     */
    private String portKey;
    /**
     * Templatized key for enabling TLS (e.g., "${metadata.tcpTls}").
     * Should resolve to "true" or "false".
     */
    private String tlsKey;

    /**
     * Payload type: STRING, JSON, BINARY
     */
    private String payloadType; // STRING, JSON, BINARY
    /**
     * Response type: STRING, JSON, BINARY
     */
    private String responseType; // STRING, JSON, BINARY

    /**
     * TLS configuration (PEM or Keystore/Truststore, SNI, ALPN, etc).
     */
    private TlsConfig tlsConfig = new TlsConfig();

    /**
     * TLS configuration for PEM or Keystore/Truststore, SNI, ALPN, and verification options.
     */
    @Data
    public static class TlsConfig {
        /**
         * Templatized key for client certificate PEM (optional, for mutual TLS).
         */
        private String certificateKey;
        /**
         * Templatized key for private key PEM (optional, for mutual TLS).
         */
        private String privateKeyKey;
        /**
         * Templatized key for private key passphrase (optional).
         */
        private String privateKeyPassphraseKey;
        /**
         * Templatized key for CA certificate PEM (optional, for custom trust roots).
         */
        private String caCertificateKey;
        /**
         * Whether to verify the server certificate (default: true). If false, disables validation (INSECURE!).
         */
        private Boolean verifyServerCertificate;
        /**
         * Templatized key for SNI/server name (optional).
         */
        private String serverNameKey;
        /**
         * Templatized key for ALPN protocol (optional).
         */
        private String alpnProtocolKey;
    }

    @Override
    public TbSendToTcpNodeConfiguration defaultConfiguration() {
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${metadata.tcpHost}");
        config.setPortKey("${metadata.tcpPort}");
        config.setTlsKey("${metadata.tcpTls}");
        config.setPayloadType("STRING");
        config.setResponseType("STRING");
        TlsConfig tls = new TlsConfig();
        tls.setCertificateKey("tcpClientCert");
        tls.setPrivateKeyKey("tcpClientKey");
        tls.setPrivateKeyPassphraseKey("tcpClientKeyPassphrase");
        tls.setCaCertificateKey("tcpCaCert");
        tls.setVerifyServerCertificate(true);
        tls.setServerNameKey("tcpServerName");
        tls.setAlpnProtocolKey("tcpAlpnProtocol");
        config.setTlsConfig(tls);
        return config;
    }
}
