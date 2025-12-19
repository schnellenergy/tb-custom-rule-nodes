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
    /**
     * TCP connect timeout (ms)
     */
    private int connectTimeout;
    /**
     * TCP read timeout (ms)
     */
    private int readTimeout;
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
        config.setTls(false);
        config.setTlsConfig(new TlsConfig());
        return config;
    }
}
