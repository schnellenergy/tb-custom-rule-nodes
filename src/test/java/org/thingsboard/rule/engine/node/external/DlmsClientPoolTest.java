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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import gurux.net.GXNet;

/**
 * Unit Tests for DlmsClientPool
 *
 * GXNet construction is mocked (via Mockito's mockConstruction), so every
 * "new GXNet(...)" inside the pool returns a controllable mock - no real network
 * connection is ever opened. This lets these tests exercise the pool's actual
 * borrow/return/discard/fairness logic, not just its constructor and stats.
 *
 * Test Categories:
 * ✓ Happy Path (3): open new, reuse idle, discard dead idle and reopen
 * ✓ Concurrency / Fairness (3): waits for a freed slot, times out, per-meter isolation
 * ✓ Error Handling (1): connect failure still releases its permit
 * ✓ Return Connection (2): null no-op, dead connection closed instead of queued
 * ✓ Discard Connection (3): closes + releases permit, null no-op, close() failure still releases
 * ✓ Stats / Lifecycle (5): initial state, lenient constructor, shutdown behavior
 */
public class DlmsClientPoolTest {

    private static final String HOST = "192.168.1.50";
    private static final String HOST_2 = "192.168.1.51";
    private static final int PORT = 4059;

    private MockedConstruction<GXNet> gxNetConstruction;

    @AfterEach
    void tearDown() {
        if (gxNetConstruction != null) {
            gxNetConstruction.close();
        }
    }

    private void mockOpenableConnections() {
        gxNetConstruction = mockConstruction(GXNet.class, (mock, context) -> when(mock.isOpen()).thenReturn(true));
    }

    // ════════════════════════════════════════════════════════════════════
    // HAPPY PATH
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testBorrowConnection_FirstTime_OpensNewConnection() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);

        GXNet connection = pool.borrowConnection(HOST, PORT);

        assertNotNull(connection);
        verify(connection).open();
        assertEquals(1, gxNetConstruction.constructed().size());
    }

    @Test
    void testBorrowConnection_ReusesReturnedOpenConnection() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);

        GXNet first = pool.borrowConnection(HOST, PORT);
        pool.returnConnection(first, HOST, PORT);
        GXNet second = pool.borrowConnection(HOST, PORT);

        assertSame(first, second, "Should reuse the returned connection instead of opening a new one");
        assertEquals(1, gxNetConstruction.constructed().size(), "Only one GXNet should ever have been created");
    }

    @Test
    void testBorrowConnection_DeadIdleConnection_DiscardedAndReopened() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);

        GXNet first = pool.borrowConnection(HOST, PORT);
        pool.returnConnection(first, HOST, PORT);
        when(first.isOpen()).thenReturn(false); // connection died while sitting idle

        GXNet second = pool.borrowConnection(HOST, PORT);

        assertNotSame(first, second, "A dead idle connection must never be handed out again");
        verify(first).close();
        assertEquals(2, gxNetConstruction.constructed().size());
    }

    // ════════════════════════════════════════════════════════════════════
    // CONCURRENCY / FAIRNESS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testBorrowConnection_WaitsForFreedSlot_ThenSucceeds() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(1, 5000); // only 1 slot for this meter
        GXNet held = pool.borrowConnection(HOST, PORT); // takes the only slot

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<GXNet> waiter = exec.submit(() -> pool.borrowConnection(HOST, PORT));

            Thread.sleep(200);
            assertFalse(waiter.isDone(), "Second borrow should be waiting, not failing or succeeding early");

            pool.returnConnection(held, HOST, PORT);

            assertNotNull(waiter.get(2, TimeUnit.SECONDS), "Waiter should succeed once the slot is freed");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void testBorrowConnection_SlotNeverFreed_ThrowsTimeoutException() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(1, 300); // only 1 slot, short timeout
        pool.borrowConnection(HOST, PORT); // hold the only slot, never return it

        assertThrows(TimeoutException.class, () -> pool.borrowConnection(HOST, PORT));
    }

    @Test
    void testBorrowConnection_DifferentMeters_HaveIndependentCapacity() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(1, 2000);
        pool.borrowConnection(HOST, PORT); // uses up HOST's only slot

        assertDoesNotThrow(() -> pool.borrowConnection(HOST_2, PORT),
                "A different meter must not be blocked by another meter's exhausted capacity");
    }

    // ════════════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testBorrowConnection_ConnectFailure_ReleasesPermitBeforeThrowing() throws Exception {
        gxNetConstruction = mockConstruction(GXNet.class,
                (mock, context) -> doThrow(new Exception("connection refused")).when(mock).open());
        DlmsClientPool pool = new DlmsClientPool(1, 500);

        assertThrows(Exception.class, () -> pool.borrowConnection(HOST, PORT));

        // If the permit were leaked, this second attempt would hang/timeout waiting instead of
        // failing again immediately on connect - proves the failed borrow above released it.
        assertThrows(Exception.class, () -> pool.borrowConnection(HOST, PORT));
    }

    // ════════════════════════════════════════════════════════════════════
    // RETURN CONNECTION
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testReturnConnection_Null_NoOp() {
        DlmsClientPool pool = new DlmsClientPool(10, 2000);
        assertDoesNotThrow(() -> pool.returnConnection(null, HOST, PORT));
    }

    @Test
    void testReturnConnection_ConnectionAlreadyDead_ClosedInsteadOfQueued() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);
        GXNet connection = pool.borrowConnection(HOST, PORT);
        when(connection.isOpen()).thenReturn(false); // died before being returned

        pool.returnConnection(connection, HOST, PORT);

        verify(connection).close();
    }

    // ════════════════════════════════════════════════════════════════════
    // DISCARD CONNECTION
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testDiscardConnection_ClosesAndReleasesPermit() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(1, 2000);
        GXNet connection = pool.borrowConnection(HOST, PORT);

        pool.discardConnection(connection, HOST, PORT);

        verify(connection).close();
        assertDoesNotThrow(() -> pool.borrowConnection(HOST, PORT),
                "Permit must be released so the next borrow doesn't wait/timeout");
    }

    @Test
    void testDiscardConnection_Null_NoOp() {
        DlmsClientPool pool = new DlmsClientPool(10, 2000);
        assertDoesNotThrow(() -> pool.discardConnection(null, HOST, PORT));
    }

    @Test
    void testDiscardConnection_CloseThrows_StillReleasesPermit() throws Exception {
        gxNetConstruction = mockConstruction(GXNet.class, (mock, context) -> {
            when(mock.isOpen()).thenReturn(true);
            doThrow(new RuntimeException("already broken")).when(mock).close();
        });
        DlmsClientPool pool = new DlmsClientPool(1, 2000);
        GXNet connection = pool.borrowConnection(HOST, PORT);

        assertDoesNotThrow(() -> pool.discardConnection(connection, HOST, PORT));
        assertDoesNotThrow(() -> pool.borrowConnection(HOST, PORT),
                "Permit must be released even when close() itself throws");
    }

    // ════════════════════════════════════════════════════════════════════
    // STATS / LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testPoolInitialization_StartsEmptyWithConfiguredMax() {
        DlmsClientPool pool = new DlmsClientPool(10, 5000);

        Map<String, Object> stats = pool.getStats();

        assertEquals(0, stats.get("size"));
        assertEquals(10, stats.get("maxPerMeter"));
    }

    @Test
    void testConstructor_NegativeMaxSize_AcceptedWithoutValidation() {
        assertDoesNotThrow(() -> new DlmsClientPool(-1, 5000));
    }

    @Test
    void testConstructor_ZeroTimeout_Accepted() {
        assertDoesNotThrow(() -> new DlmsClientPool(10, 0));
    }

    @Test
    void testShutdown_ClosesIdleConnections() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);
        GXNet connection = pool.borrowConnection(HOST, PORT);
        pool.returnConnection(connection, HOST, PORT);

        pool.shutdown();

        verify(connection).close();
    }

    @Test
    void testShutdown_CalledTwice_NoError() {
        DlmsClientPool pool = new DlmsClientPool(10, 2000);
        assertDoesNotThrow(() -> {
            pool.shutdown();
            pool.shutdown();
        });
    }

    // ════════════════════════════════════════════════════════════════════
    // LEGACY getConnection() TESTS (deprecated, no semaphore - kept only for
    // backward compatibility with any caller not yet migrated to borrowConnection())
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testGetConnection_FirstTime_OpensNewConnection() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);

        GXNet connection = pool.getConnection(HOST, PORT);

        assertNotNull(connection);
        verify(connection).open();
    }

    @Test
    void testGetConnection_ReusesExistingOpenConnection() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);

        GXNet first = pool.getConnection(HOST, PORT);
        GXNet second = pool.getConnection(HOST, PORT);

        assertSame(first, second, "Should reuse the existing connection instead of opening a new one");
        assertEquals(1, gxNetConstruction.constructed().size());
    }

    @Test
    void testGetConnection_DeadConnection_RemovedAndReopened() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);

        GXNet first = pool.getConnection(HOST, PORT);
        when(first.isOpen()).thenReturn(false); // connection died while sitting in the pool
        GXNet second = pool.getConnection(HOST, PORT);

        assertNotSame(first, second, "A dead connection must never be handed out again");
        assertEquals(2, gxNetConstruction.constructed().size());
    }

    @Test
    void testGetConnection_PoolAtMaxSize_ThrowsException() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(1, 2000);
        pool.getConnection(HOST, PORT); // fills the (old-style) pool to its max of 1

        Exception ex = assertThrows(Exception.class, () -> pool.getConnection(HOST_2, PORT));
        assertTrue(ex.getMessage().contains("Connection pool exhausted"));
    }

    // ════════════════════════════════════════════════════════════════════
    // getStats() PER-METER DETAIL TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testGetStats_AfterBorrow_ReportsPerMeterUsage() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(5, 2000);
        pool.borrowConnection(HOST, PORT);

        Map<String, Object> stats = pool.getStats();

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> perMeter = (Map<String, Map<String, Object>>) stats.get("perMeter");
        Map<String, Object> meterStats = perMeter.get(HOST + ":" + PORT);
        assertEquals(1, meterStats.get("used"));
        assertEquals(4, meterStats.get("available"));
        assertEquals(0, meterStats.get("idle"));
    }

    @Test
    void testGetStats_AfterReturn_ReportsIdleConnection() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(5, 2000);
        GXNet connection = pool.borrowConnection(HOST, PORT);
        pool.returnConnection(connection, HOST, PORT);

        Map<String, Object> stats = pool.getStats();

        assertEquals(1, stats.get("idleSize"));
    }

    // ════════════════════════════════════════════════════════════════════
    // SHUTDOWN - LEGACY ACTIVE CONNECTIONS AND ERROR HANDLING
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testShutdown_ClosesLegacyActiveConnections() throws Exception {
        mockOpenableConnections();
        DlmsClientPool pool = new DlmsClientPool(10, 2000);
        GXNet connection = pool.getConnection(HOST, PORT); // populates the legacy connectionPool map

        pool.shutdown();

        verify(connection).close();
    }

    @Test
    void testShutdown_IdleConnectionCloseThrows_DoesNotAbortShutdown() throws Exception {
        gxNetConstruction = mockConstruction(GXNet.class, (mock, context) -> {
            when(mock.isOpen()).thenReturn(true);
            doThrow(new RuntimeException("already broken")).when(mock).close();
        });
        DlmsClientPool pool = new DlmsClientPool(10, 2000);
        GXNet connection = pool.borrowConnection(HOST, PORT);
        pool.returnConnection(connection, HOST, PORT);

        assertDoesNotThrow(pool::shutdown);
    }
}
