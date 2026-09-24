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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocketFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * Connection pool for {@link TbTcpClient} instances.
 *
 * <p>Changes for SmartLights_2.0 issues #4434 / #4435:
 * <ul>
 *   <li><strong>One-socket-per-target hard cap</strong> — the SEMC device accepts
 *       exactly one TCP connection at a time. When a second {@link #borrowClient}
 *       arrives for the same {@code host:port} while one is already in use, a
 *       {@link TbTcpException} with phase {@code POOL} and error-class {@code BUSY}
 *       is thrown immediately rather than blocking or dialling a second socket.</li>
 *   <li><strong>Max-idle eviction</strong> — sockets returned to the idle queue are
 *       timestamped. On the next borrow, sockets older than {@code maxIdleMs} are
 *       closed and discarded; a fresh connection is made instead. Default
 *       {@code maxIdleMs = 120 000} (2 min, below the SEMC teardown window).</li>
 *   <li><strong>Per-call timeouts</strong> — {@code connectTimeoutMs} and
 *       {@code readTimeoutMs} are passed per-call (derived from RPC metadata by the
 *       node) rather than stored as pool-wide configuration.</li>
 *   <li><strong>Structured logging</strong> — key pool events are logged with a
 *       {@code pool.*} marker for future Micrometer integration.</li>
 * </ul>
 */
@Slf4j
public class TbTcpConnectionPool {

    private final int maxConnections;
    private final boolean keepAlive;
    private final int maxIdleMs;

    /** Tracks whether a client is currently checked out for a given key. */
    private final ConcurrentMap<String, AtomicBoolean> inUseFlags = new ConcurrentHashMap<>();

    /** Idle (returned) clients, at most one per key. */
    private final ConcurrentMap<String, IdleEntry> idleClients = new ConcurrentHashMap<>();

    /** Wraps a client with the timestamp at which it was returned to the pool. */
    private static class IdleEntry {
        final TbTcpClient client;
        final long returnedAt;

        IdleEntry(TbTcpClient client) {
            this.client = client;
            this.returnedAt = System.currentTimeMillis();
        }
    }

    /**
     * @param maxConnections maximum number of connections per target (currently
     *                       only the value {@code 1} is meaningful for SEMC devices)
     * @param keepAlive      whether sockets should use TCP keep-alive
     * @param maxIdleMs      maximum idle time in milliseconds before a socket is
     *                       evicted from the pool; {@code 0} disables idle eviction
     */
    public TbTcpConnectionPool(int maxConnections, boolean keepAlive, int maxIdleMs) {
        this.maxConnections = maxConnections;
        this.keepAlive = keepAlive;
        this.maxIdleMs = maxIdleMs;
    }

    private String getKey(String host, int port) {
        return host + ":" + port;
    }

    /**
     * Borrows a client for the given target.
     *
     * <p>If the target already has a client checked out, a {@link TbTcpException}
     * with phase {@code POOL} / errorClass {@code BUSY} is thrown immediately —
     * no dial is attempted.
     *
     * <p>If an idle client exists but has been idle longer than {@code maxIdleMs},
     * it is closed and evicted; a fresh connection is made in its place.
     *
     * @param connectTimeoutMs TCP handshake budget (derived from RPC metadata)
     * @param readTimeoutMs    read budget (derived from RPC metadata)
     * @param cmdId            correlation ID for error enrichment (may be null)
     */
    public TbTcpClient borrowClient(String host, int port, boolean tls, SSLSocketFactory sslFactory,
            int connectTimeoutMs, int readTimeoutMs, String cmdId) throws TbTcpException {
        String key = getKey(host, port);

        // ── BUSY check: one socket per target ───────────────────────────────
        AtomicBoolean inUse = inUseFlags.computeIfAbsent(key, k -> new AtomicBoolean(false));
        if (!inUse.compareAndSet(false, true)) {
            log.warn("[pool.busy_rejected] target={} cmdId={}", key, cmdId);
            throw new TbTcpException("POOL", "BUSY",
                    "A downlink to " + key + " is already in progress. Concurrent access rejected.",
                    key, cmdId);
        }

        // ── Idle eviction ───────────────────────────────────────────────────
        IdleEntry entry = idleClients.remove(key);
        TbTcpClient client = null;

        if (entry != null) {
            long idleMs = System.currentTimeMillis() - entry.returnedAt;
            if (maxIdleMs > 0 && idleMs >= maxIdleMs) {
                log.info("[pool.evicted] target={} idleMs={}", key, idleMs);
                entry.client.close();
                entry = null;
            } else if (!entry.client.isValid()) {
                log.info("[pool.evicted] target={} reason=invalid", key);
                entry.client.close();
                entry = null;
            } else {
                client = entry.client;
                log.debug("[pool.reused] target={} idleMs={}", key, idleMs);
            }
        }

        // ── Connect if needed ───────────────────────────────────────────────
        if (client == null) {
            try {
                client = TbSendToTcpNode.clientFactory.create(host, port, tls, sslFactory,
                        connectTimeoutMs, readTimeoutMs, keepAlive);
                client.connect(connectTimeoutMs, readTimeoutMs);
                log.info("[pool.open] target={}", key);
            } catch (TbTcpException te) {
                inUse.set(false); // release the in-use flag on connect failure
                throw te;
            } catch (Exception e) {
                inUse.set(false);
                throw new TbTcpException("CONNECT", e.getClass().getSimpleName(),
                        "Failed to connect to " + key + ": " + e.getMessage(), key, cmdId, e);
            }
        }

        return client;
    }

    /**
     * Returns a healthy client to the idle pool so it can be reused.
     * Releases the in-use flag for the target.
     */
    public void returnClient(TbTcpClient client, String host, int port) {
        if (client == null) {
            return;
        }
        String key = getKey(host, port);

        if (client.isValid()) {
            idleClients.put(key, new IdleEntry(client));
            log.debug("[pool.idle] target={}", key);
        } else {
            client.close();
            log.info("[pool.closed] target={} reason=invalid_on_return", key);
        }

        AtomicBoolean inUse = inUseFlags.get(key);
        if (inUse != null) {
            inUse.set(false);
        }
    }

    /**
     * Closes a client that encountered an error and releases the in-use flag.
     */
    public void discardClient(TbTcpClient client, String host, int port) {
        if (client == null) {
            return;
        }
        String key = getKey(host, port);
        client.close();
        log.info("[pool.evicted] target={} reason=discard", key);

        AtomicBoolean inUse = inUseFlags.get(key);
        if (inUse != null) {
            inUse.set(false);
        }
    }

    /**
     * Closes all idle clients and resets pool state. Called during node destroy.
     */
    public void closeAll() {
        for (Map.Entry<String, IdleEntry> e : idleClients.entrySet()) {
            e.getValue().client.close();
        }
        idleClients.clear();
        inUseFlags.clear();
    }
}
