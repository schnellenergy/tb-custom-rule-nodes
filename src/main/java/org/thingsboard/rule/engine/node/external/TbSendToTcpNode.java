package org.thingsboard.rule.engine.node.external;

import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.msg.TbMsg;

import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;
import java.net.Socket;

/**
 * Sends data to a TCP endpoint with dynamic TLS/PEM configuration from metadata.
 * Created by Kalaivanan S on 11-July-2025.
 */
@Slf4j
@RuleNode(
    type = ComponentType.EXTERNAL,
    name = "tcp request",
    configClazz = TbSendToTcpNodeConfiguration.class,
    nodeDescription = "Send message data to a TCP endpoint. Target IP, port, and TLS/PEM secrets are provided via message metadata.",
    nodeDetails = "Reads target host, port, and TLS/PEM configuration from metadata using configurable keys. Supports both plain TCP and TLS (with in-memory trust/key stores from PEM).",
    uiResources = {"static/rulenode/custom-nodes-config.js"},
    configDirective = "tbExternalNodeSendToTcpConfig",
    icon = "call_made"
)
public class TbSendToTcpNode implements TbNode {

    private TbSendToTcpNodeConfiguration config;
    private String hostKey;
    private String portKey;
    private String tlsKey;
    private TbSendToTcpNodeConfiguration.TlsConfig tlsConfig;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbSendToTcpNodeConfiguration.class);
        hostKey = config.getHostKey();
        portKey = config.getPortKey();
        tlsKey = config.getTlsKey();
        tlsConfig = config.getTlsConfig();
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        String targetHost = msg.getMetaData().getValue(hostKey);
        String portStr = msg.getMetaData().getValue(portKey);
        String tlsStr = msg.getMetaData().getValue(tlsKey);
        boolean tls = "true".equalsIgnoreCase(tlsStr);
        int targetPort = 0;
        try {
            targetPort = Integer.parseInt(portStr);
        } catch (Exception e) {
            ctx.tellFailure(msg, new IllegalArgumentException("Message doesn't contain a valid port: " + portStr));
            return;
        }
        try {
            OutputStream out;
            Socket socket;
            if (tls) {
                // Read TLS/PEM fields from metadata using keys from tlsConfig
                String caPem = tlsConfig.getCaCertificateKey() != null ? msg.getMetaData().getValue(tlsConfig.getCaCertificateKey()) : null;
                String certPem = tlsConfig.getCertificateKey() != null ? msg.getMetaData().getValue(tlsConfig.getCertificateKey()) : null;
                String keyPem = tlsConfig.getPrivateKeyKey() != null ? msg.getMetaData().getValue(tlsConfig.getPrivateKeyKey()) : null;
                String keyPassword = tlsConfig.getPrivateKeyPassphraseKey() != null ? msg.getMetaData().getValue(tlsConfig.getPrivateKeyPassphraseKey()) : null;
                javax.net.ssl.SSLSocketFactory factory = createSSLSocketFactoryFromPem(caPem, certPem, keyPem, keyPassword);
                socket = factory.createSocket(targetHost, targetPort);
            } else {
                socket = new Socket(targetHost, targetPort);
            }
            out = socket.getOutputStream();
            out.write(msg.getData().getBytes());
            out.flush();
            socket.close();
            ctx.tellNext(msg, TbNodeConnectionType.SUCCESS);
        } catch (Exception e) {
            ctx.tellFailure(msg, e);
        }
    }

    // Helper: create SSLSocketFactory from PEM strings (CA, client cert, private key)
    private javax.net.ssl.SSLSocketFactory createSSLSocketFactoryFromPem(String caPem, String certPem, String keyPem, String keyPassword) throws Exception {
        java.security.KeyStore trustStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        trustStore.load(null, null);
        if (caPem != null && !caPem.isEmpty()) {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(caPem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.security.cert.Certificate caCert = cf.generateCertificate(bis);
            trustStore.setCertificateEntry("ca-cert", caCert);
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        javax.net.ssl.KeyManager[] kms = null;
        if (certPem != null && !certPem.isEmpty() && keyPem != null && !keyPem.isEmpty()) {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            keyStore.load(null, null);
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.io.ByteArrayInputStream certBis = new java.io.ByteArrayInputStream(certPem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.security.cert.Certificate clientCert = cf.generateCertificate(certBis);
            java.security.PrivateKey privateKey = loadPrivateKeyFromPem(keyPem, keyPassword);
            keyStore.setKeyEntry("client-key", privateKey, keyPassword != null ? keyPassword.toCharArray() : new char[0], new java.security.cert.Certificate[]{clientCert});
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPassword != null ? keyPassword.toCharArray() : new char[0]);
            kms = kmf.getKeyManagers();
        }
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(kms, tmf.getTrustManagers(), null);
        return ctx.getSocketFactory();
    }

    // Helper: parse PEM private key (PKCS#8, RSA or EC)
    private java.security.PrivateKey loadPrivateKeyFromPem(String pem, String password) throws Exception {
        String privKeyPEM = pem.replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----(\\r)?\\n?", "")
                .replaceAll("\\s", "");
        byte[] encoded = java.util.Base64.getDecoder().decode(privKeyPEM);
        java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(encoded);
        try {
            return java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (Exception e) {
            return java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec);
        }
    }

    @Override
    public void destroy() {
        // No resources to clean up
    }
}
