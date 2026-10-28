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

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

@Data
public class TbSendToTcpNodeConfiguration implements NodeConfiguration<TbSendToTcpNodeConfiguration> {
    /**
     * Templatized key for the target host (e.g., "${tcpHost}" or "$[tcpHost]").
     */
    private String hostKey;
    /**
     * Templatized key for the target port (e.g., "${tcpPort}" or "$[tcpPort]").
     */
    private String portKey;
    private int connectTimeout;
    private int readTimeout;
    private int maxConnections = 10;
    private boolean keepAlive = true;
    /**
     * Enable TLS
     */
    private boolean tls;

    /**
     * Payload type: STRING, JSON, BINARY
     */
    private String payloadType; // STRING, JSON, BINARY
    /**
     * Response type: STRING, JSON, BINARY
     */
    private String responseType; // STRING, JSON, BINARY

    /**
     * Framing type: NONE, DELIMITER, LENGTH_PREFIX
     */
    private String framingType;

    /**
     * Delimiter string (e.g. \n or \r\n)
     */
    private String delimiter;

    /**
     * Netty-style length-prefix settings
     */
    private int maxFrameLength;
    private int lengthFieldOffset;
    private int lengthFieldLength;
    private int lengthAdjustment;
    private int initialBytesToStrip;
    private String lengthByteOrder;

    /**
     * Fragment smart timeout settings
     */
    private int fragmentTimeoutMs;

    /**
     * Maximum time (ms) a socket may sit idle in the pool before being evicted.
     * Defaults to 120 000 ms (2 min) to stay below the SEMC device-side
     * teardown window. Set to 0 to disable idle eviction.
     */
    private int maxIdleMs = 120_000;

    /**
     * Templatized metadata key used to read the command correlation identifier.
     * The value is included in both success and failure outbound message metadata
     * under the key {@code cmdId}.
     * Default: {@code "${cmdId}"}.
     */
    private String cmdIdKey = "${cmdId}";

    /**
     * Templatized metadata key used to read the overall RPC budget in
     * milliseconds. When present, {@code connectTimeout} and {@code readTimeout}
     * are derived from this value (20 % / 75 % split) instead of their static
     * configured values. This enforces the nesting constraint:
     * {@code connectTimeout + readTimeout < rpcTimeoutMs}.
     * Default: {@code "${rpcTimeoutMs}"}.
     */
    private String rpcTimeoutKey = "${rpcTimeoutMs}";

    /**
     * Consecutive CONNECT-phase failures (to the same host:port) required before this
     * node flags a message with alarm_action=RAISE. Cleared (alarm_action=CLEAR) on the
     * next fully successful request. Default matches the "N configurable, default 3"
     * requirement from the SEMC connection-refused ticket (#4433).
     */
    private int alarmThreshold = 3;

    /**
     * TLS configuration (PEM or Keystore/Truststore, SNI, ALPN, etc).
     */
    private TlsConfig tlsConfig = new TlsConfig();

    /**
     * TLS configuration for PEM or Keystore/Truststore, SNI, ALPN, and verification
     * options.
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
         * Whether to verify the server certificate (default: true). If false, disables
         * validation (INSECURE!).
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
        config.setPayloadType("TEXT");
        config.setResponseType("TEXT");
        config.setConnectTimeout(1000);
        config.setReadTimeout(5000);
        config.setMaxConnections(10);
        config.setKeepAlive(true);
        config.setTls(false);
        config.setTlsConfig(new TlsConfig());
        config.setFramingType("NONE");
        config.setDelimiter("\\n");
        config.setMaxFrameLength(1048576);
        config.setLengthFieldOffset(0);
        config.setLengthFieldLength(4);
        config.setLengthAdjustment(0);
        config.setInitialBytesToStrip(0);
        config.setLengthByteOrder("BIG_ENDIAN");
        config.setFragmentTimeoutMs(50);
        config.setMaxIdleMs(120_000);
        config.setCmdIdKey("${cmdId}");
        config.setRpcTimeoutKey("${rpcTimeoutMs}");
        config.setAlarmThreshold(3);
        return config;
    }
}
