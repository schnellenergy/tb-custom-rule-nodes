package org.thingsboard.rule.engine.node.external;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TbSendToTcpNodeTest {
    @Test
    public void testStringPayloadType() {
        TbMsgMetaData localMeta = new TbMsgMetaData();
        localMeta.putValue("tcpHost", "3.109.15.103");
        localMeta.putValue("tcpPort", "10550");
        localMeta.putValue("tcpTls", "false");
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTlsKey("${tcpTls}");
        config.setPayloadType("STRING");
        config.setResponseType("STRING");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(config);
        org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
        try {
            node.init(ctx, tbNodeConfig);
        } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
            org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
        }
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(localMeta);
        when(msg.getData()).thenReturn("{\"test\":\"string\"}");
        node.onMsg(ctx, msg);
        verify(ctx).tellNext(
            org.mockito.ArgumentMatchers.argThat(m ->
                m.getData().equals("{\"test\":\"string\"}") &&
                m.getMetaData().getValue("tcpResponse").equals("{\"test\":\"string\"}")
            ),
            eq("Success")
        );
    }

    @Test
    public void testJsonPayloadType() {
        TbMsgMetaData localMeta = new TbMsgMetaData();
        localMeta.putValue("tcpHost", "3.109.15.103");
        localMeta.putValue("tcpPort", "10550");
        localMeta.putValue("tcpTls", "false");
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTlsKey("${tcpTls}");
        config.setPayloadType("JSON");
        config.setResponseType("JSON");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(config);
        org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
        try {
            node.init(ctx, tbNodeConfig);
        } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
            org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
        }
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(localMeta);
        when(msg.getData()).thenReturn("{\"key\":\"value\"}");
        node.onMsg(ctx, msg);
        verify(ctx).tellNext(
            org.mockito.ArgumentMatchers.argThat(m ->
                m.getData().equals("{\"key\":\"value\"}") &&
                m.getMetaData().getValue("tcpResponse").equals("{\"key\":\"value\"}")
            ),
            eq("Success")
        );
    }

    @Test
    public void testBinaryPayloadType() {
        TbMsgMetaData localMeta = new TbMsgMetaData();
        localMeta.putValue("tcpHost", "3.109.15.103");
        localMeta.putValue("tcpPort", "10550");
        localMeta.putValue("tcpTls", "false");
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTlsKey("${tcpTls}");
        config.setPayloadType("BINARY");
        config.setResponseType("BINARY");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(config);
        org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
        try {
            node.init(ctx, tbNodeConfig);
        } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
            org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
        }
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(localMeta);
        // Base64 for 'test' is dGVzdA==
        when(msg.getData()).thenReturn("{\"data\":\"e1wia2V5XCI6XCJkR1Z6ZEE9PVwifQ==\"}");
        node.onMsg(ctx, msg);
        verify(ctx).tellNext(
            org.mockito.ArgumentMatchers.argThat(m ->
                m.getData().equals("{\"data\":\"e1wia2V5XCI6XCJkR1Z6ZEE9PVwifQ==\"}") &&
                m.getMetaData().getValue("tcpResponse").equals("{\"data\":\"e1wia2V5XCI6XCJkR1Z6ZEE9PVwifQ==\"}")
            ),
            eq("Success")
        );
    }
    private TbSendToTcpNode node;
    private TbContext ctx;
    private TbMsg msg;
    private TbMsgMetaData metaData;

    @BeforeEach
    public void setup() throws TbNodeException {
        node = new TbSendToTcpNode();
        ctx = mock(TbContext.class);
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTlsKey("${tcpTls}");
        // Serialize config to JSON and use TbNodeUtils.convert to simulate production config
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(config);
        org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
        node.init(ctx, tbNodeConfig);
        metaData = new TbMsgMetaData();
    }

    @Test
    public void testInvalidPort() {
        metaData.putValue("tcpHost", "127.0.0.1");
        metaData.putValue("tcpPort", "notaport");
        metaData.putValue("tcpTls", "false");
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(metaData);
        when(msg.getData()).thenReturn("[\"test\"]");
        node.onMsg(ctx, msg);
        verify(ctx).tellFailure(eq(msg), any(IllegalArgumentException.class));
    }

    @Test
    public void testNoHost() {
        metaData.putValue("tcpPort", "1234");
        metaData.putValue("tcpTls", "false");
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(metaData);
        when(msg.getData()).thenReturn("[\"test\"]");
        node.onMsg(ctx, msg);
        // Should fail with NullPointerException or similar
        verify(ctx).tellFailure(eq(msg), any(Exception.class));
    }

    @Test
    public void testNoPort() {
        metaData.putValue("tcpHost", "127.0.0.1");
        metaData.putValue("tcpTls", "false");
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(metaData);
        when(msg.getData()).thenReturn("[\"test\"]");
        node.onMsg(ctx, msg);
        verify(ctx).tellFailure(eq(msg), any(Exception.class));
    }

    @Test
    public void testNoTls() {
        metaData.putValue("tcpHost", "127.0.0.1");
        metaData.putValue("tcpPort", "1234");
        metaData.putValue("tcpTls", "false");
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(metaData);
        when(msg.getData()).thenReturn("[\"test\"]");
        // Socket will fail to connect, but we want to verify the code path
        node.onMsg(ctx, msg);
        verify(ctx).tellFailure(eq(msg), any(Exception.class));
    }

    @Test
    public void testWithTlsPem() {
        metaData.putValue("tcpHost", "127.0.0.1");
        metaData.putValue("tcpPort", "1234");
        metaData.putValue("tcpTls", "true");
        metaData.putValue("caPem", "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----");
        metaData.putValue("certPem", "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----");
        metaData.putValue("keyPem", "-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----");
        metaData.putValue("keyPassword", "");
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(metaData);
        when(msg.getData()).thenReturn("[\"test\"]");
        node.onMsg(ctx, msg);
        verify(ctx).tellFailure(eq(msg), any(Exception.class));
    }
}
