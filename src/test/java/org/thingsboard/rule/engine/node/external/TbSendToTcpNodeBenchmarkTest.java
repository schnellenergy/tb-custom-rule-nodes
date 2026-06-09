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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Benchmark test suite for TbSendToTcpNode concurrency and reliability.
 * 
 * Spins up a local TCP echo server and fires concurrent onMsg() calls
 * to measure throughput, latency distribution, error rates, and thread safety.
 * 
 * Run with: mvn test -Dtest=TbSendToTcpNodeBenchmarkTest -pl .
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TbSendToTcpNodeBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ECHO_SERVER_PORT = 19876;

    // Echo server infrastructure
    private static ServerSocket echoServer;
    private static Thread echoServerThread;
    private static volatile boolean serverRunning;

    // Node under test
    private TbSendToTcpNode node;

    // ── Embedded TCP Echo Server ──────────────────────────────────────────

    @BeforeAll
    static void startEchoServer() throws IOException {
        echoServer = new ServerSocket(ECHO_SERVER_PORT);
        echoServer.setReuseAddress(true);
        serverRunning = true;

        echoServerThread = new Thread(() -> {
            while (serverRunning) {
                try {
                    Socket client = echoServer.accept();
                    // Handle each connection in its own thread
                    Thread handler = new Thread(() -> handleClient(client));
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (serverRunning) {
                        System.err.println("[EchoServer] Accept error: " + e.getMessage());
                    }
                }
            }
        }, "echo-server-acceptor");
        echoServerThread.setDaemon(true);
        echoServerThread.start();
        System.out.println("[EchoServer] Listening on port " + ECHO_SERVER_PORT);
    }

    private static void handleClient(Socket client) {
        try (client) {
            client.setSoTimeout(5000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] buf = new byte[4096];
            int len;
            try {
                while ((len = in.read(buf)) != -1) {
                    // Echo back wrapped in JSON: {"response":"<data>"}
                    String received = new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);
                    String response = "{\"response\":\"" + received + "\"}";
                    out.write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                    // Keep looping to serve subsequent requests on this connection
                }
            } catch (java.net.SocketTimeoutException ignored) {
            }
        } catch (IOException e) {
            // Client disconnected
        }
    }

    @AfterAll
    static void stopEchoServer() throws IOException {
        serverRunning = false;
        if (echoServer != null && !echoServer.isClosed()) {
            echoServer.close();
        }
    }

    // ── Test Setup ────────────────────────────────────────────────────────

    @BeforeEach
    void initNode() throws Exception {
        node = new TbSendToTcpNode();
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTls(false);
        config.setPayloadType("TEXT");
        config.setResponseType("TEXT");
        config.setConnectTimeout(2000);
        config.setReadTimeout(3000);
        JsonNode json = MAPPER.valueToTree(config);
        TbNodeConfiguration tbNodeConfig = new TbNodeConfiguration(json);
        TbContext ctx = mock(TbContext.class);
        org.thingsboard.common.util.ListeningExecutor mockExecutor = mock(org.thingsboard.common.util.ListeningExecutor.class);
        doAnswer((org.mockito.stubbing.Answer<Void>) inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(mockExecutor).execute(any(Runnable.class));
        when(ctx.getExternalCallExecutor()).thenReturn(mockExecutor);
        node.init(ctx, tbNodeConfig);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Result collector for concurrent runs */
    static class BenchmarkResult {
        final int concurrency;
        final int totalRequests;
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final List<Long> latenciesNs = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentHashMap<String, AtomicInteger> errorTypes = new ConcurrentHashMap<>();
        long wallClockMs;

        BenchmarkResult(int concurrency, int totalRequests) {
            this.concurrency = concurrency;
            this.totalRequests = totalRequests;
        }

        void recordSuccess(long latencyNs) {
            successes.incrementAndGet();
            latenciesNs.add(latencyNs);
        }

        void recordFailure(String errorType) {
            failures.incrementAndGet();
            errorTypes.computeIfAbsent(errorType, k -> new AtomicInteger()).incrementAndGet();
        }

        void printReport(String testName) {
            System.out.println("\n╔══════════════════════════════════════════════════════════╗");
            System.out.printf("║  BENCHMARK: %-44s ║%n", testName);
            System.out.println("╠══════════════════════════════════════════════════════════╣");
            System.out.printf("║  Concurrency:     %-38d ║%n", concurrency);
            System.out.printf("║  Total Requests:  %-38d ║%n", totalRequests);
            System.out.printf("║  Successes:       %-38d ║%n", successes.get());
            System.out.printf("║  Failures:        %-38d ║%n", failures.get());
            System.out.printf("║  Error Rate:      %-38s ║%n",
                    String.format("%.2f%%", (failures.get() * 100.0) / totalRequests));
            System.out.printf("║  Wall Clock:      %-38s ║%n", wallClockMs + " ms");

            if (!latenciesNs.isEmpty()) {
                List<Long> sorted = new ArrayList<>(latenciesNs);
                Collections.sort(sorted);
                double avgMs = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
                double p50Ms = sorted.get(sorted.size() / 2) / 1_000_000.0;
                double p95Ms = sorted.get((int) (sorted.size() * 0.95)) / 1_000_000.0;
                double p99Ms = sorted.get((int) (sorted.size() * 0.99)) / 1_000_000.0;
                double maxMs = sorted.get(sorted.size() - 1) / 1_000_000.0;
                double throughput = successes.get() * 1000.0 / Math.max(wallClockMs, 1);

                System.out.println("╠──────────────────────────────────────────────────────────╣");
                System.out.printf("║  Throughput:      %-38s ║%n", String.format("%.1f req/s", throughput));
                System.out.printf("║  Avg Latency:     %-38s ║%n", String.format("%.2f ms", avgMs));
                System.out.printf("║  P50 Latency:     %-38s ║%n", String.format("%.2f ms", p50Ms));
                System.out.printf("║  P95 Latency:     %-38s ║%n", String.format("%.2f ms", p95Ms));
                System.out.printf("║  P99 Latency:     %-38s ║%n", String.format("%.2f ms", p99Ms));
                System.out.printf("║  Max Latency:     %-38s ║%n", String.format("%.2f ms", maxMs));
            }

            if (!errorTypes.isEmpty()) {
                System.out.println("╠──────────────────────────────────────────────────────────╣");
                System.out.printf("║  %-56s ║%n", "Error Breakdown:");
                errorTypes.forEach((type, count) ->
                        System.out.printf("║    %-40s %10d   ║%n", type, count.get()));
            }
            System.out.println("╚══════════════════════════════════════════════════════════╝");
        }
    }

    /**
     * Runs N concurrent onMsg() calls using a real echo server connection.
     */
    private BenchmarkResult runConcurrentBenchmark(int concurrency, int totalRequests) throws InterruptedException {
        BenchmarkResult result = new BenchmarkResult(concurrency, totalRequests);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);  // all threads start together
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        // Restore real client factory (no mocks - real TCP connections)
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        TbSendToTcpNode.clientFactory = (host, port, tls, sslFactory, ct, rt, ka) ->
                new TbTcpClient(host, port, tls, sslFactory, ct, rt, ka);

        try {
            for (int i = 0; i < totalRequests; i++) {
                final int msgId = i;
                executor.submit(() -> {
                    try {
                        startGate.await(); // wait for all threads to be ready
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    TbMsgMetaData meta = new TbMsgMetaData();
                    meta.putValue("tcpHost", "127.0.0.1");
                    meta.putValue("tcpPort", String.valueOf(ECHO_SERVER_PORT));

                    String payload = "benchmark-msg-" + msgId;
                    TbMsg msg = TbMsg.newMsg()
                            .data("{\"payload\":\"" + payload + "\"}")
                            .metaData(meta)
                            .originator(new DeviceId(UUID.randomUUID()))
                            .build();

                    // Track success/failure via a capturing TbContext mock
                    TbContext ctx = mock(TbContext.class);
                    org.thingsboard.common.util.ListeningExecutor mockExecutor = mock(org.thingsboard.common.util.ListeningExecutor.class);
                    doAnswer((org.mockito.stubbing.Answer<Void>) inv -> {
                        ((Runnable) inv.getArgument(0)).run();
                        return null;
                    }).when(mockExecutor).execute(any(Runnable.class));
                    when(ctx.getExternalCallExecutor()).thenReturn(mockExecutor);
                    AtomicInteger localSuccess = new AtomicInteger();
                    AtomicInteger localFailure = new AtomicInteger();
                    final String[] errorMsg = {null};

                    org.mockito.stubbing.Answer<Void> successAnswer = inv -> {
                        localSuccess.incrementAndGet();
                        return null;
                    };
                    org.mockito.stubbing.Answer<Void> failureAnswer = inv -> {
                        localFailure.incrementAndGet();
                        Throwable t = inv.getArgument(1);
                        errorMsg[0] = t.getClass().getSimpleName();
                        return null;
                    };

                    org.mockito.Mockito.doAnswer(successAnswer).when(ctx).tellSuccess(any());
                    org.mockito.Mockito.doAnswer(failureAnswer).when(ctx).tellFailure(any(), any());

                    long start = System.nanoTime();
                    try {
                        node.onMsg(ctx, msg);
                        long elapsed = System.nanoTime() - start;

                        if (localSuccess.get() > 0) {
                            result.recordSuccess(elapsed);
                        } else {
                            result.recordFailure(errorMsg[0] != null ? errorMsg[0] : "UnknownFailure");
                        }
                    } catch (Exception e) {
                        result.recordFailure(e.getClass().getSimpleName());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            long wallStart = System.currentTimeMillis();
            startGate.countDown(); // fire!
            doneLatch.await(120, TimeUnit.SECONDS);
            result.wallClockMs = System.currentTimeMillis() - wallStart;

        } finally {
            executor.shutdownNow();
            TbSendToTcpNode.clientFactory = originalFactory;
        }

        return result;
    }

    // ── Benchmark Tests ──────────────────────────────────────────────────

    @Test
    @Order(1)
    void benchmark_singleThread_baseline() throws Exception {
        BenchmarkResult r = runConcurrentBenchmark(1, 50);
        r.printReport("Single Thread Baseline (50 msgs)");
    }

    @Test
    @Order(2)
    void benchmark_10_concurrent() throws Exception {
        BenchmarkResult r = runConcurrentBenchmark(10, 100);
        r.printReport("10 Concurrent Threads (100 msgs)");
    }

    @Test
    @Order(3)
    void benchmark_50_concurrent() throws Exception {
        BenchmarkResult r = runConcurrentBenchmark(50, 200);
        r.printReport("50 Concurrent Threads (200 msgs)");
    }

    @Test
    @Order(4)
    void benchmark_100_concurrent() throws Exception {
        BenchmarkResult r = runConcurrentBenchmark(100, 500);
        r.printReport("100 Concurrent Threads (500 msgs)");
    }

    @Test
    @Order(5)
    void benchmark_200_concurrent_stress() throws Exception {
        BenchmarkResult r = runConcurrentBenchmark(200, 1000);
        r.printReport("200 Concurrent Threads (1000 msgs)");
    }

    // ── Reliability Tests ────────────────────────────────────────────────

    @Test
    @Order(6)
    void reliability_interleaved_failures() throws Exception {
        /**
         * Tests mixed success/failure scenarios: even-numbered messages go to
         * the real echo server, odd-numbered go to a dead port.
         * Validates that failures don't corrupt state for subsequent successes.
         */
        int totalRequests = 100;
        int concurrency = 20;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        TbSendToTcpNode.clientFactory = (host, port, tls, sslFactory, ct, rt, ka) ->
                new TbTcpClient(host, port, tls, sslFactory, ct, rt, ka);

        try {
            for (int i = 0; i < totalRequests; i++) {
                final int msgId = i;
                executor.submit(() -> {
                    try { startGate.await(); } catch (InterruptedException e) { return; }

                    TbMsgMetaData meta = new TbMsgMetaData();
                    meta.putValue("tcpHost", "127.0.0.1");
                    // Even → echo server, Odd → dead port (fast connect-refuse)
                    meta.putValue("tcpPort", msgId % 2 == 0
                            ? String.valueOf(ECHO_SERVER_PORT) : "19999");

                    TbMsg msg = TbMsg.newMsg()
                            .data("{\"payload\":\"reliability-" + msgId + "\"}")
                            .metaData(meta)
                            .originator(new DeviceId(UUID.randomUUID()))
                            .build();

                    TbContext ctx = mock(TbContext.class);
                    org.thingsboard.common.util.ListeningExecutor mockExecutor = mock(org.thingsboard.common.util.ListeningExecutor.class);
                    doAnswer((org.mockito.stubbing.Answer<Void>) inv -> {
                        ((Runnable) inv.getArgument(0)).run();
                        return null;
                    }).when(mockExecutor).execute(any(Runnable.class));
                    when(ctx.getExternalCallExecutor()).thenReturn(mockExecutor);
                    org.mockito.Mockito.doAnswer(inv -> { successes.incrementAndGet(); return null; })
                            .when(ctx).tellSuccess(any());
                    org.mockito.Mockito.doAnswer(inv -> { failures.incrementAndGet(); return null; })
                            .when(ctx).tellFailure(any(), any());

                    try {
                        node.onMsg(ctx, msg);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            TbSendToTcpNode.clientFactory = originalFactory;
        }

        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  RELIABILITY: Interleaved Failures                       ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf("║  Total:     %-44d ║%n", totalRequests);
        System.out.printf("║  Successes: %-44d ║%n", successes.get());
        System.out.printf("║  Failures:  %-44d ║%n", failures.get());
        System.out.printf("║  Expected successes (even msgs): %-23d ║%n", totalRequests / 2);
        System.out.printf("║  Expected failures  (odd msgs):  %-23d ║%n", totalRequests / 2);
        System.out.printf("║  Success match: %-40s ║%n",
                successes.get() == totalRequests / 2 ? "✓ PASS" : "✗ MISMATCH");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    @Test
    @Order(7)
    void reliability_sustained_load() throws Exception {
        /**
         * Sends messages in 5 waves of 50 messages each with 20 threads.
         * Checks that error rate stays stable across waves (no resource leaks).
         */
        int waves = 5;
        int msgsPerWave = 50;
        int concurrency = 20;

        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  RELIABILITY: Sustained Load (5 waves × 50 msgs)         ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf("║  %-8s  %-10s  %-10s  %-10s  %-10s  ║%n",
                "Wave", "Success", "Failure", "Rate", "Time(ms)");
        System.out.println("╠──────────────────────────────────────────────────────────╣");

        for (int wave = 1; wave <= waves; wave++) {
            BenchmarkResult r = runConcurrentBenchmark(concurrency, msgsPerWave);
            System.out.printf("║  %-8d  %-10d  %-10d  %-10s  %-10d  ║%n",
                    wave, r.successes.get(), r.failures.get(),
                    String.format("%.1f%%", r.failures.get() * 100.0 / msgsPerWave),
                    r.wallClockMs);
        }
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    @Test
    @Order(8)
    void reliability_thread_safety_shared_node() throws Exception {
        /**
         * All threads share the SAME TbSendToTcpNode instance (as in production).
         * Verifies no ConcurrentModificationException or corrupted state.
         */
        int concurrency = 50;
        int totalRequests = 200;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger exceptions = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        TbSendToTcpNode.clientFactory = (host, port, tls, sslFactory, ct, rt, ka) ->
                new TbTcpClient(host, port, tls, sslFactory, ct, rt, ka);

        try {
            for (int i = 0; i < totalRequests; i++) {
                final int msgId = i;
                executor.submit(() -> {
                    try { startGate.await(); } catch (InterruptedException e) { return; }

                    TbMsgMetaData meta = new TbMsgMetaData();
                    meta.putValue("tcpHost", "127.0.0.1");
                    meta.putValue("tcpPort", String.valueOf(ECHO_SERVER_PORT));

                    TbMsg msg = TbMsg.newMsg()
                            .data("{\"payload\":\"thread-safety-" + msgId + "\"}")
                            .metaData(meta)
                            .originator(new DeviceId(UUID.randomUUID()))
                            .build();

                    TbContext ctx = mock(TbContext.class);
                    org.thingsboard.common.util.ListeningExecutor mockExecutor = mock(org.thingsboard.common.util.ListeningExecutor.class);
                    doAnswer((org.mockito.stubbing.Answer<Void>) inv -> {
                        ((Runnable) inv.getArgument(0)).run();
                        return null;
                    }).when(mockExecutor).execute(any(Runnable.class));
                    when(ctx.getExternalCallExecutor()).thenReturn(mockExecutor);
                    org.mockito.Mockito.doAnswer(inv -> { successes.incrementAndGet(); return null; })
                            .when(ctx).tellSuccess(any());
                    org.mockito.Mockito.doAnswer(inv -> { failures.incrementAndGet(); return null; })
                            .when(ctx).tellFailure(any(), any());

                    try {
                        node.onMsg(ctx, msg);
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                        System.err.println("[ThreadSafety] Unexpected exception: " + e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            TbSendToTcpNode.clientFactory = originalFactory;
        }

        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  RELIABILITY: Thread Safety (shared node instance)        ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf("║  Concurrency:       %-36d ║%n", concurrency);
        System.out.printf("║  Total Requests:    %-36d ║%n", totalRequests);
        System.out.printf("║  Successes:         %-36d ║%n", successes.get());
        System.out.printf("║  Failures (ctx):    %-36d ║%n", failures.get());
        System.out.printf("║  Uncaught Exceptions: %-34d ║%n", exceptions.get());
        System.out.printf("║  Thread Safety:     %-36s ║%n",
                exceptions.get() == 0 ? "✓ PASS (no uncaught exceptions)" : "✗ FAIL");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    // ── Destroy-while-in-flight Test ─────────────────────────────────────────

    @Test
    @Order(9)
    void reliability_destroy_while_messages_in_flight() throws Exception {
        /**
         * Calls node.destroy() while messages are actively being processed.
         * Verifies that no uncaught NullPointerException escapes — every outcome
         * must be routed to tellSuccess or tellFailure, never thrown raw.
         *
         * Relies on pool being declared volatile so destroy()'s write is
         * immediately visible to onMsg() threads.
         */
        int concurrency = 20;
        int totalRequests = 200;
        AtomicInteger uncaughtExceptions = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        TbSendToTcpNode.clientFactory = (host, port, tls, sslFactory, ct, rt, ka) ->
                new TbTcpClient(host, port, tls, sslFactory, ct, rt, ka);

        try {
            for (int i = 0; i < totalRequests; i++) {
                final int msgId = i;
                executor.submit(() -> {
                    try { startGate.await(); } catch (InterruptedException e) { return; }

                    TbMsgMetaData meta = new TbMsgMetaData();
                    meta.putValue("tcpHost", "127.0.0.1");
                    meta.putValue("tcpPort", String.valueOf(ECHO_SERVER_PORT));

                    TbMsg msg = TbMsg.newMsg()
                            .data("{\"payload\":\"destroy-test-" + msgId + "\"}")
                            .metaData(meta)
                            .originator(new DeviceId(UUID.randomUUID()))
                            .build();

                    TbContext ctx = mock(TbContext.class);
                    org.thingsboard.common.util.ListeningExecutor mockExecutor =
                            mock(org.thingsboard.common.util.ListeningExecutor.class);
                    doAnswer((org.mockito.stubbing.Answer<Void>) inv -> {
                        ((Runnable) inv.getArgument(0)).run();
                        return null;
                    }).when(mockExecutor).execute(any(Runnable.class));
                    when(ctx.getExternalCallExecutor()).thenReturn(mockExecutor);
                    doAnswer(inv -> { successes.incrementAndGet(); return null; }).when(ctx).tellSuccess(any());
                    doAnswer(inv -> { failures.incrementAndGet(); return null; }).when(ctx).tellFailure(any(), any());

                    try {
                        node.onMsg(ctx, msg);
                    } catch (Exception e) {
                        uncaughtExceptions.incrementAndGet();
                        System.err.println("[DestroyTest] Uncaught exception: " + e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            Thread.sleep(2); // let a few messages enter the pool before destroy fires
            node.destroy();

            doneLatch.await(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            TbSendToTcpNode.clientFactory = originalFactory;
        }

        int routed = successes.get() + failures.get();
        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  RELIABILITY: destroy() while messages in-flight          ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf("║  Total Requests:    %-36d ║%n", totalRequests);
        System.out.printf("║  Routed (S+F):      %-36d ║%n", routed);
        System.out.printf("║  Successes:         %-36d ║%n", successes.get());
        System.out.printf("║  Failures (ctx):    %-36d ║%n", failures.get());
        System.out.printf("║  Uncaught Exceptions: %-34d ║%n", uncaughtExceptions.get());
        System.out.printf("║  All messages routed: %-34s ║%n", routed == totalRequests ? "✓ PASS" : "✗ FAIL");
        System.out.printf("║  No raw exceptions:   %-34s ║%n",
                uncaughtExceptions.get() == 0 ? "✓ PASS" : "✗ FAIL");
        System.out.println("╚══════════════════════════════════════════════════════════╝");

        org.junit.jupiter.api.Assertions.assertEquals(0, uncaughtExceptions.get(),
                "destroy() during in-flight messages must not throw uncaught exceptions");
        org.junit.jupiter.api.Assertions.assertEquals(totalRequests, routed,
                "Every message must be routed to tellSuccess or tellFailure");
    }
}
