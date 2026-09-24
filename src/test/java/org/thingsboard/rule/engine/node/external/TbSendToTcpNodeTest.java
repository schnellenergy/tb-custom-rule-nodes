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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import org.thingsboard.common.util.ListeningExecutor;

import java.util.UUID;
import org.thingsboard.server.common.data.id.DeviceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TbSendToTcpNodeTest {

    private TbSendToTcpNode node;
    private TbContext ctx;
    private TbMsg msg;
    private TbMsgMetaData metaData;

    @BeforeEach
    public void setup() throws TbNodeException {
        node = new TbSendToTcpNode();
        ctx = mock(TbContext.class);
        ListeningExecutor executor = mock(ListeningExecutor.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(ctx.getExternalCallExecutor()).thenReturn(executor);
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTls(false);
        // Serialize config to JSON and use TbNodeUtils.convert to simulate production
        // config
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(config);
        org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(
                json);
        node.init(ctx, tbNodeConfig);
        metaData = new TbMsgMetaData();
    }

    @Test
    public void testStringPayloadType() {
        // Inject mock client to avoid external network calls
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbTcpClient mockClient = mock(TbTcpClient.class);
            try {
                when(mockClient.sendRequest(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                        .thenReturn("{\"response\":\"string\"}");
            } catch (Exception ignored) {
            }
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> mockClient;

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "3.109.15.103");
            localMeta.putValue("tcpPort", "10550");
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(
                    json);
            try {
                node.init(ctx, tbNodeConfig);
            } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
                org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
            }
            msg = TbMsg.newMsg().data("{\"payload\":\"string\"}").metaData(
                    localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            node.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(m.getData());
                            return node.has("response") && "string".equals(node.get("response").asText());
                        } catch (Exception e) {
                            return false;
                        }
                    }));
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
        }
    }

    @Test
    public void testJsonPayloadType() {
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbTcpClient mockClient = mock(TbTcpClient.class);
            try {
                when(mockClient.sendRequest(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                        .thenReturn("{\"response\":{\"key\":\"value\"}}");
            } catch (Exception ignored) {
            }
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> mockClient;

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "3.109.15.103");
            localMeta.putValue("tcpPort", "10550");
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("JSON");
            config.setResponseType("JSON");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(
                    json);
            try {
                node.init(ctx, tbNodeConfig);
            } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
                org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
            }
            msg = TbMsg.newMsg().data("{\"payload\":\"{\\\"key\\\":\\\"value\\\"}\"}").metaData(
                    localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            node.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(m.getData());
                            return node.has("response") && node.get("response").has("key") &&
                                    "value".equals(node.get("response").get("key").asText());
                        } catch (Exception e) {
                            return false;
                        }
                    }));
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
        }
    }

    @Test
    public void testBinaryPayloadType() {
        // Inject mock client that returns a base64 response JSON
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbTcpClient mockClient = mock(TbTcpClient.class);
            try {
                when(mockClient.sendRequest(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                        .thenReturn("{\"response\":\"eqcADcABwQAHAQBeWwD/AgA=\"}");
            } catch (Exception ignored) {
            }
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> mockClient;

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "3.109.15.103");
            localMeta.putValue("tcpPort", "10550");
            localMeta.putValue("otherMeta", "Legacy Meta Chain");
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("BINARY");
            config.setResponseType("BINARY");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(
                    json);
            try {
                node.init(ctx, tbNodeConfig);
            } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
                org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
            }
            msg = mock(TbMsg.class);
            when(msg.getMetaData()).thenReturn(localMeta);
            when(msg.getDataType()).thenReturn(TbMsgDataType.BINARY);
            // Use a real TbMsg instance so the internal processing context is initialized
            String base64Data = "{\"payload\":\"eqcADcABwQAHAQBeWwD/AgA=\"}";
            msg = TbMsg.newMsg().data(base64Data).metaData(
                    localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            node.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(m.getData());
                            return node.has("response")
                                    && "eqcADcABwQAHAQBeWwD/AgA=".equals(node.get("response").asText());
                        } catch (Exception e) {
                            return false;
                        }
                    }));
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
        }
    }

    @Test
    public void testTlsModeWithValidCerts() {
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbTcpClient mockClient = mock(TbTcpClient.class);
            try {
                when(mockClient.sendRequest(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                        .thenReturn("{\"response\":\"test over tls\"}");
            } catch (Exception ignored) {
            }
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> mockClient;
            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "3.109.15.103");
            localMeta.putValue("tcpPort", "10550");
            localMeta.putValue("caPem", "-----BEGIN CERTIFICATE-----\n" + //
                    "MIIEJzCCAw+gAwIBAgIUU2GTeytpdw+43i05YCL0yNypBcMwDQYJKoZIhvcNAQEL\n" + //
                    "BQAwgaIxCzAJBgNVBAYTAklOMRIwEAYDVQQIDAlUYW1pbG5hZHUxEzARBgNVBAcM\n" + //
                    "CkNvaW1iYXRvcmUxEDAOBgNVBAoMB1NjaG5lbGwxCzAJBgNVBAsMAklUMRswGQYD\n" + //
                    "VQQDDBJTY2huZWxsLVByaXZhdGUtQ0ExLjAsBgkqhkiG9w0BCQEWH2l0LmVuZ2lu\n" + //
                    "ZWVyMTFAc2NobmVsbGVuZXJneS5jb20wHhcNMjUxMTI1MDcxODE3WhcNMzAxMTI0\n" + //
                    "MDcxODE3WjCBojELMAkGA1UEBhMCSU4xEjAQBgNVBAgMCVRhbWlsbmFkdTETMBEG\n" + //
                    "A1UEBwwKQ29pbWJhdG9yZTEQMA4GA1UECgwHU2NobmVsbDELMAkGA1UECwwCSVQx\n" + //
                    "GzAZBgNVBAMMElNjaG5lbGwtUHJpdmF0ZS1DQTEuMCwGCSqGSIb3DQEJARYfaXQu\n" + //
                    "ZW5naW5lZXIxMUBzY2huZWxsZW5lcmd5LmNvbTCCASIwDQYJKoZIhvcNAQEBBQAD\n" + //
                    "ggEPADCCAQoCggEBALr2ahyrSKc+7Owp2Un666WFMre6zpfnZIEyow+nzg5wYD+D\n" + //
                    "7gDS/aSJSmNu0CLLcS5sB03DvUsNNKbbwWIeL99n4Bmd2WoqAkHgYrAjuWvqk+4a\n" + //
                    "IjrrLzgd/eICsBd6y2gD4h/7Wrg6+youuZ2jiMJM4iM4sGfHweFDH7R83UO6r0Ek\n" + //
                    "0qmJ1dNWGTX+nP2OkscsMTanPp9YZOzOu0e6VkQRUhi4Bt1sCamHlbloLhHhyuFk\n" + //
                    "242OwzzDbbOyD3SH85TwlWb/Z+VVM6kfB0uXg6aOm0PzdYfAHsWv0rNFawd7U66V\n" + //
                    "Cjay1sHKivfTr2WO4iSoFpE+QVJegdNUrFLKF5ECAwEAAaNTMFEwHQYDVR0OBBYE\n" + //
                    "FA9PdzIDQpEh6s0j3A6dXBYbCfZYMB8GA1UdIwQYMBaAFA9PdzIDQpEh6s0j3A6d\n" + //
                    "XBYbCfZYMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAJQq4O/u\n" + //
                    "i2eEByh1J90mVMu1OqHT6tkp/O9BjHRoHriPiDA81E3ZhntkQE4ptxeunulopQqa\n" + //
                    "yGXy10asc+Dvsdc8o0nXwkyDP5MWGJuN5Pd6P+3T6ZwKOWVTVSJEiCgd3n5fhXPx\n" + //
                    "1hKndw0MUsXYy4IkrNMO9bsJVfyxbe0BTeqLIL8TLfta0QTjBSVw7affIsjUxNxR\n" + //
                    "jXQ3xPy/mheURkAGgPJk2kaInPWjfAWgEojf+vOnTthnIHWR3sVpZDgMfnAkhaX7\n" + //
                    "O57elGC3mwmjfaLGAhU33ezLzY2XkbvGJ77uYzZq3WE4jl4smf46gJsuRlYn2Ul1\n" + //
                    "vEr9+mqowdIZkoI=\n" + //
                    "-----END CERTIFICATE-----");
            localMeta.putValue("keyPassword", "");
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(true);
            config.setPayloadType("BINARY");
            config.setResponseType("BINARY");
            config.getTlsConfig().setCaCertificateKey("${caPem}");
            config.getTlsConfig().setVerifyServerCertificate(false);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(
                    json);
            try {
                node.init(ctx, tbNodeConfig);
            } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
                org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
            }
            msg = TbMsg.newMsg().data("{\"payload\":\"eqcAD8MBwQAoAAcZCQD/AQEPAA==\"}").metaData(
                    localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            node.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(m.getData());
                            return node.has("response") && "test over tls".equals(node.get("response").asText());
                        } catch (Exception e) {
                            return false;
                        }
                    }));
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
        }
    }

    @Test
    public void testTlsModeWithInvalidCerts() {
        TbMsgMetaData localMeta = new TbMsgMetaData();
        localMeta.putValue("tcpHost", "3.109.15.103");
        localMeta.putValue("tcpPort", "10550");
        localMeta.putValue("caPem", "invalid-ca");
        localMeta.putValue("certPem", "invalid-cert");
        localMeta.putValue("keyPem", "invalid-key");
        localMeta.putValue("keyPassword", "");
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTls(true);
        config.setPayloadType("TEXT");
        config.setResponseType("TEXT");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(config);
        org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(
                json);
        try {
            node.init(ctx, tbNodeConfig);
        } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
            org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
        }
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(localMeta);
        when(msg.getData()).thenReturn("{\"payload\":\"test over tls\"}");
        node.onMsg(ctx, msg);
        verify(ctx).tellFailure(eq(msg), any(Exception.class));
    }

    @Test
    public void testInvalidPort() {
        metaData.putValue("tcpHost", "127.0.0.1");
        metaData.putValue("tcpPort", "notaport");
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(metaData);
        when(msg.getData()).thenReturn("[\"test\"]");
        node.onMsg(ctx, msg);
        verify(ctx).tellFailure(eq(msg), any(IllegalArgumentException.class));
    }

    @Test
    public void testNoHost() {
        metaData.putValue("tcpPort", "1234");
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
        // Re-init node with TLS enabled
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTls(true);
        config.getTlsConfig().setCaCertificateKey("${caPem}");
        config.getTlsConfig().setCertificateKey("${certPem}");
        config.getTlsConfig().setPrivateKeyKey("${keyPem}");
        config.getTlsConfig().setPrivateKeyPassphraseKey("${keyPassword}");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(config);
        org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(
                json);
        try {
            node.init(ctx, tbNodeConfig);
        } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
            org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
        }

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

    // ── Framing Tests ───────────────────────────────────────────────────

    @Test
    public void testFramingNone_SmartTimeout_FragmentedResponse() throws Exception {
        // Start a local TCP server that sends a response in two fragments with a gap
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int serverPort = server.getLocalPort();
        Thread serverThread = new Thread(() -> {
            try (java.net.Socket client = server.accept()) {
                java.io.InputStream in = client.getInputStream();
                byte[] buf = new byte[1024];
                int n = in.read(buf); // consume request
                java.io.OutputStream out = client.getOutputStream();
                // Fragment 1
                out.write("{\"response\":\"hel".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(10); // simulate network delay
                // Fragment 2
                out.write("lo\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) ->
                    new TbTcpClient(h, p, t, ssl, ct, rt, ka);

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "127.0.0.1");
            localMeta.putValue("tcpPort", String.valueOf(serverPort));
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            config.setFramingType("NONE");
            config.setFragmentTimeoutMs(100);
            config.setKeepAlive(false);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig =
                    new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            testNode.init(ctx, tbNodeConfig);

            msg = TbMsg.newMsg().data("{\"payload\":\"request\"}")
                    .metaData(localMeta)
                    .originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new ObjectMapper().readTree(m.getData());
                            return node.has("response")
                                    && node.get("response").asText().contains("hello");
                        } catch (Exception e) {
                            return false;
                        }
                    }));
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
            server.close();
        }
    }

    @Test
    public void testFramingDelimiter_NewlineTerminated() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int serverPort = server.getLocalPort();
        Thread serverThread = new Thread(() -> {
            try (java.net.Socket client = server.accept()) {
                java.io.InputStream in = client.getInputStream();
                byte[] buf = new byte[1024];
                in.read(buf);
                java.io.OutputStream out = client.getOutputStream();
                out.write("hello-delim\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) ->
                    new TbTcpClient(h, p, t, ssl, ct, rt, ka);

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "127.0.0.1");
            localMeta.putValue("tcpPort", String.valueOf(serverPort));
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            config.setFramingType("DELIMITER");
            config.setDelimiter("\\n");
            config.setKeepAlive(false);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig =
                    new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            testNode.init(ctx, tbNodeConfig);

            msg = TbMsg.newMsg().data("{\"payload\":\"request\"}")
                    .metaData(localMeta)
                    .originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new ObjectMapper().readTree(m.getData());
                            return node.has("response")
                                    && "hello-delim".equals(node.get("response").asText());
                        } catch (Exception e) {
                            return false;
                        }
                    }));
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
            server.close();
        }
    }

    @Test
    public void testFramingLengthPrefix_4ByteBigEndian() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int serverPort = server.getLocalPort();
        Thread serverThread = new Thread(() -> {
            try (java.net.Socket client = server.accept()) {
                java.io.InputStream in = client.getInputStream();
                byte[] buf = new byte[1024];
                in.read(buf);
                java.io.OutputStream out = client.getOutputStream();
                // Send 4-byte big-endian length prefix + payload
                byte[] payload = "length-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.nio.ByteBuffer lenBuf = java.nio.ByteBuffer.allocate(4);
                lenBuf.order(java.nio.ByteOrder.BIG_ENDIAN);
                lenBuf.putInt(payload.length);
                out.write(lenBuf.array());
                out.write(payload);
                out.flush();
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) ->
                    new TbTcpClient(h, p, t, ssl, ct, rt, ka);

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "127.0.0.1");
            localMeta.putValue("tcpPort", String.valueOf(serverPort));
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            config.setFramingType("LENGTH_PREFIX");
            config.setMaxFrameLength(1048576);
            config.setLengthFieldOffset(0);
            config.setLengthFieldLength(4);
            config.setLengthAdjustment(0);
            config.setInitialBytesToStrip(4); // strip the 4-byte header
            config.setLengthByteOrder("BIG_ENDIAN");
            config.setKeepAlive(false);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig =
                    new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            testNode.init(ctx, tbNodeConfig);

            msg = TbMsg.newMsg().data("{\"payload\":\"request\"}")
                    .metaData(localMeta)
                    .originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new ObjectMapper().readTree(m.getData());
                            return node.has("response")
                                    && "length-ok".equals(node.get("response").asText());
                        } catch (Exception e) {
                            return false;
                        }
                    }));
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
            server.close();
        }
    }

    @Test
    public void testFramingNone_RetryOnStaleConnection() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int serverPort = server.getLocalPort();
        java.util.concurrent.atomic.AtomicInteger connectionCount = new java.util.concurrent.atomic.AtomicInteger(0);

        Thread serverThread = new Thread(() -> {
            try {
                // Connection 1: Accept, read request, and close socket immediately (EOF)
                try (java.net.Socket client = server.accept()) {
                    connectionCount.incrementAndGet();
                    java.io.InputStream in = client.getInputStream();
                    byte[] buf = new byte[1024];
                    in.read(buf); // consume request
                }
                
                // Connection 2: Accept retry, read request, and send response
                try (java.net.Socket client = server.accept()) {
                    connectionCount.incrementAndGet();
                    java.io.InputStream in = client.getInputStream();
                    byte[] buf = new byte[1024];
                    in.read(buf); // consume request
                    java.io.OutputStream out = client.getOutputStream();
                    out.write("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) ->
                    new TbTcpClient(h, p, t, ssl, ct, rt, ka);

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "127.0.0.1");
            localMeta.putValue("tcpPort", String.valueOf(serverPort));
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            config.setFramingType("NONE");
            config.setFragmentTimeoutMs(50);
            config.setKeepAlive(true);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig =
                    new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            testNode.init(ctx, tbNodeConfig);

            msg = TbMsg.newMsg().data("{\"payload\":\"request\"}")
                    .metaData(localMeta)
                    .originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);

            verify(ctx).tellSuccess(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        try {
                            com.fasterxml.jackson.databind.JsonNode node =
                                    new ObjectMapper().readTree(m.getData());
                            return node.has("response")
                                    && "ok".equals(node.get("response").asText());
                        } catch (Exception e) {
                            return false;
                        }
                    }));

            org.junit.jupiter.api.Assertions.assertEquals(2, connectionCount.get());
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
            server.close();
        }
    }

    @Test
    public void testResolveDelimiterBytes() {
        // Newline escape
        byte[] nl = TbTcpClient.resolveDelimiterBytes("\\n");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{'\n'}, nl);

        // CRLF
        byte[] crlf = TbTcpClient.resolveDelimiterBytes("\\r\\n");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{'\r', '\n'}, crlf);

        // Null byte via unicode
        byte[] nul = TbTcpClient.resolveDelimiterBytes("\\u0000");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{0}, nul);

        // Literal string (no escapes)
        byte[] lit = TbTcpClient.resolveDelimiterBytes("END");
        org.junit.jupiter.api.Assertions.assertArrayEquals("END".getBytes(java.nio.charset.StandardCharsets.UTF_8), lit);

        // Null/empty defaults to newline
        byte[] def = TbTcpClient.resolveDelimiterBytes(null);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{'\n'}, def);
    }

    // ── New tests for issues #4433 / #4434 / #4435 ──────────────────────────

    /**
     * #4433 — Connection refused must produce tcpErrorPhase=CONNECT in failure msg metadata.
     */
    @Test
    public void testConnectionRefused_structuredFailure() {
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> {
                TbTcpClient mockClient = mock(TbTcpClient.class);
                try {
                    org.mockito.Mockito.doThrow(new TbTcpException("CONNECT", "ConnectException",
                            "Connection refused to " + h + ":" + p, h + ":" + p, null,
                            new java.net.ConnectException("Connection refused")))
                            .when(mockClient).connect(ct, rt);
                    when(mockClient.isValid()).thenReturn(false);
                } catch (Exception ignored) {}
                return mockClient;
            };

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "10.0.0.1");
            localMeta.putValue("tcpPort", "4059");

            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            try {
                testNode.init(ctx, new org.thingsboard.rule.engine.api.TbNodeConfiguration(json));
            } catch (TbNodeException e) {
                org.junit.jupiter.api.Assertions.fail("init failed: " + e.getMessage());
            }
            msg = TbMsg.newMsg().data("{\"payload\":\"cmd\"}")
                    .metaData(localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);

            verify(ctx).tellFailure(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        String phase = m.getMetaData().getValue("tcpErrorPhase");
                        String detail = m.getMetaData().getValue("tcpErrorDetail");
                        return "CONNECT".equals(phase) && detail != null && detail.contains("10.0.0.1");
                    }),
                    any(TbTcpException.class));
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
        }
    }

    /**
     * #4434 — A second concurrent borrow for the same host:port must throw BUSY.
     */
    @Test
    public void testConnectionPool_busyRejection() throws Exception {
        TbTcpConnectionPool testPool = new TbTcpConnectionPool(1, false, 120_000);
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> {
                TbTcpClient mc = mock(TbTcpClient.class);
                when(mc.isValid()).thenReturn(true);
                return mc;
            };

            // First borrow succeeds
            TbTcpClient first = testPool.borrowClient("10.0.0.2", 4059, false, null, 500, 2000, "cmd-1");
            org.junit.jupiter.api.Assertions.assertNotNull(first);

            // Second borrow for same target must throw BUSY
            try {
                testPool.borrowClient("10.0.0.2", 4059, false, null, 500, 2000, "cmd-2");
                org.junit.jupiter.api.Assertions.fail("Expected TbTcpException(BUSY)");
            } catch (TbTcpException te) {
                org.junit.jupiter.api.Assertions.assertEquals("POOL", te.getPhase());
                org.junit.jupiter.api.Assertions.assertEquals("BUSY", te.getErrorClass());
            }

            // Different target must proceed normally
            TbTcpClient other = testPool.borrowClient("10.0.0.3", 4059, false, null, 500, 2000, "cmd-3");
            org.junit.jupiter.api.Assertions.assertNotNull(other);

            testPool.returnClient(first, "10.0.0.2", 4059);
            testPool.returnClient(other, "10.0.0.3", 4059);
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
            testPool.closeAll();
        }
    }

    /**
     * #4435 — Read timeout must NOT trigger a reconnect-and-retry.
     * Exactly one TCP connection must be made; failure metadata must carry phase=READ.
     */
    @Test
    public void testReadTimeout_noRetry() throws Exception {
        java.util.concurrent.atomic.AtomicInteger connectionCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int serverPort = server.getLocalPort();
        Thread serverThread = new Thread(() -> {
            try {
                java.net.Socket client = server.accept();
                connectionCount.incrementAndGet();
                client.getInputStream().read(new byte[1024]);
                Thread.sleep(60_000); // hold; never respond
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) ->
                    new TbTcpClient(h, p, t, ssl, ct, rt, ka);

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "127.0.0.1");
            localMeta.putValue("tcpPort", String.valueOf(serverPort));
            localMeta.putValue("rpcTimeoutMs", "400"); // read budget ~ 300 ms

            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            config.setFramingType("NONE");
            config.setFragmentTimeoutMs(50);
            config.setKeepAlive(false);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            testNode.init(ctx, new org.thingsboard.rule.engine.api.TbNodeConfiguration(json));

            msg = TbMsg.newMsg().data("{\"payload\":\"cmd\"}")
                    .metaData(localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);

            verify(ctx).tellFailure(
                    org.mockito.ArgumentMatchers.argThat(m ->
                            "READ".equals(m.getMetaData().getValue("tcpErrorPhase"))),
                    any(Exception.class));
            org.junit.jupiter.api.Assertions.assertEquals(1, connectionCount.get(),
                    "Read timeout must not trigger a reconnect-and-retry");
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
            server.close();
        }
    }

    /**
     * #4435 — cmdId from metadata must appear in the failure message metadata.
     */
    @Test
    public void testCmdIdPropagatedInFailureMetadata() {
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            String expectedCmdId = "cmd-abc-123";
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> {
                TbTcpClient mc = mock(TbTcpClient.class);
                try {
                    org.mockito.Mockito.doThrow(new TbTcpException("CONNECT", "ConnectException",
                            "Connection refused", h + ":" + p, expectedCmdId,
                            new java.net.ConnectException("refused")))
                            .when(mc).connect(ct, rt);
                    when(mc.isValid()).thenReturn(false);
                } catch (Exception ignored) {}
                return mc;
            };

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "10.0.0.1");
            localMeta.putValue("tcpPort", "4059");
            localMeta.putValue("cmdId", expectedCmdId);

            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            try {
                testNode.init(ctx, new org.thingsboard.rule.engine.api.TbNodeConfiguration(json));
            } catch (TbNodeException e) {
                org.junit.jupiter.api.Assertions.fail("init failed: " + e.getMessage());
            }
            msg = TbMsg.newMsg().data("{\"payload\":\"cmd\"}")
                    .metaData(localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);

            verify(ctx).tellFailure(
                    org.mockito.ArgumentMatchers.argThat(m ->
                            expectedCmdId.equals(m.getMetaData().getValue("cmdId"))),
                    any(Exception.class));
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
        }
    }

    /**
     * #4435 — rpcTimeoutMs=10000 must derive connectMs=2000 and readMs=7500.
     */
    @Test
    public void testRpcTimeoutDrivesConnectAndReadTimeouts() throws Exception {
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            java.util.concurrent.atomic.AtomicInteger capturedConnect = new java.util.concurrent.atomic.AtomicInteger(-1);
            java.util.concurrent.atomic.AtomicInteger capturedRead = new java.util.concurrent.atomic.AtomicInteger(-1);

            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> {
                capturedConnect.set(ct);
                capturedRead.set(rt);
                TbTcpClient mc = mock(TbTcpClient.class);
                try {
                    when(mc.isValid()).thenReturn(true);
                    when(mc.sendRequest(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                            .thenReturn("{\"response\":\"ok\"}");
                    when(mc.sendRequest(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.any()))
                            .thenReturn("{\"response\":\"ok\"}");
                    when(mc.getLastPhaseTimings()).thenReturn(new long[]{10L, 5L, 50L});
                } catch (Exception ignored) {}
                return mc;
            };

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "10.0.0.1");
            localMeta.putValue("tcpPort", "4059");
            localMeta.putValue("rpcTimeoutMs", "10000");

            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            config.setConnectTimeout(1000); // must be overridden
            config.setReadTimeout(5000);    // must be overridden
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            testNode.init(ctx, new org.thingsboard.rule.engine.api.TbNodeConfiguration(json));

            msg = TbMsg.newMsg().data("{\"payload\":\"cmd\"}")
                    .metaData(localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);

            org.junit.jupiter.api.Assertions.assertEquals(2000, capturedConnect.get(),
                    "connectTimeout must be 20% of rpcTimeoutMs=10000");
            org.junit.jupiter.api.Assertions.assertEquals(7500, capturedRead.get(),
                    "readTimeout must be 75% of rpcTimeoutMs=10000");
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
        }
    }

    /**
     * #4434 — Idle sockets beyond maxIdleMs must be evicted; a new connection created.
     */
    @Test
    public void testPoolEvictsIdleSocket() throws Exception {
        TbTcpConnectionPool testPool = new TbTcpConnectionPool(10, false, 100);
        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        java.util.concurrent.atomic.AtomicInteger newConnections = new java.util.concurrent.atomic.AtomicInteger(0);
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) -> {
                newConnections.incrementAndGet();
                TbTcpClient mc = mock(TbTcpClient.class);
                when(mc.isValid()).thenReturn(true);
                return mc;
            };

            TbTcpClient first = testPool.borrowClient("10.0.0.5", 4059, false, null, 500, 2000, null);
            testPool.returnClient(first, "10.0.0.5", 4059);

            Thread.sleep(200); // exceed maxIdleMs=100

            TbTcpClient second = testPool.borrowClient("10.0.0.5", 4059, false, null, 500, 2000, null);
            org.junit.jupiter.api.Assertions.assertNotNull(second);
            org.junit.jupiter.api.Assertions.assertEquals(2, newConnections.get(),
                    "A new connection must be made after idle eviction");
            testPool.returnClient(second, "10.0.0.5", 4059);
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
            testPool.closeAll();
        }
    }

    /**
     * #4435 — SocketTimeoutException on read → tcpErrorPhase=READ + cmdId in failure metadata.
     */
    @Test
    public void testReadTimeout_metadataContainsReadPhase() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int serverPort = server.getLocalPort();
        Thread serverThread = new Thread(() -> {
            try {
                java.net.Socket client = server.accept();
                client.getInputStream().read(new byte[1024]);
                Thread.sleep(60_000);
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        TbSendToTcpNode.ClientFactory originalFactory = TbSendToTcpNode.clientFactory;
        try {
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt, ka) ->
                    new TbTcpClient(h, p, t, ssl, ct, rt, ka);

            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "127.0.0.1");
            localMeta.putValue("tcpPort", String.valueOf(serverPort));
            localMeta.putValue("rpcTimeoutMs", "400");
            localMeta.putValue("cmdId", "test-cmd-999");

            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTls(false);
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            config.setFramingType("NONE");
            config.setFragmentTimeoutMs(50);
            config.setKeepAlive(false);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            TbSendToTcpNode testNode = new TbSendToTcpNode();
            testNode.init(ctx, new org.thingsboard.rule.engine.api.TbNodeConfiguration(json));

            msg = TbMsg.newMsg().data("{\"payload\":\"cmd\"}")
                    .metaData(localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            testNode.onMsg(ctx, msg);

            verify(ctx).tellFailure(
                    org.mockito.ArgumentMatchers.argThat(m -> {
                        String phase  = m.getMetaData().getValue("tcpErrorPhase");
                        String detail = m.getMetaData().getValue("tcpErrorDetail");
                        String cmdId  = m.getMetaData().getValue("cmdId");
                        return "READ".equals(phase)
                                && detail != null && !detail.isEmpty()
                                && "test-cmd-999".equals(cmdId);
                    }),
                    any(Exception.class));
            testNode.destroy();
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
            server.close();
        }
    }
}
