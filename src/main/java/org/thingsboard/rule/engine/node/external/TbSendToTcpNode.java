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
    nodeDescription = "Send message data to a TCP endpoint. Target IP, port, and TLS/PEM secrets are provided via message metadata or message body using templatized keys.",
    nodeDetails = "Reads target host, port, and TLS/PEM configuration from metadata or message using templatized keys (e.g., ${key}, $[key]). Supports both plain TCP and TLS (with in-memory trust/key stores from PEM).",
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
    private String payloadType;
    private String responseType;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbSendToTcpNodeConfiguration.class);
        hostKey = config.getHostKey() != null ? config.getHostKey() : "${tcpHost}";
        portKey = config.getPortKey() != null ? config.getPortKey() : "${tcpPort}";
        tlsKey = config.getTlsKey() != null ? config.getTlsKey() : "${tcpTls}";
        tlsConfig = config.getTlsConfig();
        payloadType = config.getPayloadType() != null ? config.getPayloadType() : "STRING";
        responseType = config.getResponseType() != null ? config.getResponseType() : "STRING";
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        String targetHost, portStr, tlsStr;
        try {
            
            targetHost = TbNodeUtils.processPattern(hostKey, msg);
            portStr = TbNodeUtils.processPattern(portKey, msg);
            tlsStr = TbNodeUtils.processPattern(tlsKey, msg);
        } catch (IllegalArgumentException iae) {
            ctx.tellFailure(msg, new IllegalArgumentException("Failed to process pattern for host/port/tls: " + iae.getMessage(), iae));
            return;
        }
        boolean tls = "true".equalsIgnoreCase(tlsStr);
        int targetPort = 0;
        try {
            targetPort = Integer.parseInt(portStr);
        } catch (Exception e) {
            ctx.tellFailure(msg, new IllegalArgumentException("Message doesn't contain a valid port: " + portStr));
            return;
        }
        try {
            javax.net.ssl.SSLSocketFactory sslFactory = null;
            if (tls) {
                String caPem = tlsConfig.getCaCertificateKey() != null ? TbNodeUtils.processPattern(tlsConfig.getCaCertificateKey(), msg) : null;
                String certPem = tlsConfig.getCertificateKey() != null ? TbNodeUtils.processPattern(tlsConfig.getCertificateKey(), msg) : null;
                String keyPem = tlsConfig.getPrivateKeyKey() != null ? TbNodeUtils.processPattern(tlsConfig.getPrivateKeyKey(), msg) : null;
                String keyPassword = tlsConfig.getPrivateKeyPassphraseKey() != null ? TbNodeUtils.processPattern(tlsConfig.getPrivateKeyPassphraseKey(), msg) : null;
                boolean verifyServerCert = tlsConfig.getVerifyServerCertificate() == null || tlsConfig.getVerifyServerCertificate();
                sslFactory = createSSLSocketFactoryFromPem(caPem, certPem, keyPem, keyPassword, verifyServerCert);
            }
            TbTcpClient.PayloadType pt = TbTcpClient.PayloadType.valueOf(payloadType.toUpperCase());
            TbTcpClient.PayloadType rt = TbTcpClient.PayloadType.valueOf(responseType.toUpperCase());
            TbTcpClient client = new TbTcpClient(targetHost, targetPort, tls, sslFactory, 5000, 5000);
            byte[] payloadBytes;
            if (pt == TbTcpClient.PayloadType.BINARY) {
                payloadBytes = java.util.Base64.getDecoder().decode(msg.getData());
            } else {
                if (pt == TbTcpClient.PayloadType.JSON) {
                    try {
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(msg.getData());
                    } catch (Exception e) {
                        ctx.tellFailure(msg, new IllegalArgumentException("Payload is not valid JSON: " + e.getMessage()));
                        return;
                    }
                }
                payloadBytes = msg.getData().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            byte[] responseBytes = client.sendAndReceive(payloadBytes);
            String decodedResponse = client.decodeResponse(responseBytes, rt);
            // Copy metadata and add tcpResponse
            org.thingsboard.server.common.msg.TbMsgMetaData newMd = new org.thingsboard.server.common.msg.TbMsgMetaData();
            msg.getMetaData().getData().forEach(newMd::putValue);
            newMd.putValue("tcpResponse", decodedResponse);
            TbMsg outMsg = TbMsg.builder()
                .type(msg.getType())
                .originator(msg.getOriginator())
                .metaData(newMd)
                .data(msg.getData())
                .build();
            ctx.tellNext(outMsg, TbNodeConnectionType.SUCCESS);
        } catch (Exception e) {
            ctx.tellFailure(msg, e);
        }
    }

    // Helper: create SSLSocketFactory from PEM strings (CA, client cert, private key)
    private javax.net.ssl.SSLSocketFactory createSSLSocketFactoryFromPem(String caPem, String certPem, String keyPem, String keyPassword, boolean verifyServerCert) throws Exception {
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
        javax.net.ssl.TrustManager[] trustManagers = tmf.getTrustManagers();
        if (!verifyServerCert) {
            trustManagers = new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
            }};
        }
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
        ctx.init(kms, trustManagers, null);
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
