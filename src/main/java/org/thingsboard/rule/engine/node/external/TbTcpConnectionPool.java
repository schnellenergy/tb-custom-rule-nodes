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

import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLSocketFactory;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TbTcpConnectionPool {

    private final int maxConnections;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean keepAlive;

    private final ConcurrentMap<String, BlockingDeque<TbTcpClient>> idleClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public TbTcpConnectionPool(int maxConnections, int connectTimeoutMs, int readTimeoutMs, boolean keepAlive) {
        this.maxConnections = maxConnections;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.keepAlive = keepAlive;
    }

    private String getKey(String host, int port) {
        return host + ":" + port;
    }

    public TbTcpClient borrowClient(String host, int port, boolean tls, SSLSocketFactory sslFactory) throws Exception {
        String key = getKey(host, port);
        Semaphore sem = semaphores.computeIfAbsent(key, k -> new Semaphore(maxConnections));
        
        if (!sem.tryAcquire(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Connection pool exhausted for " + key + " after waiting " + connectTimeoutMs + "ms");
        }

        BlockingDeque<TbTcpClient> queue = idleClients.computeIfAbsent(key, k -> new LinkedBlockingDeque<>());
        TbTcpClient client = queue.poll();

        if (client != null && !client.isValid()) {
            client.close();
            client = null;
        }

        if (client == null) {
            try {
                client = TbSendToTcpNode.clientFactory.create(host, port, tls, sslFactory, connectTimeoutMs, readTimeoutMs, keepAlive);
                client.connect();
            } catch (Exception e) {
                sem.release();
                throw e;
            }
        }
        return client;
    }

    public void returnClient(TbTcpClient client, String host, int port) {
        if (client == null) {
            return;
        }
        String key = getKey(host, port);
        BlockingDeque<TbTcpClient> queue = idleClients.get(key);
        
        if (queue != null && client.isValid()) {
            queue.offerFirst(client);
        } else {
            client.close();
        }
        
        Semaphore sem = semaphores.get(key);
        if (sem != null) {
            sem.release();
        }
    }

    public void discardClient(TbTcpClient client, String host, int port) {
        if (client == null) {
            return;
        }
        client.close();
        Semaphore sem = semaphores.get(getKey(host, port));
        if (sem != null) {
            sem.release();
        }
    }

    public void closeAll() {
        idleClients.values().forEach(queue -> {
            TbTcpClient client;
            while ((client = queue.poll()) != null) {
                client.close();
            }
        });
        idleClients.clear();
        semaphores.clear();
    }
}
