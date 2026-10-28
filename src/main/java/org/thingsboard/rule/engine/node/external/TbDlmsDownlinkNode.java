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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.rule.engine.api.*;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;

import java.util.concurrent.*;

/**
 * DLMS Downlink Rule Node (Embedded).
 *
 * Embedded smart meter communication node running inside ThingsBoard.
 * Receives downlink commands from rule chain and executes them on smart meters.
 *
 * Architecture:
 * - RuleChain → TbDlmsDownlinkNode → DlmsExecutor (thread pool) → SmartMeter
 * - Non-blocking: Work submitted to pool, node returns immediately
 * - Callback: Response routed back to rule chain when meter responds
 *
 * Example Input Payload (from Rule Chain):
 * {
 *   "host": "192.168.1.1",
 *   "port": 4059,
 *   "action": "READ",
 *   "obis": "1.0.94.91.7.255",
 *   "classId": 3,
 *   "attribute": 2,
 *   "blockCipherKey": "D0D1D2D3D4D5D6D7D8D9DADBDCDDDEDFE",
 *   "authenticationKey": "000102030405060708090A0B0C0D0E0F",
 *   "systemTitle": "48455330000001",
 *   "timeout": 30
 * }
 *
 * Example Output Payload (on Success):
 * {
 *   "success": true,
 *   "responseCode": "0",
 *   "data": "1234567",
 *   "durationMs": 523
 * }
 */
@Slf4j
@RuleNode(
    type = ComponentType.EXTERNAL,
    name = "DLMS Request",
    configClazz = TbDlmsDownlinkNodeConfiguration.class,
    nodeDescription = "Sends downlink commands to smart meters via DLMS protocol",
    nodeDetails = "Executes DLMS operations (READ, WRITE, ACTION) with connection pooling",
    uiResources = {
        "static/rulenode/custom-nodes-config.js"
    },
    configDirective = "tbExternalNodeSendToDlmsConfig",
    icon = "settings_ethernet"
)
public class TbDlmsDownlinkNode implements TbNode {

    private TbDlmsDownlinkNodeConfiguration config;
    private DlmsClientPool connectionPool;
    private DlmsExecutor dlmsExecutor;
    private ExecutorService threadPool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        try {
            // Step 1: Load configuration from rule node
            this.config = TbNodeUtils.convert(configuration, TbDlmsDownlinkNodeConfiguration.class);
            log.info("TbDlmsDownlinkNode initialized: threadPoolSize={}, maxConnections={}",
                config.getThreadPoolSize(), config.getMaxConnections());

            // Step 2: Create connection pool (reuse TCP connections)
            this.connectionPool = new DlmsClientPool(
                config.getMaxConnections(),
                config.getConnectionTimeoutMs()
            );

            // Step 3: Create DLMS executor
            this.dlmsExecutor = new DlmsExecutor(connectionPool);

            // Step 4: Create thread pool for concurrent meter requests
            // Why 8 threads?
            // - Each thread blocks 30sec waiting for meter response
            // - 8 threads = 8 concurrent meters
            // - Typical: 80% hit rate on connection pool
            // - Throughput: ~240 req/min (assuming 5sec per request)
            this.threadPool = new ThreadPoolExecutor(
                config.getThreadPoolSize(),          // Core threads
                config.getThreadPoolSize() * 2,      // Max threads (can grow temporarily)
                60,                                   // Keep-alive time
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),     // Queue size
                new ThreadPoolExecutor.CallerRunsPolicy() // Reject policy: caller runs
            );

            log.info("Thread pool created: {} core threads", config.getThreadPoolSize());

        } catch (Exception e) {
            log.error("Failed to initialize TbDlmsDownlinkNode", e);
            throw new TbNodeException(e);
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) throws TbNodeException {
        try {
            // Step 1: Parse input message (from rule chain)
            DlmsRequest request = parseMessage(msg);

            // Step 2: Submit work to thread pool (non-blocking)
            // Node returns immediately, work happens asynchronously
            threadPool.submit(() -> {
                try {
                    // Step 3a: Execute DLMS operation (blocking, waits for meter response)
                    DlmsResponse response = dlmsExecutor.execute(request);

                    // Step 3b: Convert response to JSON
                    String responseJson = objectMapper.writeValueAsString(response);

                    // Step 3c: Create output message with response
                    TbMsg outMsg = msg.transform()
                        .data(responseJson)
                        .build();

                    // Step 3d: Route success result back to rule chain
                    ctx.tellSuccess(outMsg);

                    log.debug("DLMS Success: {} duration={}ms", request.getHost(), response.getDurationMs());

                } catch (Exception e) {
                    // Step 3d: Route error back to rule chain
                    log.error("DLMS execution error", e);
                    ctx.tellFailure(msg, e);
                }
            });

        } catch (Exception e) {
            // Parse error - tell failure immediately
            log.error("Failed to parse DLMS request", e);
            ctx.tellFailure(msg, e);
        }
    }

    @Override
    public void destroy() {
        try {
            // Shutdown thread pool gracefully
            if (threadPool != null) {
                threadPool.shutdown();
                if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
                log.info("Thread pool shutdown complete");
            }

            // Shutdown connection pool
            if (connectionPool != null) {
                connectionPool.shutdown();
                log.info("Connection pool shutdown complete");
            }

            log.info("TbDlmsDownlinkNode destroyed");
        } catch (Exception e) {
            log.error("Error during shutdown", e);
        }
    }

    /**
     * Parse input message into DlmsRequest.
     *
     * Expected message format:
     * - Type: Can be any type (DEVICE_ATTRIBUTES_UPDATED, etc.)
     * - Originator: Device entity (device ID)
     * - Payload: JSON with DLMS parameters
     *
     * Example payload:
     * {
     *   "host": "192.168.1.100",
     *   "port": 4059,
     *   "action": "READ",
     *   "obis": "1.0.94.91.7.255",
     *   "classId": 3,
     *   "attribute": 2,
     *   "password": "12345678",
     *   "timeout": 30
     * }
     *
     * @param msg TbMsg from rule chain
     * @return DlmsRequest object
     * @throws Exception if parsing fails
     */
    private DlmsRequest parseMessage(TbMsg msg) throws Exception {
        try {
            JsonNode jsonData = objectMapper.readTree(msg.getData());
            DlmsRequest request = objectMapper.treeToValue(jsonData, DlmsRequest.class);

            // Extract host from configured key (e.g., "${tcpHost}" → get msg.tcpHost)
            if ((request.getHost() == null || request.getHost().isEmpty()) && config.getHostKey() != null) {
                String hostFieldName = config.getHostKey().replaceAll("[\\$\\{\\}]", "");
                if (jsonData.has(hostFieldName)) {
                    request.setHost(jsonData.get(hostFieldName).asText());
                }
            }

            // Extract port from configured key (e.g., "${tcpPort}" → get msg.tcpPort)
            if ((request.getPort() <= 0) && config.getPortKey() != null) {
                String portFieldName = config.getPortKey().replaceAll("[\\$\\{\\}]", "");
                if (jsonData.has(portFieldName)) {
                    request.setPort(jsonData.get(portFieldName).asInt());
                }
            }

            // Validate required fields
            if (request.getHost() == null || request.getHost().isEmpty()) {
                throw new IllegalArgumentException("'host' is required (configure hostKey in node config)");
            }
            if (request.getPort() <= 0) {
                throw new IllegalArgumentException("'port' is required (configure portKey in node config)");
            }
            if (request.getAction() == null || request.getAction().isEmpty()) {
                throw new IllegalArgumentException("'action' is required");
            }

            // Set default timeout if not provided
            if (request.getTimeout() <= 0) {
                request.setTimeout(config.getResponseTimeoutSeconds());
            }

            log.debug("Parsed request: host={} port={} action={}", request.getHost(), request.getPort(), request.getAction());
            return request;

        } catch (Exception e) {
            throw new Exception("Failed to parse DLMS request from message: " + e.getMessage(), e);
        }
    }
}
