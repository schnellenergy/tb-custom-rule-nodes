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
import org.thingsboard.server.common.msg.TbMsgMetaData;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends data to a TCP endpoint with dynamic TLS/PEM configuration from
 * metadata.
 *
 * <p>Changes for SmartLights_2.0 issues #4433 / #4434 / #4435:
 * <ul>
 *   <li><strong>RPC-derived timeouts (#4435)</strong> — when metadata key
 *       {@code rpcTimeoutMs} is present, {@code connectTimeout} and
 *       {@code readTimeout} are derived as 20 % / 75 % of the budget. Static
 *       config values are used as fallback.</li>
 *   <li><strong>Error metadata enrichment (#4433 / #4434 / #4435)</strong> —
 *       any {@link TbTcpException} is caught and its phase, error class, detail
 *       and target are written into the outbound message metadata before
 *       {@code tellFailure}.</li>
 *   <li><strong>cmd_id correlation (#4435)</strong> — resolved from metadata
 *       via {@code cmdIdKey}; included in both success and failure metadata.</li>
 *   <li><strong>Timing metadata on success</strong> — {@code tcpConnectMs},
 *       {@code tcpWriteMs}, {@code tcpReadMs} added to the success message.</li>
 * </ul>
 *
 * Created by Kalaivanan S on 11-July-2025.
 */
@Slf4j
@RuleNode(type = ComponentType.EXTERNAL, name = "tcp request", configClazz = TbSendToTcpNodeConfiguration.class, nodeDescription = "Send message data to a TCP endpoint. Payload data type can be one of TEXT, JSON or BINARY.", nodeDetails = "<span>Reads target host and port using templatized keys <code>${metadata_key}</code> or <code>$[message_key]</code>. The incoming message JSON must contain a <code>payload</code> field whose value is sent as the TCP payload. The response is attached to the outbound message.</span><br/><br/>"
                + "<span><strong>Connection Settings</strong></span>"
                + "<ul>"
                + "<li><code>hostKey</code> / <code>portKey</code> &ndash; Template expressions that resolve to the target host and port from metadata or message data. Defaults: <code>${tcpHost}</code> / <code>${tcpPort}</code>.</li>"
                + "<li><code>connectTimeout</code> &ndash; Fallback maximum time (ms) for the TCP handshake when <code>rpcTimeoutMs</code> is absent. Default: <code>1000 ms</code>.</li>"
                + "<li><code>readTimeout</code> &ndash; Fallback maximum idle time (ms) while reading when <code>rpcTimeoutMs</code> is absent. Default: <code>5000 ms</code>.</li>"
                + "<li><code>rpcTimeoutKey</code> &ndash; Metadata key carrying the overall RPC budget (ms). When present, connect and read timeouts are derived as 20 % / 75 % of this value. Default: <code>${rpcTimeoutMs}</code>.</li>"
                + "<li><code>keepAlive</code> &ndash; When <code>true</code>, sockets are kept open and reused from the pool. Default: <code>true</code>.</li>"
                + "<li><code>maxConnections</code> &ndash; Size of the connection pool per host/port pair. Default: <code>10</code>.</li>"
                + "<li><code>maxIdleMs</code> &ndash; Maximum idle time (ms) before a pooled socket is evicted. Default: <code>120000 ms</code> (2 min).</li>"
                + "</ul>"
                + "<span><strong>Command Correlation</strong></span>"
                + "<ul>"
                + "<li><code>cmdIdKey</code> &ndash; Metadata template that resolves to a command identifier. Forwarded in both success and failure metadata as <code>cmdId</code>. Default: <code>${cmdId}</code>.</li>"
                + "</ul>"
                + "<span><strong>Payload &amp; Response Types</strong></span>"
                + "<ul>"
                + "<li><code>payloadType</code> &ndash; How the <code>payload</code> field is serialized before sending: <code>TEXT</code> (UTF-8 string), <code>JSON</code> (stringified JSON), or <code>BINARY</code> (Base64-decoded bytes). Default: <code>TEXT</code>.</li>"
                + "<li><code>responseType</code> &ndash; How the response bytes are interpreted in the outbound message. Same options as <code>payloadType</code>. Default: <code>TEXT</code>.</li>"
                + "</ul>"
                + "<span><strong>Response Framing</strong> &ndash; controlled by <code>framingType</code>:</span>"
                + "<ul>"
                + "<li><code>NONE</code> (smart-timeout) &ndash; Reads until no new data arrives for <code>fragmentTimeoutMs</code> ms or the server closes the connection.</li>"
                + "<li><code>DELIMITER</code> &ndash; Reads until a specific byte sequence is found. Configure <code>delimiter</code> using escape sequences: <code>\\n</code>, <code>\\r\\n</code>, <code>\\t</code>, or hex e.g. <code>\\x00</code>. Default: <code>\\n</code>.</li>"
                + "<li><code>LENGTH_PREFIX</code> (Netty-style) &ndash; Reads a fixed-size length field from the stream, then reads exactly that many bytes.</li>"
                + "</ul>"
                + "<span><strong>Failure Metadata</strong> &ndash; on the <em>Failure</em> chain the message metadata will contain:</span>"
                + "<ul>"
                + "<li><code>tcpErrorPhase</code> &ndash; CONNECT / WRITE / READ / POOL</li>"
                + "<li><code>tcpErrorClass</code> &ndash; Java exception class name</li>"
                + "<li><code>tcpErrorDetail</code> &ndash; Human-readable error detail including target endpoint</li>"
                + "<li><code>tcpTarget</code> &ndash; host:port</li>"
                + "<li><code>cmdId</code> &ndash; Command correlation identifier</li>"
                + "</ul>"
                + "<span><strong>TLS Configuration</strong> (when <code>tls</code> is enabled)</span>"
                + "<ul>"
                + "<li><code>caCertificateKey</code> &ndash; Templatized key resolving to a PEM CA certificate for server trust verification.</li>"
                + "<li><code>certificateKey</code> / <code>privateKeyKey</code> &ndash; Templatized keys for the client certificate and private key (mutual TLS).</li>"
                + "<li><code>privateKeyPassphraseKey</code> &ndash; Templatized key for the private-key passphrase (if encrypted).</li>"
                + "<li><code>verifyServerCertificate</code> &ndash; Set to <code>false</code> to disable server certificate validation (insecure &ndash; testing only). Default: <code>true</code>.</li>"
                + "</ul>"
                + "<span><strong>Output:</strong> On success the node emits the TCP response as a new message on the <em>Success</em> chain. On any error the original message (with enriched metadata) is forwarded on the <em>Failure</em> chain.</span>", uiResources = {
        "static/rulenode/custom-nodes-config.js" }, configDirective = "tbExternalNodeSendToTcpConfig", icon = "call_made")
public class TbSendToTcpNode implements TbNode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TbSendToTcpNodeConfiguration config;
    private String hostKey;
    private String portKey;
    private boolean tls;
    private TbSendToTcpNodeConfiguration.TlsConfig tlsConfig;
    private String payloadType;
    private String responseType;

    private volatile TbTcpConnectionPool pool;
    private final ConcurrentMap<String, javax.net.ssl.SSLSocketFactory> sslSocketFactoryCache = new ConcurrentHashMap<>();

    // Client factory to allow tests to inject mocks. Defaults to real client
    // constructor.
    public interface ClientFactory {
        TbTcpClient create(String host, int port, boolean tls, javax.net.ssl.SSLSocketFactory sslFactory,
                int connectTimeoutMs, int readTimeoutMs, boolean keepAlive);
    }

    public static volatile ClientFactory clientFactory = (host, port, tls, sslFactory, connectTimeoutMs,
            readTimeoutMs, keepAlive) -> new TbTcpClient(host, port, tls, sslFactory, connectTimeoutMs, readTimeoutMs, keepAlive);

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbSendToTcpNodeConfiguration.class);
        hostKey = config.getHostKey() != null ? config.getHostKey() : "${tcpHost}";
        portKey = config.getPortKey() != null ? config.getPortKey() : "${tcpPort}";
        this.tls = config.isTls();
        tlsConfig = config.getTlsConfig();
        payloadType = config.getPayloadType() != null ? config.getPayloadType() : "TEXT";
        responseType = config.getResponseType() != null ? config.getResponseType() : "TEXT";

        int maxIdleMs = config.getMaxIdleMs() > 0 ? config.getMaxIdleMs() : 120_000;
        pool = new TbTcpConnectionPool(
                config.getMaxConnections(),
                config.isKeepAlive(),
                maxIdleMs);
    }

    @Override
    public void destroy() {
        if (pool != null) {
            pool.closeAll();
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        ctx.getExternalCallExecutor().execute(() -> {
            try {
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

            // ── Resolve cmd_id (#4435) ──────────────────────────────────────────────
            String cmdIdKey = config.getCmdIdKey() != null ? config.getCmdIdKey() : "${cmdId}";
            String cmdId = null;
            try {
                String resolved = TbNodeUtils.processPattern(cmdIdKey, msg);
                // Only use the resolved value if it differs from the raw template (i.e. the key was present)
                if (resolved != null && !resolved.isEmpty() && !resolved.equals(cmdIdKey)) {
                    cmdId = resolved;
                }
            } catch (Exception ignored) {
                // cmdId is optional — absence is not an error
            }
            if (cmdId == null) {
                cmdId = (msg != null && msg.getId() != null) ? msg.getId().toString() : java.util.UUID.randomUUID().toString();
            }

            // ── Derive timeouts from rpcTimeoutMs metadata (#4435) ──────────────────
            String rpcTimeoutKey = config.getRpcTimeoutKey() != null ? config.getRpcTimeoutKey() : "${rpcTimeoutMs}";
            int connectTimeoutMs = config.getConnectTimeout() > 0 ? config.getConnectTimeout() : 1_000;
            int readTimeoutMs    = config.getReadTimeout()    > 0 ? config.getReadTimeout()    : 5_000;
            try {
                String rpcMsStr = TbNodeUtils.processPattern(rpcTimeoutKey, msg);
                if (rpcMsStr != null && !rpcMsStr.isEmpty() && !rpcMsStr.equals(rpcTimeoutKey)) {
                    long rpcMs = Long.parseLong(rpcMsStr);
                    if (rpcMs > 0) {
                        connectTimeoutMs = (int) (rpcMs * 0.20);
                        readTimeoutMs    = (int) (rpcMs * 0.75);
                        if (connectTimeoutMs + readTimeoutMs >= rpcMs) {
                            log.warn("[tcp-node] Timeout nesting violated: connectMs={} + readMs={} >= rpcMs={} for target={}:{}",
                                    connectTimeoutMs, readTimeoutMs, rpcMs, targetHost, targetPort);
                        } else {
                            log.debug("[tcp-node] Derived timeouts from rpcTimeoutMs={}: connectMs={} readMs={}",
                                    rpcMs, connectTimeoutMs, readTimeoutMs);
                        }
                    }
                }
            } catch (NumberFormatException nfe) {
                log.warn("[tcp-node] rpcTimeoutMs metadata value is not a valid number — using static config defaults");
            } catch (Exception ignored) {
                // Key absent from metadata: silently fall back to static config
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

                    String cacheKey = (caPem != null ? caPem : "") + "|" +
                                      (certPem != null ? certPem : "") + "|" +
                                      (keyPem != null ? keyPem : "") + "|" +
                                      (keyPassword != null ? keyPassword : "") + "|" + verifyServerCert;
                    sslFactory = sslSocketFactoryCache.computeIfAbsent(cacheKey, k -> {
                        try {
                            return createSSLSocketFactoryFromPem(caPem, certPem, keyPem, keyPassword, verifyServerCert);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to create SSLSocketFactory", e);
                        }
                    });
                }
                TbMsgDataType pt = TbMsgDataType.valueOf(payloadType.toUpperCase());
                TbMsgDataType rt = TbMsgDataType.valueOf(responseType.toUpperCase());

                String payload = null;
                try {
                    com.fasterxml.jackson.databind.JsonNode dataNode = com.fasterxml.jackson.databind.node.NullNode
                            .getInstance();
                    if (msg.getData() != null && !msg.getData().isEmpty()) {
                        dataNode = MAPPER.readTree(msg.getData());
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

                final String finalCmdId = cmdId;
                final int finalConnectMs = connectTimeoutMs;
                final int finalReadMs = readTimeoutMs;
                final String finalTarget = targetHost + ":" + targetPort;

                TbTcpClient client = null;
                try {
                    client = pool.borrowClient(targetHost, targetPort, tls, sslFactory,
                            finalConnectMs, finalReadMs, finalCmdId);
                    client.setFramingSettings(config);
                    client.setInvocationContext(finalConnectMs, finalReadMs, finalCmdId);
                    String response = client.sendRequest(payload, pt, rt);

                    // Enrich success metadata with timing and correlation
                    long[] timings = client.getLastPhaseTimings();
                    TbMsgMetaData successMeta = msg.getMetaData() != null ? msg.getMetaData().copy() : new TbMsgMetaData();
                    successMeta.putValue("cmdId", finalCmdId);
                    if (timings != null && timings.length >= 3) {
                        if (timings[0] >= 0) successMeta.putValue("tcpConnectMs", String.valueOf(timings[0]));
                        if (timings[1] >= 0) successMeta.putValue("tcpWriteMs",   String.valueOf(timings[1]));
                        if (timings[2] >= 0) successMeta.putValue("tcpReadMs",    String.valueOf(timings[2]));
                    }

                    TbMsg responseMsg;
                    try {
                        responseMsg = msg.transform()
                                .data(response)
                                .dataType(rt)
                                .metaData(successMeta)
                                .build();
                    } catch (Exception ex) {
                        responseMsg = msg;
                    }
                    log.debug("Received TCP response for cmdId={}: {}", finalCmdId, response);

                    pool.returnClient(client, targetHost, targetPort);
                    ctx.tellSuccess(responseMsg);

                } catch (TbTcpException te) {
                    // Structured TCP error (#4433 / #4434 / #4435) —
                    // enrich metadata and forward to the Failure chain.
                    log.error("[tcp-node] {} phase={} target={} cmdId={}: {}",
                            te.getErrorClass(), te.getPhase(), te.getTarget(), finalCmdId, te.getErrorDetail(), te);
                    if (client != null) {
                        pool.discardClient(client, targetHost, targetPort);
                    }
                    // For POOL/BUSY the in-use flag was already released inside the pool
                    TbMsgMetaData failMeta = msg.getMetaData() != null ? msg.getMetaData().copy() : new TbMsgMetaData();
                    failMeta.putValue("cmdId",           finalCmdId);
                    failMeta.putValue("tcpErrorPhase",   te.getPhase());
                    failMeta.putValue("tcpErrorClass",   te.getErrorClass());
                    failMeta.putValue("tcpErrorDetail",  te.getErrorDetail());
                    failMeta.putValue("tcpTarget",       te.getTarget() != null ? te.getTarget() : finalTarget);
                    TbMsg failMsg;
                    try {
                        failMsg = msg.transform().metaData(failMeta).build();
                    } catch (Exception ex) {
                        failMsg = msg;
                    }
                    ctx.tellFailure(failMsg, te);

                } catch (Exception e) {
                    log.error("Failed to send TCP request (unclassified)", e);
                    if (client != null) {
                        pool.discardClient(client, targetHost, targetPort);
                    }
                    TbMsgMetaData failMeta = msg.getMetaData() != null ? msg.getMetaData().copy() : new TbMsgMetaData();
                    failMeta.putValue("cmdId",           finalCmdId);
                    failMeta.putValue("tcpErrorPhase",   "UNKNOWN");
                    failMeta.putValue("tcpErrorClass",   e.getClass().getSimpleName());
                    failMeta.putValue("tcpErrorDetail",  e.getMessage() != null ? e.getMessage() : e.toString());
                    failMeta.putValue("tcpTarget",       finalTarget);
                    TbMsg failMsg;
                    try {
                        failMsg = msg.transform().metaData(failMeta).build();
                    } catch (Exception ex) {
                        failMsg = msg;
                    }
                    ctx.tellFailure(failMsg, new IllegalArgumentException("Failed to send TCP request: " + e.getMessage(), e));
                }
            } catch (Exception e) {
                log.error("TCP node failed to process message", e.fillInStackTrace());
                ctx.tellFailure(msg, new TbNodeException("Unable to process message: " + e.getLocalizedMessage()));
            }
        } catch (Throwable t) {
            log.error("Fatal error processing message in TCP node", t);
            ctx.tellFailure(msg, t instanceof Exception ? (Exception) t : new RuntimeException(t));
        }
    });
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
        byte[] rsaOid = new byte[] { 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01,
                0x01 };
        byte[] nullTag = new byte[] { 0x05, 0x00 };
        byte[] algIdSeq = buildAsn1Sequence(concatBytes(rsaOid, nullTag));
        byte[] octetString = buildAsn1OctetString(pkcs1Bytes);
        byte[] version = new byte[] { 0x02, 0x01, 0x00 };
        byte[] pkcs8Content = concatBytes(version, concatBytes(algIdSeq, octetString));
        return buildAsn1Sequence(pkcs8Content);
    }

    // Parse SEC1 EC private key format
    private java.security.PrivateKey parseEc1PrivateKey(byte[] sec1Bytes) throws Exception {
        byte[] curveOid = extractCurveOidFromSec1(sec1Bytes);
        if (curveOid == null) {
            curveOid = new byte[] { 0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07 };
        }
        byte[] ecOid = new byte[] { 0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01 };
        byte[] algIdContent = concatBytes(ecOid, curveOid);
        byte[] algIdSeq = buildAsn1Sequence(algIdContent);
        byte[] octetString = buildAsn1OctetString(sec1Bytes);
        byte[] version = new byte[] { 0x02, 0x01, 0x00 };
        byte[] pkcs8Content = concatBytes(version, concatBytes(algIdSeq, octetString));
        byte[] pkcs8Bytes = buildAsn1Sequence(pkcs8Content);
        java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(pkcs8Bytes);
        return java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec);
    }

    // Extract curve OID from SEC1 structure
    private byte[] extractCurveOidFromSec1(byte[] sec1Bytes) {
        int pos = 0;
        if (sec1Bytes[pos] != 0x30)
            return null;
        pos++;
        pos += lengthOfLength(sec1Bytes, pos);
        if (sec1Bytes[pos] != 0x02)
            return null;
        pos++;
        int verLen = sec1Bytes[pos] & 0xFF;
        pos += 1 + verLen;
        if (sec1Bytes[pos] != 0x04)
            return null;
        pos++;
        int keyLen = readAsn1Length(sec1Bytes, pos);
        pos += lengthOfLength(sec1Bytes, pos) + keyLen;
        if (pos < sec1Bytes.length && (sec1Bytes[pos] & 0xFF) == 0xA0) {
            pos++;
            int paramLen = readAsn1Length(sec1Bytes, pos);
            pos += lengthOfLength(sec1Bytes, pos);
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

}
