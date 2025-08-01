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
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(localMeta);
        when(msg.getData()).thenReturn("{\"payload\":\"string\"}");
        node.onMsg(ctx, msg);
        verify(ctx).tellNext(
            org.mockito.ArgumentMatchers.argThat(m ->
                m.getData().equals("{\"payload\":\"string\"}") &&
                "string".equals(m.getMetaData().getValue("tcpResponse"))
            ),
            eq(org.thingsboard.server.common.data.msg.TbNodeConnectionType.SUCCESS)
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
        when(msg.getData()).thenReturn("{\"payload\":\"{\\\"key\\\":\\\"value\\\"}\"}");
        node.onMsg(ctx, msg);
        verify(ctx).tellNext(
            org.mockito.ArgumentMatchers.argThat(m ->
                (!m.getData().isEmpty() &&
                !m.getMetaData().getData().isEmpty())
            ),
            eq(org.thingsboard.server.common.data.msg.TbNodeConnectionType.SUCCESS)
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
        when(msg.getDataType()).thenReturn(TbMsgDataType.BINARY);
/* Test Data
|--------------------- Byte Array -----------------------|----------- Base64 ---------|
|7A A7 00 0D C0 01 C1 00 07 01 00 5E 5B 00 FF 02 00      |eqcADcABwQAHAQBeWwD/AgA=    | GIDP
|7A A7 00 0D C0 01 C1 00 07 01 00 5E 5B 85 FF 02 00      |eqcADcABwQAHAQBeW4X/AgA=    | GADP
|7A A7 00 0D C0 01 C1 00 07 01 00 63 02 00 FF 02 00      |eqcADcABwQAHAQBjAgD/AgA=    | GMRP
|7A A7 00 0F C3 01 C1 00 46 00 00 60 03 0A FF 01 01 0F 00|eqcAD8MBwQAoAAcZCQD/AQEPAA==| RDCON
|7A A7 00 0F C3 01 C1 00 46 00 00 60 03 0A FF 02 01 0F 00|eqcAD8MBwQBGAABgAwr/AgEPAA==| RCON
|7A A7 00 0F C3 01 C1 00 28 00 07 19 09 00 FF 01 01 0F 00|eqcAD8MBwQAoAAcZCQD/AQEPAA==| GAIDP
|7A A7 00 0F C3 01 C1 00 28 00 85 19 09 00 FF 01 01 0F 00|eqcAD8MBwQAoAIUZCQD/AQEPAA==| GAADP
|7A A7 00 0F C3 01 C1 00 28 00 06 19 09 00 FF 01 01 0F 00|eqcAD8MBwQAoAAYZCQD/AQEPAA==| GAMRP
|7A A7 00 0F C3 01 C1 00 28 00 04 19 09 00 FF 01 01 0F 00|eqcAD8MBwQAoAAQZCQD/AQEPAA==| GAEDP
|--------------------------------------------------------|----------------------------|
*/
        String base64Data = "{\"payload\":\"eqcADcABwQAHAQBeWwD/AgA=\"}";
        when(msg.getData()).thenReturn(base64Data);
        node.onMsg(ctx, msg);
        verify(ctx).tellNext(
            org.mockito.ArgumentMatchers.argThat(m ->
                !m.getData().isEmpty()
            ),
            eq(org.thingsboard.server.common.data.msg.TbNodeConnectionType.SUCCESS)
        );
    }

    @Test
    public void testTlsModeWithValidCerts() {
        TbMsgMetaData localMeta = new TbMsgMetaData();
        localMeta.putValue("tcpHost", "3.109.15.103");
        localMeta.putValue("tcpPort", "10550");
        localMeta.putValue("tcpTls", "true");
        localMeta.putValue("caPem", "-----BEGIN CERTIFICATE-----\n" + //
                        "MIIFuzCCA6OgAwIBAgIUc6FpyReDgyP+IqhhxiHziGF0BqswDQYJKoZIhvcNAQEM\n" + //
                        "BQAwbTELMAkGA1UEBhMCSU4xCzAJBgNVBAgMAlROMRMwEQYDVQQHDApDb2ltYmF0\n" + //
                        "b3JlMRIwEAYDVQQKDAlTY2hlbGwtQ0ExCzAJBgNVBAsMAklUMRswGQYDVQQDDBJT\n" + //
                        "Y2huZWxsLVByaXZhdGUtQ0EwHhcNMjUwNzMwMDk0ODU5WhcNMzUwNzI4MDk0ODU5\n" + //
                        "WjBtMQswCQYDVQQGEwJJTjELMAkGA1UECAwCVE4xEzARBgNVBAcMCkNvaW1iYXRv\n" + //
                        "cmUxEjAQBgNVBAoMCVNjaGVsbC1DQTELMAkGA1UECwwCSVQxGzAZBgNVBAMMElNj\n" + //
                        "aG5lbGwtUHJpdmF0ZS1DQTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIB\n" + //
                        "ALho/FJg3k1WSI2mZfJVDV6jT40Oqki+DbcekTZNc24dNTByzaRXGjO4iXfLWM15\n" + //
                        "WJ+VA78g0rnXPa2odJqvXcaY/Haz/xYLf5C1Ycq4o1t2lrh0plrp66HTUAt8X8qw\n" + //
                        "x0BWcjqbZQ3CR3oBWwwbA4Q3Bgizvra7C9Il1oJcjKZW6KFM4Ta1b7EMeWAulH08\n" + //
                        "fNNV2Y9HhyQF2jlH7lRUoEAk2t/SmXhrlSb3ErY2R8/sfAznjUxOBLkaIAFfO/qK\n" + //
                        "YfDkAc1BEajEiV61L2txt/kFJVMcRqskNsW1uk1x2WPDX28XyIfYr7NyZcmB0Hb6\n" + //
                        "vUWX2Fg3DSo/urG0D9IdeeU9nhBsWcehOZhrwqeRstBMSLkIKAtGSFa62jgtBu8H\n" + //
                        "0vDWkFZANPJKTdR7oZK9YXN39bKRl4lO29XDJ0AThNFd8sMgeRkok8YugdKn1auM\n" + //
                        "HfQTh5wR6ww0jx35HJAUenU9F2HdKTJggF2RM7ACyyHZFu0v4zMsbs9ZSU3sTnQl\n" + //
                        "pevJs3MeFDOsuox76TB6KxZ+0xKg2lBmvlJrKzw9HbROYdRmCUh/eVO2YmSpmTh7\n" + //
                        "UP0IXyC+QoWTRa5iRRkZHhKvTHTpf3vnduF3o4PpUe857LBvZkaOm0B2FnHbXLxC\n" + //
                        "HQ8vNmgMV6sNalxhdTGcKSveenlDJRv3IfKb9I2wIuFFAgMBAAGjUzBRMB0GA1Ud\n" + //
                        "DgQWBBQ9QRxtBtfo8j39OzgBOykK1qsLkDAfBgNVHSMEGDAWgBQ9QRxtBtfo8j39\n" + //
                        "OzgBOykK1qsLkDAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBDAUAA4ICAQCe\n" + //
                        "G5zLSxKs78bRdrex/XXGupMJ2ELjH/FG5Y/TWGh8eRTkDbYD2LD7k4NtSx3ai8Pi\n" + //
                        "GN9AtuHkd3OD1ZLC3mj/OmALKs90yeSyrSWcjMLZ4EI2dvz844BMOFufWA1hLCAc\n" + //
                        "JZoiaUhaZSEld5yrT+F18N1vwWVu/ctVimiYQYVlG6DozT2tRJqbXKDrkbLnbabx\n" + //
                        "l0ubv+qCk20nGaX/fuSTeYodS8pmZl7Y1665Wwc2It7W7Hs4PJQH3rYQ1sowpxmz\n" + //
                        "E3ayFQPO2vs7kC+Dk12L48WqLu10iMLwU3pxFAH74yAmt5Aec5cBtyvpybYKsg3z\n" + //
                        "zqfvzTkYJ5iYHvXw42bAwcU3JVbavHSu94tXfeVunRj+MNG8GY6tJ6OdH241/izZ\n" + //
                        "224t5C3Lvw4BD/msybyrT5H0jJGp+ArFHIUMHPpOS4IZBsyrOaIDbNeM069Wt4G3\n" + //
                        "7lZJElprFpefTHv9St2Gy8u3tzRyK4HKsUr/8bCoX+6Q8ofVupBollx+jCxBGmiM\n" + //
                        "2P8ewMidIFW12PzPqmHcUJDd1RU2eR//3kAJtk4flhLG5JJU1QI5flC+bFWrPy63\n" + //
                        "XTmNRTiZQ0DY5upZW9AdNFCMLLRXLT/yz6uF7y/HFo1Fa0fN/6oMMoPcV10bHLkm\n" + //
                        "Qy9WsJaDbCoQ31lg8IpuxFxdGyk4r06rdHczDR6oMA==\n" + //
                        "-----END CERTIFICATE-----");
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
        msg = mock(TbMsg.class);
        when(msg.getMetaData()).thenReturn(localMeta);
        when(msg.getData()).thenReturn("{\"payload\":\"test over tls\"}");
        node.onMsg(ctx, msg);
        verify(ctx).tellNext(
            org.mockito.ArgumentMatchers.argThat(m ->
                m.getData().equals("test over tls") &&
                !m.getData().isEmpty()
            ),
            eq(org.thingsboard.server.common.data.msg.TbNodeConnectionType.SUCCESS)
        );
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
