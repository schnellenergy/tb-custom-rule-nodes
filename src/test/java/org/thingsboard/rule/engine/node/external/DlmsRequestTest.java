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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit Tests for DlmsRequest
 * Tests: Data validation, getters/setters, defaults
 */
public class DlmsRequestTest {

    @Test
    public void testDlmsRequestCreation() {
        DlmsRequest request = new DlmsRequest();
        assertNotNull("Request should be created", request);
    }

    @Test
    public void testSettersAndGetters() {
        DlmsRequest request = new DlmsRequest();

        request.setHost("192.168.1.100");
        request.setPort(4059);
        request.setAction("READ");
        request.setObis("1.0.94.91.7.255");
        request.setClassId(3);
        request.setAttribute(2);
        request.setBlockCipherKey("D0D1D2D3D4D5D6D7D8D9DADBDCDDDEDFE");
        request.setAuthenticationKey("000102030405060708090A0B0C0D0E0F");
        request.setSystemTitle("48455300000001");
        request.setCounter("1001");
        request.setTimeout(30);

        assertEquals("Host should match", "192.168.1.100", request.getHost());
        assertEquals("Port should match", 4059, request.getPort());
        assertEquals("Action should match", "READ", request.getAction());
        assertEquals("OBIS should match", "1.0.94.91.7.255", request.getObis());
        assertEquals("ClassId should match", 3, request.getClassId());
        assertEquals("Attribute should match", 2, request.getAttribute());
        assertEquals("BlockCipherKey should match", "D0D1D2D3D4D5D6D7D8D9DADBDCDDDEDFE", request.getBlockCipherKey());
        assertEquals("AuthenticationKey should match", "000102030405060708090A0B0C0D0E0F", request.getAuthenticationKey());
        assertEquals("SystemTitle should match", "48455300000001", request.getSystemTitle());
        assertEquals("Counter should match", "1001", request.getCounter());
        assertEquals("Timeout should match", 30, request.getTimeout());
    }

    @Test
    public void testDefaultTimeout() {
        DlmsRequest request = new DlmsRequest();
        assertEquals("Default timeout should be 30", 30, request.getTimeout());
    }

    @Test
    public void testAllOperationTypes() {
        String[] operations = {"READ", "WRITE", "ACTION", "SET-CLOCK", "WRITE-GROUP"};

        for (String op : operations) {
            DlmsRequest request = new DlmsRequest();
            request.setAction(op);
            assertEquals("Should support operation: " + op, op, request.getAction());
        }
    }

    @Test
    public void testSecurityFieldsForHLSE() {
        DlmsRequest request = new DlmsRequest();
        request.setHost("192.168.1.1");
        request.setPort(4059);
        request.setAction("READ");
        request.setBlockCipherKey("D0D1D2D3D4D5D6D7D8D9DADBDCDDDEDFE");
        request.setAuthenticationKey("000102030405060708090A0B0C0D0E0F");
        request.setSystemTitle("48455300000001");
        request.setCounter("1");

        assertEquals("Host", "192.168.1.1", request.getHost());
        assertEquals("Port", 4059, request.getPort());
        assertEquals("Action", "READ", request.getAction());
        assertNotNull("BlockCipherKey must be set", request.getBlockCipherKey());
        assertNotNull("AuthenticationKey must be set", request.getAuthenticationKey());
        assertNotNull("SystemTitle must be set", request.getSystemTitle());
    }

    @Test
    public void testWriteOperation() {
        DlmsRequest request = new DlmsRequest();
        request.setAction("WRITE");
        request.setValue("12345");

        assertEquals("Should support WRITE action", "WRITE", request.getAction());
        assertEquals("Should have value", "12345", request.getValue());
    }

    @Test
    public void testActionOperation() {
        DlmsRequest request = new DlmsRequest();
        request.setAction("ACTION");
        request.setMethod(1);

        assertEquals("Should support ACTION", "ACTION", request.getAction());
        assertEquals("Method should be set", 1, request.getMethod());
    }
}
