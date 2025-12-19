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
            } catch (java.io.IOException ignored) {
            }
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt) -> mockClient;

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
            } catch (java.io.IOException ignored) {
            }
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt) -> mockClient;

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
            } catch (java.io.IOException ignored) {
            }
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt) -> mockClient;

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
            } catch (java.io.IOException ignored) {
            }
            TbSendToTcpNode.clientFactory = (h, p, t, ssl, ct, rt) -> mockClient;
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
}
