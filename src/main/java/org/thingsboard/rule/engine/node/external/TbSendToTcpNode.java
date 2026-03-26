/**
 * Copyright © 2018-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.node.external;

import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends data to a TCP endpoint with dynamic TLS/PEM configuration from
 * metadata.
 * Created by Kalaivanan S on 11-July-2025.
 */
@Slf4j
@RuleNode(type = ComponentType.EXTERNAL, name = "tcp request", configClazz = TbSendToTcpNodeConfiguration.class, nodeDescription = "Send message data to a TCP endpoint. Payload data type can be one of TEXT, JSON or BINARY.", nodeDetails = "v1.0.0: Reads target host, port, and TLS/PEM configuration from metadata or message using templatized keys <code>${metadata_key}</code>, <code>$[message_key]</code>. The message data must contain a <code>payload</code> key (e.g., <code>{\"payload\":...}</code). The value of 'payload' is sent as the TCP payload. Supports both plain TCP and TLS (with trust credentials shall be passed as part of the message data or metadata).", uiResources = {
        "static/rulenode/custom-nodes-config.js" }, configDirective = "tbExternalNodeSendToTcpConfig", icon = "call_made")
public class TbSendToTcpNode implements TbNode {

    private TbSendToTcpNodeConfiguration config;
    private String hostKey;
    private String portKey;
    private boolean tls;
    private TbSendToTcpNodeConfiguration.TlsConfig tlsConfig;
    private String payloadType;
    private String responseType;

    // Client factory to allow tests to inject mocks. Defaults to real client
    // constructor.
    public interface ClientFactory {
        TbTcpClient create(String host, int port, boolean tls, javax.net.ssl.SSLSocketFactory sslFactory,
                int connectTimeoutMs, int readTimeoutMs);
    }

    public static volatile ClientFactory clientFactory = (host, port, tls, sslFactory, connectTimeoutMs,
            readTimeoutMs) -> new TbTcpClient(host, port, tls, sslFactory, connectTimeoutMs, readTimeoutMs);

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbSendToTcpNodeConfiguration.class);
        hostKey = config.getHostKey() != null ? config.getHostKey() : "${tcpHost}";
        portKey = config.getPortKey() != null ? config.getPortKey() : "${tcpPort}";
        this.tls = config.isTls();
        tlsConfig = config.getTlsConfig();
        payloadType = config.getPayloadType() != null ? config.getPayloadType() : "TEXT";
        responseType = config.getResponseType() != null ? config.getResponseType() : "TEXT";
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        String targetHost, portStr;
        try {
            targetHost = TbNodeUtils.processPattern(hostKey, msg);
            portStr = TbNodeUtils.processPattern(portKey, msg);
        } catch (IllegalArgumentException iae) {
            log.error("Failed to process pattern for host/port", iae);
            ctx.tellFailure(msg,
                    new IllegalArgumentException("Failed to process pattern for host/port: " + iae.getMessage(), iae));
            return;
        }

        int targetPort = 0;
        try {
            targetPort = Integer.parseInt(portStr);
        } catch (Exception e) {
            log.error("Message doesn't contain a valid port: {}", portStr);
            ctx.tellFailure(msg, new IllegalArgumentException("Message doesn't contain a valid port: " + portStr));
            return;
        }
        try {
            javax.net.ssl.SSLSocketFactory sslFactory = null;
            if (tls) {
                String caPem = tlsConfig.getCaCertificateKey() != null
                        ? TbNodeUtils.processPattern(tlsConfig.getCaCertificateKey(), msg)
                        : null;
                String certPem = tlsConfig.getCertificateKey() != null
                        ? TbNodeUtils.processPattern(tlsConfig.getCertificateKey(), msg)
                        : null;
                String keyPem = tlsConfig.getPrivateKeyKey() != null
                        ? TbNodeUtils.processPattern(tlsConfig.getPrivateKeyKey(), msg)
                        : null;
                String keyPassword = tlsConfig.getPrivateKeyPassphraseKey() != null
                        ? TbNodeUtils.processPattern(tlsConfig.getPrivateKeyPassphraseKey(), msg)
                        : null;
                boolean verifyServerCert = tlsConfig.getVerifyServerCertificate() == null
                        || tlsConfig.getVerifyServerCertificate();
                sslFactory = createSSLSocketFactoryFromPem(caPem, certPem, keyPem, keyPassword, verifyServerCert);
            }
            TbMsgDataType pt = TbMsgDataType.valueOf(payloadType.toUpperCase());
            TbMsgDataType rt = TbMsgDataType.valueOf(responseType.toUpperCase());
            int connectTimeout = config.getConnectTimeout() > 0 ? config.getConnectTimeout() : 1000;
            int readTimeout = config.getReadTimeout() > 0 ? config.getReadTimeout() : 5000;
            TbTcpClient client = clientFactory.create(targetHost, targetPort, tls, sslFactory, connectTimeout,
                    readTimeout);
            String payload = null;
            try {
                com.fasterxml.jackson.databind.JsonNode dataNode = com.fasterxml.jackson.databind.node.NullNode
                        .getInstance();
                if (msg.getData() != null && !msg.getData().isEmpty()) {
                    dataNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(msg.getData());
                }
                if (dataNode.has("payload")) {
                    payload = dataNode.get("payload").asText();
                }
            } catch (Exception ex) {
                log.error("Failed to parse message data for 'payload' key", ex);
                ctx.tellFailure(msg, new IllegalArgumentException(
                        "Failed to parse message data for 'payload' key: " + ex.getMessage()));
                return;
            }
            if (payload == null) {
                log.error("No 'payload' key found in msg data");
                ctx.tellFailure(msg, new IllegalArgumentException("No 'payload' key found in msg data"));
                return;
            }
            try {
                String response = client.sendRequest(payload, pt, rt);

                // Convert response to a TbMsg
                TbMsg responseMsg = msg.transform()
                        .data(response)
                        .dataType(rt) // Keep original response type
                        .build();
                log.debug("Received TCP response: {}", response);
                ctx.tellSuccess(responseMsg);
            } catch (IllegalArgumentException | java.io.IOException e) {
                log.error("Failed to encode payload: {}", payload, e);
                ctx.tellFailure(msg,
                        new IllegalArgumentException("Payload is not valid for type " + pt + ": " + e.getMessage()));
                return;
            }
        } catch (Exception e) {
            log.error("TCP node failed to process message", e.fillInStackTrace());
            ctx.tellFailure(msg, new TbNodeException("Unable to process message: " + e.getLocalizedMessage()));
        }
    }

    // Helper: create SSLSocketFactory from PEM strings (CA, client cert, private
    // key)
    private javax.net.ssl.SSLSocketFactory createSSLSocketFactoryFromPem(String caPem, String certPem, String keyPem,
            String keyPassword, boolean verifyServerCert) throws Exception {
        java.security.KeyStore trustStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        trustStore.load(null, null);
        if (caPem != null && !caPem.isEmpty()) {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(
                    caPem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.security.cert.Certificate caCert = cf.generateCertificate(bis);
            trustStore.setCertificateEntry("ca-cert", caCert);
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        javax.net.ssl.TrustManager[] trustManagers = tmf.getTrustManagers();
        if (!verifyServerCert) {
            trustManagers = new javax.net.ssl.TrustManager[] { new javax.net.ssl.X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }

                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                }
            } };
        }
        javax.net.ssl.KeyManager[] kms = null;
        if (certPem != null && !certPem.isEmpty() && keyPem != null && !keyPem.isEmpty()) {
            java.security.KeyStore keyStore = java.security.KeyStore
                    .getInstance(java.security.KeyStore.getDefaultType());
            keyStore.load(null, null);
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.io.ByteArrayInputStream certBis = new java.io.ByteArrayInputStream(
                    certPem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.security.cert.Certificate clientCert = cf.generateCertificate(certBis);
            java.security.PrivateKey privateKey = loadPrivateKeyFromPem(keyPem, keyPassword);
            keyStore.setKeyEntry("client-key", privateKey,
                    keyPassword != null ? keyPassword.toCharArray() : new char[0],
                    new java.security.cert.Certificate[] { clientCert });
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory
                    .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPassword != null ? keyPassword.toCharArray() : new char[0]);
            kms = kmf.getKeyManagers();
        }
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(kms, trustManagers, null);
        return ctx.getSocketFactory();
    }

    // Helper: parse PEM private key (PKCS#8, PKCS#1 RSA, or SEC1 EC)
    private java.security.PrivateKey loadPrivateKeyFromPem(String pem, String password) throws Exception {
        String trimmedPem = pem.trim();

        // Detect key format from PEM header
        boolean isPkcs1Rsa = trimmedPem.contains("-----BEGIN RSA PRIVATE KEY-----");
        boolean isSec1Ec = trimmedPem.contains("-----BEGIN EC PRIVATE KEY-----");
        boolean isPkcs8 = trimmedPem.contains("-----BEGIN PRIVATE KEY-----");

        // Strip PEM headers/footers and whitespace
        String privKeyPEM = pem.replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----(\\r)?\\n?", "")
                .replaceAll("\\s", "");
        byte[] encoded = java.util.Base64.getDecoder().decode(privKeyPEM);

        if (isPkcs8) {
            // Standard PKCS#8 format - try RSA first, then EC
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(encoded);
            try {
                return java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
            } catch (Exception e) {
                return java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec);
            }
        } else if (isPkcs1Rsa) {
            // PKCS#1 RSA format - need to wrap in PKCS#8 structure
            byte[] pkcs8Bytes = wrapRsaPkcs1InPkcs8(encoded);
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(pkcs8Bytes);
            return java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } else if (isSec1Ec) {
            // SEC1 EC format - parse the EC parameters and private key
            return parseEc1PrivateKey(encoded);
        } else {
            // Unknown format - try PKCS#8 as fallback
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(encoded);
            try {
                return java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
            } catch (Exception e) {
                return java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec);
            }
        }
    }

    // Wrap PKCS#1 RSA private key in PKCS#8 structure
    private byte[] wrapRsaPkcs1InPkcs8(byte[] pkcs1Bytes) throws Exception {
        // PKCS#8 header for RSA: SEQUENCE { SEQUENCE { OID rsaEncryption, NULL }, OCTET
        // STRING { pkcs1 key } }
        byte[] rsaOid = new byte[] { 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01,
                0x01 };
        byte[] nullTag = new byte[] { 0x05, 0x00 };

        // Build AlgorithmIdentifier SEQUENCE
        byte[] algIdSeq = buildAsn1Sequence(concatBytes(rsaOid, nullTag));

        // Build OCTET STRING containing PKCS#1 key
        byte[] octetString = buildAsn1OctetString(pkcs1Bytes);

        // Build outer SEQUENCE containing version(0), AlgorithmIdentifier, and OCTET
        // STRING
        byte[] version = new byte[] { 0x02, 0x01, 0x00 }; // INTEGER 0
        byte[] pkcs8Content = concatBytes(version, concatBytes(algIdSeq, octetString));
        return buildAsn1Sequence(pkcs8Content);
    }

    // Parse SEC1 EC private key format
    private java.security.PrivateKey parseEc1PrivateKey(byte[] sec1Bytes) throws Exception {
        // SEC1 format: SEQUENCE { version INTEGER, privateKey OCTET STRING, [0]
        // parameters, [1] publicKey }
        // We need to wrap this in PKCS#8 format for Java's KeyFactory

        // Extract the curve OID from the SEC1 structure (context tag [0])
        byte[] curveOid = extractCurveOidFromSec1(sec1Bytes);
        if (curveOid == null) {
            // Default to prime256v1 (P-256) if no curve specified
            curveOid = new byte[] { 0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07 };
        }

        // Build PKCS#8 structure for EC key
        // AlgorithmIdentifier: SEQUENCE { OID ecPublicKey, OID namedCurve }
        byte[] ecOid = new byte[] { 0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01 };
        byte[] algIdContent = concatBytes(ecOid, curveOid);
        byte[] algIdSeq = buildAsn1Sequence(algIdContent);

        // Build OCTET STRING containing SEC1 key
        byte[] octetString = buildAsn1OctetString(sec1Bytes);

        // Build outer SEQUENCE: version(0), AlgorithmIdentifier, OCTET STRING
        byte[] version = new byte[] { 0x02, 0x01, 0x00 };
        byte[] pkcs8Content = concatBytes(version, concatBytes(algIdSeq, octetString));
        byte[] pkcs8Bytes = buildAsn1Sequence(pkcs8Content);

        java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(pkcs8Bytes);
        return java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec);
    }

    // Extract curve OID from SEC1 structure
    private byte[] extractCurveOidFromSec1(byte[] sec1Bytes) {
        // Look for context tag [0] which contains the curve parameters (OID)
        int pos = 0;
        if (sec1Bytes[pos] != 0x30)
            return null; // Not a SEQUENCE
        pos++;
        pos += lengthOfLength(sec1Bytes, pos);

        // Skip version
        if (sec1Bytes[pos] != 0x02)
            return null;
        pos++;
        int verLen = sec1Bytes[pos] & 0xFF;
        pos += 1 + verLen;

        // Skip private key OCTET STRING
        if (sec1Bytes[pos] != 0x04)
            return null;
        pos++;
        int keyLen = readAsn1Length(sec1Bytes, pos);
        pos += lengthOfLength(sec1Bytes, pos) + keyLen;

        // Look for context tag [0] = 0xA0
        if (pos < sec1Bytes.length && (sec1Bytes[pos] & 0xFF) == 0xA0) {
            pos++;
            int paramLen = readAsn1Length(sec1Bytes, pos);
            pos += lengthOfLength(sec1Bytes, pos);
            // Extract the OID
            byte[] oid = new byte[paramLen];
            System.arraycopy(sec1Bytes, pos, oid, 0, paramLen);
            return oid;
        }
        return null;
    }

    private int readAsn1Length(byte[] data, int pos) {
        int len = data[pos] & 0xFF;
        if (len < 128)
            return len;
        int numBytes = len & 0x7F;
        len = 0;
        for (int i = 0; i < numBytes; i++) {
            len = (len << 8) | (data[pos + 1 + i] & 0xFF);
        }
        return len;
    }

    private int lengthOfLength(byte[] data, int pos) {
        int len = data[pos] & 0xFF;
        if (len < 128)
            return 1;
        return 1 + (len & 0x7F);
    }

    private byte[] buildAsn1Sequence(byte[] content) {
        return buildAsn1Tagged((byte) 0x30, content);
    }

    private byte[] buildAsn1OctetString(byte[] content) {
        return buildAsn1Tagged((byte) 0x04, content);
    }

    private byte[] buildAsn1Tagged(byte tag, byte[] content) {
        byte[] lenBytes = encodeAsn1Length(content.length);
        byte[] result = new byte[1 + lenBytes.length + content.length];
        result[0] = tag;
        System.arraycopy(lenBytes, 0, result, 1, lenBytes.length);
        System.arraycopy(content, 0, result, 1 + lenBytes.length, content.length);
        return result;
    }

    private byte[] encodeAsn1Length(int len) {
        if (len < 128) {
            return new byte[] { (byte) len };
        } else if (len < 256) {
            return new byte[] { (byte) 0x81, (byte) len };
        } else if (len < 65536) {
            return new byte[] { (byte) 0x82, (byte) (len >> 8), (byte) len };
        } else {
            return new byte[] { (byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) len };
        }
    }

    private byte[] concatBytes(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    @Override
    public void destroy() {
        // No resources to clean up
    }
}
