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
 * Unit Tests for DlmsResponse
 * Tests: Success/failure responses, data handling, helper methods
 */
public class DlmsResponseTest {

    @Test
    public void testSuccessResponse() {
        DlmsResponse response = new DlmsResponse();
        response.setSuccess(true);
        response.setResponseCode("0");
        response.setData("1234567");
        response.setErrorMessage(null);
        response.setDurationMs(523);

        assertTrue("Should be success", response.isSuccess());
        assertEquals("Response code", "0", response.getResponseCode());
        assertEquals("Data", "1234567", response.getData());
        assertNull("No error message", response.getErrorMessage());
        assertEquals("Duration", 523, response.getDurationMs());
    }

    @Test
    public void testErrorResponse() {
        DlmsResponse response = new DlmsResponse();
        response.setSuccess(false);
        response.setResponseCode("ERROR");
        response.setData(null);
        response.setErrorMessage("Connection timeout");
        response.setDurationMs(35000);

        assertFalse("Should be failure", response.isSuccess());
        assertEquals("Response code", "ERROR", response.getResponseCode());
        assertNull("No data", response.getData());
        assertEquals("Error message", "Connection timeout", response.getErrorMessage());
    }

    @Test
    public void testGetResultOnSuccess() {
        DlmsResponse response = new DlmsResponse();
        response.setSuccess(true);
        response.setData("12345");

        assertEquals("Should return data on success", "12345", response.getResult());
    }

    @Test
    public void testGetResultOnFailure() {
        DlmsResponse response = new DlmsResponse();
        response.setSuccess(false);
        response.setErrorMessage("Failed");

        assertEquals("Should return error on failure", "Failed", response.getResult());
    }

    @Test
    public void testDurationTracking() {
        DlmsResponse response = new DlmsResponse();
        response.setDurationMs(1523);

        assertEquals("Should track duration", 1523, response.getDurationMs());
    }

    @Test
    public void testConstructorWithAllArgs() {
        DlmsResponse response = new DlmsResponse(
            true, "0", "100", null, 500
        );

        assertTrue("Success flag", response.isSuccess());
        assertEquals("Response code", "0", response.getResponseCode());
        assertEquals("Data", "100", response.getData());
        assertNull("Error message", response.getErrorMessage());
        assertEquals("Duration", 500, response.getDurationMs());
    }

    @Test
    public void testWriteOperationResponse() {
        DlmsResponse response = new DlmsResponse();
        response.setSuccess(true);
        response.setResponseCode("0");
        response.setData(null); // WRITE doesn't return data
        response.setErrorMessage(null);

        assertTrue("WRITE should succeed", response.isSuccess());
        assertNull("WRITE doesn't return data", response.getData());
    }
}
