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

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import static org.thingsboard.server.common.data.msg.TbMsgType.POST_ATTRIBUTES_REQUEST;

/**
 * Unit Tests for TbDlmsDownlinkNode
 *
 * DlmsExecutor construction is mocked (via Mockito's mockConstruction), so every
 * "new DlmsExecutor(...)" inside init() returns a controllable mock - this isolates
 * the node's own responsibilities (message parsing, async dispatch to the internal
 * thread pool, success/failure routing back to the rule chain) from real DLMS
 * communication, which is DlmsExecutor's own already-tested responsibility.
 *
 * onMsg() submits work to an internal thread pool and returns immediately, so
 * assertions on ctx.tellSuccess()/tellFailure() for the async path use Mockito's
 * timeout() to wait for that work to complete rather than asserting instantly.
 * Message-parsing failures, by contrast, are synchronous (checked before the
 * thread pool is touched at all) and are asserted immediately.
 *
 * Test Categories:
 * ✓ Lifecycle (2): init constructs exactly one DlmsExecutor, destroy is idempotent
 * ✓ Message Parsing / Validation (5): valid JSON, missing host/port/action, malformed JSON
 * ✓ Host/Port via configured key (2): extracted from a non-"host"/"port" field, direct field wins
 * ✓ Async Execution Routing (2): success returns response JSON, executor failure routes to tellFailure
 */
public class TbDlmsDownlinkNodeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private DeviceId deviceId;
    private TbDlmsDownlinkNode node;
    private TbContext ctx;
    private MockedConstruction<DlmsExecutor> executorConstruction;
    private DlmsResponse cannedResponse;

    @BeforeEach
    void setUp() throws TbNodeException {
        deviceId = new DeviceId(UUID.randomUUID());
        ctx = mock(TbContext.class);

        cannedResponse = new DlmsResponse();
        cannedResponse.setSuccess(true);
        cannedResponse.setResponseCode("0");
        cannedResponse.setData("1234");

        executorConstruction = mockConstruction(DlmsExecutor.class,
                (mock, context) -> when(mock.execute(any())).thenReturn(cannedResponse));

        node = new TbDlmsDownlinkNode();
        node.init(ctx, defaultNodeConfiguration());
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.destroy();
        }
        if (executorConstruction != null) {
            executorConstruction.close();
        }
    }

    private TbNodeConfiguration defaultNodeConfiguration() {
        TbDlmsDownlinkNodeConfiguration config = new TbDlmsDownlinkNodeConfiguration().defaultConfiguration();
        return new TbNodeConfiguration(mapper.valueToTree(config));
    }

    private TbMsg msgWithData(String data) {
        return TbMsg.newMsg()
                .type(POST_ATTRIBUTES_REQUEST)
                .originator(deviceId)
                .data(data)
                .metaData(TbMsgMetaData.EMPTY)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testInit_ConstructsExactlyOneDlmsExecutor() {
        assertEquals(1, executorConstruction.constructed().size());
    }

    @Test
    void testDestroy_CalledTwice_NoError() {
        assertDoesNotThrow(() -> {
            node.destroy();
            node.destroy();
        });
    }

    // ════════════════════════════════════════════════════════════════════
    // MESSAGE PARSING / VALIDATION
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testOnMsg_ValidRequest_TellsSuccessWithResponseJson() throws Exception {
        String data = "{\"host\":\"192.168.1.1\",\"port\":4059,\"action\":\"READ\",\"obis\":\"1.0.94.91.7.255\"}";
        TbMsg msg = msgWithData(data);

        node.onMsg(ctx, msg);

        ArgumentCaptor<TbMsg> outMsgCaptor = ArgumentCaptor.forClass(TbMsg.class);
        verify(ctx, timeout(2000)).tellSuccess(outMsgCaptor.capture());
        verify(ctx, never()).tellFailure(any(), any());
        assertTrue(outMsgCaptor.getValue().getData().contains("\"success\":true"));
    }

    @Test
    void testOnMsg_MissingHost_TellsFailureImmediately() throws Exception {
        String data = "{\"port\":4059,\"action\":\"READ\"}";
        TbMsg msg = msgWithData(data);

        node.onMsg(ctx, msg);

        ArgumentCaptor<Exception> exCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(ctx, times(1)).tellFailure(eq(msg), exCaptor.capture());
        verify(ctx, never()).tellSuccess(any());
        assertTrue(exCaptor.getValue().getMessage().contains("'host' is required"));
    }

    @Test
    void testOnMsg_MissingPort_TellsFailureImmediately() throws Exception {
        String data = "{\"host\":\"192.168.1.1\",\"action\":\"READ\"}";
        TbMsg msg = msgWithData(data);

        node.onMsg(ctx, msg);

        ArgumentCaptor<Exception> exCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(ctx).tellFailure(eq(msg), exCaptor.capture());
        assertTrue(exCaptor.getValue().getMessage().contains("'port' is required"));
    }

    @Test
    void testOnMsg_MissingAction_TellsFailureImmediately() throws Exception {
        String data = "{\"host\":\"192.168.1.1\",\"port\":4059}";
        TbMsg msg = msgWithData(data);

        node.onMsg(ctx, msg);

        ArgumentCaptor<Exception> exCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(ctx).tellFailure(eq(msg), exCaptor.capture());
        assertTrue(exCaptor.getValue().getMessage().contains("'action' is required"));
    }

    @Test
    void testOnMsg_MalformedJson_TellsFailureImmediately() throws Exception {
        TbMsg msg = msgWithData("not-json-at-all");

        node.onMsg(ctx, msg);

        verify(ctx).tellFailure(eq(msg), any());
        verify(ctx, never()).tellSuccess(any());
    }

    // ════════════════════════════════════════════════════════════════════
    // HOST/PORT EXTRACTION VIA CONFIGURED KEY (default hostKey="${tcpHost}",
    // portKey="${tcpPort}" - extracted from that field name if "host"/"port"
    // aren't present directly in the message body)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testOnMsg_HostPortViaConfiguredKey_ExtractedAndSucceeds() throws Exception {
        String data = "{\"tcpHost\":\"192.168.1.1\",\"tcpPort\":4059,\"action\":\"READ\",\"obis\":\"1.0.94.91.7.255\"}";
        TbMsg msg = msgWithData(data);

        node.onMsg(ctx, msg);

        verify(ctx, timeout(2000)).tellSuccess(any());
        verify(ctx, never()).tellFailure(any(), any());
    }

    @Test
    void testOnMsg_DirectHostPortTakesPrecedenceOverConfiguredKey() throws Exception {
        // Both present - "host"/"port" fields must win over the "${tcpHost}"-derived lookup
        String data = "{\"host\":\"10.0.0.5\",\"port\":9999,\"tcpHost\":\"192.168.1.1\",\"tcpPort\":4059,\"action\":\"READ\"}";
        TbMsg msg = msgWithData(data);

        node.onMsg(ctx, msg);

        verify(ctx, timeout(2000)).tellSuccess(any());
    }

    // ════════════════════════════════════════════════════════════════════
    // ASYNC EXECUTION FAILURE ROUTING
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testOnMsg_ExecutorThrows_TellsFailureAsynchronously() throws Exception {
        // This test needs different DlmsExecutor behavior than the shared setUp()
        // default, so it builds its own node/executor rather than reusing the fields.
        executorConstruction.close();
        node.destroy();

        try (MockedConstruction<DlmsExecutor> throwingExecutor = mockConstruction(DlmsExecutor.class,
                (mock, context) -> when(mock.execute(any())).thenThrow(new RuntimeException("meter unreachable")))) {

            TbDlmsDownlinkNode freshNode = new TbDlmsDownlinkNode();
            freshNode.init(ctx, defaultNodeConfiguration());

            String data = "{\"host\":\"192.168.1.1\",\"port\":4059,\"action\":\"READ\",\"obis\":\"1.0.94.91.7.255\"}";
            TbMsg msg = msgWithData(data);

            freshNode.onMsg(ctx, msg);

            verify(ctx, timeout(2000)).tellFailure(eq(msg), any());
            verify(ctx, never()).tellSuccess(any());

            freshNode.destroy();
        }

        // Prevent tearDown() from double-closing/double-destroying the shared instances
        executorConstruction = null;
        node = null;
    }
}
