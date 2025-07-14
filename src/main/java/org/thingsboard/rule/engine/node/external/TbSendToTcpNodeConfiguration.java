package org.thingsboard.rule.engine.node.external;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

@Data
public class TbSendToTcpNodeConfiguration implements NodeConfiguration<TbSendToTcpNodeConfiguration> {
    private String hostKey; // Metadata key for host
    private String portKey; // Metadata key for port
    private String tlsKey;  // Metadata key for TLS enable flag
    private TlsConfig tlsConfig = new TlsConfig(); // Consolidated TLS config
    // Legacy/optional fields for compatibility
    private String trustStorePathKey;
    private String trustStorePasswordKey;
    private String keyStorePathKey;
    private String keyStorePasswordKey;

    @Data
    public static class TlsConfig {
        private String certificateKey; // Metadata key for client certificate PEM
        private String privateKeyKey; // Metadata key for private key PEM
        private String privateKeyPassphraseKey; // Metadata key for private key passphrase
        private String caCertificateKey; // Metadata key for CA certificate PEM
        private Boolean verifyServerCertificate; // Whether to verify server cert
        private String serverNameKey; // Metadata key for SNI/server name
        private String alpnProtocolKey; // Metadata key for ALPN protocol
    }

    @Override
    public TbSendToTcpNodeConfiguration defaultConfiguration() {
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("tcpHost");
        config.setPortKey("tcpPort");
        config.setTlsKey("tcpTls");
        TlsConfig tls = new TlsConfig();
        tls.setCertificateKey("tcpClientCert");
        tls.setPrivateKeyKey("tcpClientKey");
        tls.setPrivateKeyPassphraseKey("tcpClientKeyPassphrase");
        tls.setCaCertificateKey("tcpCaCert");
        tls.setVerifyServerCertificate(true);
        tls.setServerNameKey("tcpServerName");
        tls.setAlpnProtocolKey("tcpAlpnProtocol");
        config.setTlsConfig(tls);
        config.setTrustStorePathKey(null);
        config.setTrustStorePasswordKey(null);
        config.setKeyStorePathKey(null);
        config.setKeyStorePasswordKey(null);
        return config;
    }
}
