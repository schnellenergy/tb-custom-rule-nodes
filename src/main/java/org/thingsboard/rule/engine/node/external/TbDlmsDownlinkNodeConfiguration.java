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

/**
 * DLMS Downlink Rule Node Configuration.
 *
 * Stores all settings for the embedded DLMS node:
 * - TCP host/port (template keys to extract from message)
 * - Thread pool size (concurrent meter connections)
 * - Connection pool size (TCP connection reuse)
 * - Timeouts (response, connection)
 * - Keep-alive settings
 *
 * Edited via ThingsBoard UI Rule Chain Designer.
 * Configuration is saved to rule node JSON in ThingsBoard database.
 */
@Data
public class TbDlmsDownlinkNodeConfiguration implements NodeConfiguration<TbDlmsDownlinkNodeConfiguration> {

    // ===== TCP Network Configuration =====
    /**
     * Template key for meter TCP host (e.g., "${tcpHost}" or "192.168.1.100").
     * Extracted from incoming message using this key.
     */
    private String hostKey;

    /**
     * Template key for meter TCP port (e.g., "${tcpPort}" or 4059).
     * Extracted from incoming message using this key.
     */
    private String portKey;

    /**
     * Enable TCP keep-alive for meter connections.
     * Default: true (maintains long-lived connections)
     */
    private boolean keepAlive = true;

    // ===== Thread Pool Configuration =====
    /**
     * Number of worker threads for concurrent meter requests.
     * Default: 8 threads
     *
     * Tuning:
     * - Increase for high throughput (> 1000 req/sec): 16-32 threads
     * - Decrease for low latency: 2-4 threads
     * - Monitor CPU usage: ~50-80% for optimal throughput
     */
    private int threadPoolSize = 8;

    // ===== Connection Pool Configuration =====
    /**
     * Maximum number of TCP connections to keep open.
     * Default: 10 connections
     *
     * Tuning:
     * - Each meter typically gets 1 connection
     * - Set to number of meters you'll communicate with
     * - Excess connections closed after idle timeout
     */
    private int maxConnections = 10;

    /**
     * TCP connection timeout in milliseconds.
     * Default: 5000ms
     *
     * Tuning:
     * - Slow networks: 10000ms (10 sec)
     * - Fast networks: 2000ms (2 sec)
     * - Should be less than meter response timeout
     */
    private int connectionTimeoutMs = 5000;

    // ===== Request Timeout Configuration =====
    /**
     * Meter response timeout in seconds.
     * Default: 30 seconds
     *
     * Tuning:
     * - Most meters respond in 1-10 seconds
     * - Slow meters or bad networks: 30-60 seconds
     * - Too long causes thread pool blocking
     */
    private int responseTimeoutSeconds = 30;

    /**
     * Total request timeout in seconds (includes connection + response).
     * Default: 35 seconds (5s connection + 30s response)
     *
     * Tuning:
     * - Should be connectionTimeoutMs + responseTimeoutSeconds
     * - Used by thread pool task timeout
     */
    private int totalTimeoutSeconds = 35;

    @Override
    public TbDlmsDownlinkNodeConfiguration defaultConfiguration() {
        TbDlmsDownlinkNodeConfiguration configuration = new TbDlmsDownlinkNodeConfiguration();
        configuration.setHostKey("${tcpHost}");
        configuration.setPortKey("${tcpPort}");
        configuration.setKeepAlive(true);
        configuration.setThreadPoolSize(8);
        configuration.setMaxConnections(10);
        configuration.setConnectionTimeoutMs(5000);
        configuration.setResponseTimeoutSeconds(30);
        configuration.setTotalTimeoutSeconds(35);
        return configuration;
    }
}
