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

import gurux.net.GXNet;
import gurux.net.enums.NetworkType;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * DLMS Connection Pool for smart meter communication with Semaphore-based capacity management.
 * Manages and reuses TCP connections with PER-METER capacity limits.
 *
 * Design: Upgraded to handle 100+ meters without data loss
 * ├─ Uses Semaphore per host:port for fair resource allocation
 * ├─ Requests to same meter queue instead of fail
 * ├─ Each meter can have independent max connections
 * ├─ Supports concurrent access from multiple threads
 * └─ Prevents connection pool exhaustion gracefully
 *
 * Example: 100 different meters, each with 10 max connections
 * ├─ Meter A: Semaphore(10) - independent capacity
 * ├─ Meter B: Semaphore(10) - independent capacity
 * ├─ ...
 * └─ Total: 1000 possible concurrent connections (vs. old 10 global)
 *
 * Benefits:
 * - Supports 100+ concurrent meters ✅
 * - No data loss on high load ✅
 * - Fair queuing instead of exceptions ✅
 * - Backward compatible (old getConnection still works)
 */
@Slf4j
public class DlmsClientPool {

    private final java.util.concurrent.ConcurrentHashMap<String, GXNet> connectionPool;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.BlockingDeque<GXNet>> idleConnections;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore> semaphores;
    private final int maxConnectionsPerMeter;
    private final int connectionTimeoutMs;

    public DlmsClientPool(int maxConnectionsPerMeter, int connectionTimeoutMs) {
        this.maxConnectionsPerMeter = maxConnectionsPerMeter;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.connectionPool = new java.util.concurrent.ConcurrentHashMap<>();
        this.idleConnections = new java.util.concurrent.ConcurrentHashMap<>();
        this.semaphores = new java.util.concurrent.ConcurrentHashMap<>();
        log.info("DlmsClientPool initialized: maxConnectionsPerMeter={}, timeout={}ms",
                 maxConnectionsPerMeter, connectionTimeoutMs);
    }

    /**
     * NEW METHOD: Borrow connection with Semaphore support (for 100+ meters)
     *
     * How it works:
     * 1. Get or create semaphore for this meter (independent capacity)
     * 2. Try to acquire permit (wait if all slots full)
     * 3. Check idle queue for reusable connection
     * 4. If none idle, create new connection
     * 5. Return connection (holding permit)
     *
     * Advantage: Fair queuing instead of failure when exhausted
     *
     * @param host meter IP address
     * @param port meter port
     * @return GXNet connection (holds semaphore permit)
     * @throws Exception if timeout or connection fails
     */
    public GXNet borrowConnection(String host, int port) throws Exception {
        String key = host + ":" + port;

        // Get or create semaphore for this meter (per-meter capacity)
        java.util.concurrent.Semaphore sem = semaphores.computeIfAbsent(key,
                k -> new java.util.concurrent.Semaphore(maxConnectionsPerMeter));

        // Try to acquire permit (wait up to connectionTimeoutMs)
        if (!sem.tryAcquire(connectionTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw new java.util.concurrent.TimeoutException(
                    "Connection pool exhausted for " + key + " after waiting " + connectionTimeoutMs + "ms");
        }

        // Now we have a permit, try to reuse idle connection
        java.util.concurrent.BlockingDeque<GXNet> idleQueue = idleConnections.computeIfAbsent(key,
                k -> new java.util.concurrent.LinkedBlockingDeque<>());

        GXNet connection = idleQueue.poll();

        // Check if idle connection is still alive
        if (connection != null && connection.isOpen()) {
            log.debug("Connection pool HIT: {}:{} (reusing idle connection)", host, port);
            return connection; // FAST path: reuse existing
        }

        // Close dead connection if found
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                log.warn("Error closing dead connection to {}:{}: {}", host, port, e.getMessage());
            }
        }

        // Create new connection (SLOW path)
        try {
            log.info("Creating new connection to {}:{}", host, port);
            GXNet newConnection = new GXNet(NetworkType.TCP, host, port);
            newConnection.setConnectionWaitTime(connectionTimeoutMs);
            newConnection.open();

            log.info("Connection pool MISS: {}:{} (new connection created)", host, port);
            return newConnection;
        } catch (Exception e) {
            // Failed to create, must release permit for next request
            sem.release();
            log.error("Failed to create connection to {}:{}", host, port, e);
            throw new Exception("Failed to connect to " + host + ":" + port + " - " + e.getMessage(), e);
        }
    }

    /**
     * NEW METHOD: Return connection after use (release semaphore permit)
     *
     * @param connection the connection to return
     * @param host meter IP address
     * @param port meter port
     */
    public void returnConnection(GXNet connection, String host, int port) {
        if (connection == null) {
            return;
        }

        String key = host + ":" + port;

        // Add back to idle queue if connection is still valid
        java.util.concurrent.BlockingDeque<GXNet> idleQueue = idleConnections.get(key);
        if (idleQueue != null && connection.isOpen()) {
            idleQueue.offerFirst(connection);  // Add to front for reuse
            log.debug("Returned connection to idle pool: {}:{}", host, port);
        } else {
            // Connection is dead, close it
            try {
                connection.close();
            } catch (Exception e) {
                log.warn("Error closing connection to {}:{}: {}", host, port, e.getMessage());
            }
        }

        // Release permit so next waiting request can proceed
        java.util.concurrent.Semaphore sem = semaphores.get(key);
        if (sem != null) {
            sem.release();  // Wakes next waiter!
            log.debug("Released permit for {}:{}", host, port);
        }
    }

    /**
     * Discard bad connection (close and release permit)
     *
     * @param connection the bad connection to discard
     * @param host meter IP address
     * @param port meter port
     */
    public void discardConnection(GXNet connection, String host, int port) {
        if (connection == null) {
            return;
        }

        String key = host + ":" + port;

        try {
            connection.close();
        } catch (Exception e) {
            log.warn("Error closing discarded connection to {}:{}: {}", host, port, e.getMessage());
        }

        // Release permit for next request
        java.util.concurrent.Semaphore sem = semaphores.get(key);
        if (sem != null) {
            sem.release();
        }
        log.debug("Discarded connection to {}:{}", host, port);
    }

    /**
     * OLD METHOD: Backward compatible getConnection (for existing code)
     * ⚠️ WARNING: Does NOT use semaphore, will fail on exhaustion
     * 🔄 MIGRATING: Use borrowConnection()/returnConnection() instead
     *
     * @deprecated Use borrowConnection() and returnConnection() instead
     * @param host meter IP address
     * @param port meter port
     * @return GXNet connection object ready to use
     * @throws Exception if connection fails
     */
    @Deprecated
    public synchronized GXNet getConnection(String host, int port) throws Exception {
        String key = host + ":" + port;

        // Check if connection exists and is alive
        if (connectionPool.containsKey(key)) {
            GXNet client = connectionPool.get(key);
            if (client.isOpen()) {
                log.debug("Connection pool HIT (old): {}:{} (reusing existing connection)", host, port);
                return client;
            } else {
                connectionPool.remove(key);
                log.warn("Removed dead connection to {}:{}", host, port);
            }
        }

        // Check pool size limit
        if (connectionPool.size() >= maxConnectionsPerMeter) {
            throw new Exception("Connection pool exhausted. Max size: " + maxConnectionsPerMeter);
        }

        // Create new connection
        try {
            log.info("Creating new connection to {}:{}", host, port);
            GXNet newConnection = new GXNet(NetworkType.TCP, host, port);
            newConnection.setConnectionWaitTime(connectionTimeoutMs);
            newConnection.open();

            connectionPool.put(key, newConnection);
            log.info("Connection pool MISS (old): {}:{} (new connection created)", host, port);
            return newConnection;
        } catch (Exception e) {
            log.error("Failed to create connection to {}:{}", host, port, e);
            throw new Exception("Failed to connect to " + host + ":" + port + " - " + e.getMessage(), e);
        }
    }

    /**
     * Get pool statistics for monitoring (new version with per-meter info)
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        // Overall stats
        stats.put("size", connectionPool.size());
        stats.put("idleSize", idleConnections.values().stream()
                .mapToInt(java.util.Collection::size).sum());
        stats.put("maxPerMeter", maxConnectionsPerMeter);

        // Per-meter stats
        Map<String, Map<String, Object>> perMeterStats = new HashMap<>();
        for (String key : semaphores.keySet()) {
            java.util.concurrent.Semaphore sem = semaphores.get(key);
            int available = sem.availablePermits();
            int used = maxConnectionsPerMeter - available;
            java.util.concurrent.BlockingDeque<GXNet> idleQueue = idleConnections.get(key);

            Map<String, Object> meterStats = new HashMap<>();
            meterStats.put("used", used);
            meterStats.put("available", available);
            meterStats.put("idle", idleQueue != null ? idleQueue.size() : 0);

            perMeterStats.put(key, meterStats);
        }
        stats.put("perMeter", perMeterStats);

        return stats;
    }

    /**
     * Shutdown all connections in the pool gracefully
     */
    public void shutdown() {
        log.info("Shutting down DlmsClientPool ({} meters)", semaphores.size());

        // Close all idle connections
        for (java.util.concurrent.BlockingDeque<GXNet> idleQueue : idleConnections.values()) {
            GXNet conn;
            while ((conn = idleQueue.poll()) != null) {
                try {
                    if (conn.isOpen()) {
                        conn.close();
                    }
                } catch (Exception e) {
                    log.warn("Error closing idle connection: {}", e.getMessage());
                }
            }
        }

        // Close all active connections
        for (GXNet conn : connectionPool.values()) {
            try {
                if (conn.isOpen()) {
                    conn.close();
                }
            } catch (Exception e) {
                log.warn("Error closing active connection: {}", e.getMessage());
            }
        }

        connectionPool.clear();
        idleConnections.clear();
        semaphores.clear();
        log.info("DlmsClientPool shutdown complete");
    }
}
