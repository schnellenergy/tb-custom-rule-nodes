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
        config.setTlsKey("${tcpTls}");
        // Serialize config to JSON and use TbNodeUtils.convert to simulate production config
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.valueToTree(config);
        org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
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
                when(mockClient.sendRequest(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                        .thenReturn("{\"response\":\"string\"}");
            } catch (java.io.IOException ignored) {}
            TbSendToTcpNode.clientFactory = (h,p,t,ssl,ct,rt) -> mockClient;
            
            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "3.109.15.103");
            localMeta.putValue("tcpPort", "10550");
            localMeta.putValue("tcpTls", "false");
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTlsKey("${tcpTls}");
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
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
                        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(m.getData());
                        return node.has("response") && "string".equals(node.get("response").asText());
                    } catch (Exception e) {
                        return false;
                    }
                })
            );
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
                when(mockClient.sendRequest(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                        .thenReturn("{\"response\":{\"key\":\"value\"}}");
            } catch (java.io.IOException ignored) {}
            TbSendToTcpNode.clientFactory = (h,p,t,ssl,ct,rt) -> mockClient;
            
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
            msg = TbMsg.newMsg().data("{\"payload\":\"{\\\"key\\\":\\\"value\\\"}\"}").metaData(
                localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            node.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                org.mockito.ArgumentMatchers.argThat(m -> {
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(m.getData());
                        return node.has("response") && node.get("response").has("key") && 
                               "value".equals(node.get("response").get("key").asText());
                    } catch (Exception e) {
                        return false;
                    }
                })
            );
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
                when(mockClient.sendRequest(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                        .thenReturn("{\"response\":\"eqcADcABwQAHAQBeWwD/AgA=\"}");
            } catch (java.io.IOException ignored) {}
            TbSendToTcpNode.clientFactory = (h,p,t,ssl,ct,rt) -> mockClient;
            
            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "3.109.15.103");
            localMeta.putValue("tcpPort", "10550");
            localMeta.putValue("tcpTls", "false");
            localMeta.putValue("otherMeta", "Legacy Meta Chain");
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
            when(msg.getDataType()).thenReturn(TbMsgDataType.BINARY);
            // Use a real TbMsg instance so the internal processing context is initialized
            String base64Data = "{\"payload\":\"eqcADcABwQAHAQBeWwD/AgA=\"}";
            msg = TbMsg.newMsg().data(base64Data).metaData(
                localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            node.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                org.mockito.ArgumentMatchers.argThat(m -> {
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(m.getData());
                        return node.has("response") && "eqcADcABwQAHAQBeWwD/AgA=".equals(node.get("response").asText());
                    } catch (Exception e) {
                        return false;
                    }
                })
            );
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
                when(mockClient.sendRequest(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                        .thenReturn("{\"response\":\"test over tls\"}");
            } catch (java.io.IOException ignored) {}
            TbSendToTcpNode.clientFactory = (h,p,t,ssl,ct,rt) -> mockClient;
            TbMsgMetaData localMeta = new TbMsgMetaData();
            localMeta.putValue("tcpHost", "3.109.15.103");
            localMeta.putValue("tcpPort", "10550");
            localMeta.putValue("tcpTls", "true");
            localMeta.putValue("caPem", "-----BEGIN CERTIFICATE-----\nMIIEqjCCA5KgAwIBAgIQAnmsRYvBskWr+YBTzSybsTANBgkqhkiG9w0BAQsFADBh\nMQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3\nd3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBD\nQTAeFw0xNzExMjcxMjQ2MTBaFw0yNzExMjcxMjQ2MTBaMG4xCzAJBgNVBAYTAlVT\nMRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5j\nb20xLTArBgNVBAMTJEVuY3J5cHRpb24gRXZlcnl3aGVyZSBEViBUTFMgQ0EgLSBH\nMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALPeP6wkab41dyQh6mKc\noHqt3jRIxW5MDvf9QyiOR7VfFwK656es0UFiIb74N9pRntzF1UgYzDGu3ppZVMdo\nlbxhm6dWS9OK/lFehKNT0OYI9aqk6F+U7cA6jxSC+iDBPXwdF4rs3KRyp3aQn6pj\npp1yr7IB6Y4zv72Ee/PlZ/6rK6InC6WpK0nPVOYR7n9iDuPe1E4IxUMBH/T33+3h\nyuH3dvfgiWUOUkjdpMbyxX+XNle5uEIiyBsi4IvbcTCh8ruifCIi5mDXkZrnMT8n\nwfYCV6v6kDdXkbgGRLKsR4pucbJtbKqIkUGxuZI2t7pfewKRc5nWecvDBZf3+p1M\npA8CAwEAAaOCAU8wggFLMB0GA1UdDgQWBBRVdE+yck/1YLpQ0dfmUVyaAYca1zAf\nBgNVHSMEGDAWgBQD3lA1VtFMu2bwo+IbG8OXsj3RVTAOBgNVHQ8BAf8EBAMCAYYw\nHQYDVR0lBBYwFAYIKwYBBQUHAwEGCCsGAQUFBwMCMBIGA1UdEwEB/wQIMAYBAf8C\nAQAwNAYIKwYBBQUHAQEEKDAmMCQGCCsGAQUFBzABhhhodHRwOi8vb2NzcC5kaWdp\nY2VydC5jb20wQgYDVR0fBDswOTA3oDWgM4YxaHR0cDovL2NybDMuZGlnaWNlcnQu\nY29tL0RpZ2lDZXJ0R2xvYmFsUm9vdENBLmNybDBMBgNVHSAERTBDMDcGCWCGSAGG\n/WwBAjAqMCgGCCsGAQUFBwIBFhxodHRwczovL3d3dy5kaWdpY2VydC5jb20vQ1BT\nMAgGBmeBDAECATANBgkqhkiG9w0BAQsFAAOCAQEAK3Gp6/aGq7aBZsxf/oQ+TD/B\nSwW3AU4ETK+GQf2kFzYZkby5SFrHdPomunx2HBzViUchGoofGgg7gHW0W3MlQAXW\nM0r5LUvStcr82QDWYNPaUy4taCQmyaJ+VB+6wxHstSigOlSNF2a6vg4rgexixeiV\n4YSB03Yqp2t3TeZHM9ESfkus74nQyW7pRGezj+TC44xCagCQQOzzNmzEAP2SnCrJ\nsNE2DpRVMnL8J6xBRdjmOsC3N6cQuKuRXbzByVBjCqAA8t1L0I+9wXJerLPyErjy\nrMKWaBFLmfK/AHNF4ZihwPGOc7w6UHczBZXH5RFzJNnww+WnKuTPI0HfnVH8lg==\n-----END CERTIFICATE-----");
            localMeta.putValue("keyPassword", "");
            TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
            config.setHostKey("${tcpHost}");
            config.setPortKey("${tcpPort}");
            config.setTlsKey("${tcpTls}");
            config.setPayloadType("TEXT");
            config.setResponseType("TEXT");
            config.getTlsConfig().setCaCertificateKey("${caPem}");
            config.getTlsConfig().setVerifyServerCertificate(true);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.valueToTree(config);
            org.thingsboard.rule.engine.api.TbNodeConfiguration tbNodeConfig = new org.thingsboard.rule.engine.api.TbNodeConfiguration(json);
            try {
                node.init(ctx, tbNodeConfig);
            } catch (org.thingsboard.rule.engine.api.TbNodeException e) {
                org.junit.jupiter.api.Assertions.fail("TbNodeException during init: " + e.getMessage());
            }
            msg = TbMsg.newMsg().data("{\"payload\":\"test over tls\"}").metaData(
                localMeta).originator(new DeviceId(UUID.randomUUID())).build();
            node.onMsg(ctx, msg);
            verify(ctx).tellSuccess(
                org.mockito.ArgumentMatchers.argThat(m -> {
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(m.getData());
                        return node.has("response") && "test over tls".equals(node.get("response").asText());
                    } catch (Exception e) {
                        return false;
                    }
                })
            );
        } finally {
            TbSendToTcpNode.clientFactory = originalFactory;
        }
    }

    @Test
    public void testTlsModeWithInvalidCerts() {
        TbMsgMetaData localMeta = new TbMsgMetaData();
        localMeta.putValue("tcpHost", "3.109.15.103");
        localMeta.putValue("tcpPort", "10550");
        localMeta.putValue("tcpTls", "true");
        localMeta.putValue("caPem", "invalid-ca");
        localMeta.putValue("certPem", "invalid-cert");
        localMeta.putValue("keyPem", "invalid-key");
        localMeta.putValue("keyPassword", "");
        TbSendToTcpNodeConfiguration config = new TbSendToTcpNodeConfiguration();
        config.setHostKey("${tcpHost}");
        config.setPortKey("${tcpPort}");
        config.setTlsKey("${tcpTls}");
        config.setPayloadType("TEXT");
        config.setResponseType("TEXT");
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
        when(msg.getData()).thenReturn("{\"payload\":\"test over tls\"}");
        node.onMsg(ctx, msg);
        verify(ctx).tellFailure(eq(msg), any(Exception.class));
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
