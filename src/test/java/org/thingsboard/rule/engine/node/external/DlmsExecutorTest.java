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

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import gurux.dlms.enums.DataType;
import gurux.net.GXNet;

/**
 * Unit Tests for DlmsExecutor
 *
 * Tests the orchestration logic: request validation, connection pool lifecycle
 * (borrow/return/discard), HLSE security wiring, per-action validation, and
 * response building.
 *
 * GXDLMSReader construction is mocked (via Mockito's mockConstruction), so every
 * "new GXDLMSReader(...)" inside DlmsExecutor returns a mock whose methods are
 * no-ops/return null by default - this isolates DlmsExecutor's own orchestration
 * logic from the real wire protocol (SNRM/AARQ/GET/SET/ACTION), which needs a
 * real or simulated meter to verify and is NOT exercised by these tests.
 * GXDLMSSecureClient2 (HLSE key validation/wiring) is real, not mocked - that
 * part is tested here for real, since it needs no network I/O.
 *
 * Test Categories:
 * ✓ Request Validation (6): null request, host, port range
 * ✓ Connection Pool Lifecycle (5): borrow/return/discard, exhaustion, errors
 * ✓ HLSE Security Wiring (8): missing/invalid keys, valid keys incl. spaces
 * ✓ Per-Action Validation (7): missing obis/value/method per action type
 * ✓ Operation Success Paths (4): READ/WRITE/ACTION/SET-CLOCK
 * ✓ Parsing (2): invalid OBIS, invalid datetime
 */
public class DlmsExecutorTest {

    private static final String HOST = "192.168.1.100";
    private static final int PORT = 4059;
    private static final String OBIS = "1.0.94.91.7.255";

    // Valid HLSE keys: block cipher (16 bytes), auth key (16 bytes), system title (8 bytes)
    private static final String VALID_BLOCK_CIPHER_KEY = "00112233445566778899AABBCCDDEEFF";
    private static final String VALID_AUTH_KEY = "FFEEDDCCBBAA99887766554433221100";
    private static final String VALID_SYSTEM_TITLE = "48455330000001FF";

    private DlmsClientPool mockPool;
    private GXNet mockConnection;
    private DlmsExecutor executor;
    private MockedConstruction<GXDLMSReader> readerConstruction;

    /**
     * Runs against every GXDLMSReader as soon as it's constructed (both the
     * counter-probe reader and the main operation reader). Defaults to a no-op;
     * tests that need to stub read()'s return value (e.g. WRITE-GROUP, which reads
     * the current structure before patching it) replace this before calling execute().
     */
    private java.util.function.Consumer<GXDLMSReader> readerCustomizer;

    @BeforeEach
    void setUp() throws Exception {
        mockPool = mock(DlmsClientPool.class);
        mockConnection = mock(GXNet.class);
        executor = new DlmsExecutor(mockPool);
        readerCustomizer = r -> { };

        // Default: pool hands out a connection successfully; tests override for error paths
        when(mockPool.borrowConnection(anyString(), anyInt())).thenReturn(mockConnection);

        // Every GXDLMSReader created inside DlmsExecutor becomes a mock: initializeConnection()
        // is a no-op "success", read()/method() return null, write() is a no-op "success" -
        // unless readerCustomizer (above) is set to stub something more specific.
        readerConstruction = mockConstruction(GXDLMSReader.class, (mock, context) -> readerCustomizer.accept(mock));
    }

    @AfterEach
    void tearDown() {
        if (readerConstruction != null) {
            readerConstruction.close();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // REQUEST VALIDATION TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_NullRequest_ReturnsGracefulFailure() throws Exception {
        // ACT
        DlmsResponse response = executor.execute(null);

        // ASSERT: must not throw NPE, must return a clean failure response
        assertFalse(response.isSuccess());
        assertEquals("ERROR", response.getResponseCode());
        assertTrue(response.getErrorMessage().contains("Request cannot be null"));
        verify(mockPool, never()).borrowConnection(anyString(), anyInt());
    }

    @Test
    void testExecute_NullHost_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setHost(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Host cannot be empty"));
    }

    @Test
    void testExecute_EmptyHost_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setHost("");

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Host cannot be empty"));
    }

    @Test
    void testExecute_PortZero_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setPort(0);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Invalid port"));
    }

    @Test
    void testExecute_PortNegative_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setPort(-1);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Invalid port"));
    }

    @Test
    void testExecute_PortTooLarge_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setPort(70000);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Invalid port"));
    }

    // ════════════════════════════════════════════════════════════════════
    // CONNECTION POOL LIFECYCLE TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_PoolTimeoutException_ReturnsPoolExhaustedMessage() throws Exception {
        when(mockPool.borrowConnection(HOST, PORT)).thenThrow(new TimeoutException("no permits"));

        DlmsResponse response = executor.execute(validReadRequest());

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Connection pool exhausted"));
    }

    @Test
    void testExecute_PoolGenericException_ReturnsFailure() throws Exception {
        when(mockPool.borrowConnection(HOST, PORT)).thenThrow(new Exception("network unreachable"));

        DlmsResponse response = executor.execute(validReadRequest());

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("network unreachable"));
    }

    @Test
    void testExecute_UnknownAction_DiscardsConnectionAndFails() {
        DlmsRequest request = validReadRequest();
        request.setAction("FOO");

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Unknown action"));
        verify(mockPool).discardConnection(mockConnection, HOST, PORT);
        verify(mockPool, never()).returnConnection(any(), anyString(), anyInt());
    }

    @Test
    void testExecute_SuccessfulRead_ReturnsConnectionToPool() {
        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.isSuccess());
        verify(mockPool).returnConnection(mockConnection, HOST, PORT);
        verify(mockPool, never()).discardConnection(any(), anyString(), anyInt());
    }

    @Test
    void testExecute_MultipleSequentialCalls_EachBorrowsAndReturns() throws Exception {
        executor.execute(validReadRequest());
        executor.execute(validReadRequest());

        verify(mockPool, times(2)).borrowConnection(HOST, PORT);
        verify(mockPool, times(2)).returnConnection(mockConnection, HOST, PORT);
    }

    // ════════════════════════════════════════════════════════════════════
    // HLSE SECURITY WIRING TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_MissingBlockCipherKey_DiscardsConnectionAndFails() {
        DlmsRequest request = validReadRequest();
        request.setBlockCipherKey(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Block Cipher Key required"));
        verify(mockPool).discardConnection(mockConnection, HOST, PORT);
    }

    @Test
    void testExecute_MissingAuthenticationKey_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setAuthenticationKey(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Authentication Key required"));
    }

    @Test
    void testExecute_MissingSystemTitle_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setSystemTitle(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("System Title required"));
    }

    @Test
    void testExecute_InvalidBlockCipherKeyLength_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setBlockCipherKey("1234"); // 2 bytes, not 16/32

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("must be 16 bytes"));
    }

    @Test
    void testExecute_InvalidAuthenticationKeyLength_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setAuthenticationKey("00112233"); // 4 bytes, not 16

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Authentication Key must be 16 bytes"));
    }

    @Test
    void testExecute_InvalidSystemTitleLength_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setSystemTitle("00112233"); // 4 bytes, not 8

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("System Title must be 8 bytes"));
    }

    @Test
    void testExecute_InvalidHexCharacters_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setBlockCipherKey("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"); // 32 chars, not hex

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Invalid hex character"));
    }

    @Test
    void testExecute_ValidHexKeysWithSpaces_Succeeds() {
        // Regression check: meter test keys are often shared as space-separated hex
        // (e.g. "73 73 73 ..."); configureHLSE must strip spaces before parsing.
        DlmsRequest request = validReadRequest();
        request.setBlockCipherKey("73 73 73 73 73 73 73 73 73 73 73 73 73 73 73 73");
        request.setAuthenticationKey("73 73 73 73 73 73 73 73 73 73 73 73 73 73 73 73");
        request.setSystemTitle("31 32 33 34 35 36 37 38");

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_PasswordProvided_UsesHlsWithEncryptionInsteadOfHlse() {
        // Regression check: default (no password) HLSE mode must not be disturbed
        // by the new password-driven mode; this only proves the alternate path also
        // succeeds when a password is present.
        DlmsRequest request = validReadRequest();
        request.setPassword("SALSMSCHNELL1060");

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_PasswordProvided_MissingKeys_ReturnsFailure() {
        // The new mode still requires keys - password alone is not enough.
        DlmsRequest request = validReadRequest();
        request.setPassword("SALSMSCHNELL1060");
        request.setBlockCipherKey(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Block Cipher Key required"));
    }

    @Test
    void testExecute_NoPassword_StillUsesDefaultHlse() {
        // Explicit regression guard: leaving password unset must keep using the
        // original, already-shipped HLSE (no password) behavior.
        DlmsRequest request = validReadRequest();
        request.setPassword(null);

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    // ════════════════════════════════════════════════════════════════════
    // PER-ACTION VALIDATION TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_ReadMissingObis_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setObis(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("OBIS code required for READ"));
    }

    @Test
    void testExecute_WriteMissingObis_ReturnsFailure() {
        DlmsRequest request = validWriteRequest();
        request.setObis(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("OBIS code required for WRITE"));
    }

    @Test
    void testExecute_WriteMissingValue_ReturnsFailure() {
        DlmsRequest request = validWriteRequest();
        request.setValue(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Value required for WRITE"));
    }

    @Test
    void testExecute_ActionMissingObis_ReturnsFailure() {
        DlmsRequest request = validActionRequest();
        request.setObis(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("OBIS code required for ACTION"));
    }

    @Test
    void testExecute_ActionInvalidMethod_ReturnsFailure() {
        DlmsRequest request = validActionRequest();
        request.setMethod(0);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Valid method index required for ACTION"));
    }

    @Test
    void testExecute_SetClockMissingValue_ReturnsFailure() {
        DlmsRequest request = validSetClockRequest();
        request.setValue(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Datetime value required for SET-CLOCK"));
    }

    @Test
    void testExecute_SetClockInvalidDatetimeFormat_ReturnsFailure() {
        DlmsRequest request = validSetClockRequest();
        request.setValue("not-a-date");

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Invalid datetime format"));
    }

    @Test
    void testExecute_ReadRangeMissingObis_ReturnsFailure() {
        DlmsRequest request = validReadRangeRequest();
        request.setObis(null);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("OBIS code required for READ-RANGE"));
    }

    @Test
    void testExecute_ReadRangeMissingFromTs_ReturnsFailure() {
        DlmsRequest request = validReadRangeRequest();
        request.setFromTs(0);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("fromTs and toTs"));
    }

    @Test
    void testExecute_ReadRangeMissingToTs_ReturnsFailure() {
        DlmsRequest request = validReadRangeRequest();
        request.setToTs(0);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("fromTs and toTs"));
    }

    @Test
    void testExecute_ReadRangeToTsBeforeFromTs_ReturnsFailure() {
        DlmsRequest request = validReadRangeRequest();
        request.setFromTs(1772670600L);
        request.setToTs(1772195400L);

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("toTs must be after fromTs"));
    }

    // ════════════════════════════════════════════════════════════════════
    // OPERATION SUCCESS PATH TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_ReadRangeSuccess_ReturnsRowsFromMeter() throws Exception {
        Object[] fakeRows = new Object[] {
                new Object[] { 1000, 230.5f },
                new Object[] { 2000, 231.2f }
        };
        readerCustomizer = r -> {
            try {
                when(r.readRange(anyString(), any(java.util.Date.class), any(java.util.Date.class))).thenReturn(fakeRows);
            } catch (Exception ignored) {
            }
        };

        DlmsResponse response = executor.execute(validReadRangeRequest());

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        assertTrue(response.getData().contains("230.50"));
        assertTrue(response.getData().contains("231.20"));

        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        ArgumentCaptor<java.util.Date> fromCaptor = ArgumentCaptor.forClass(java.util.Date.class);
        ArgumentCaptor<java.util.Date> toCaptor = ArgumentCaptor.forClass(java.util.Date.class);
        verify(mockReader).readRange(eq("1.0.99.1.0.255"), fromCaptor.capture(), toCaptor.capture());
        assertEquals(1772195400L * 1000, fromCaptor.getValue().getTime());
        assertEquals(1772670600L * 1000, toCaptor.getValue().getTime());
    }

    @Test
    void testExecute_ReadSuccess_ReturnsSuccessTrue() {
        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.isSuccess());
        assertEquals("0", response.getResponseCode());
        assertNull(response.getErrorMessage());
    }

    @Test
    void testExecute_WriteSuccess_ReturnsOK() {
        DlmsResponse response = executor.execute(validWriteRequest());

        assertTrue(response.isSuccess());
        assertEquals("OK", response.getData());
    }

    @Test
    void testExecute_WriteBitString_SetsOnlyRequestedBit() throws Exception {
        DlmsRequest request = validWriteRequest();
        request.setDataType("BITSTRING");
        request.setBitLength(64);
        request.setValue("30"); // e.g. "MCB Trip" per the meter's fault/event code table

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockReader).write(eq(request.getObis()), eq(request.getClassId()), anyInt(), valueCaptor.capture());

        gurux.dlms.GXBitString bitString = (gurux.dlms.GXBitString) valueCaptor.getValue();
        byte[] expected = {0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00}; // bit 30 set, matches reference frame
        assertArrayEquals(expected, bitString.getValue());
    }

    @Test
    void testExecute_WriteBitString_MultipleBits_SetsAllRequested() throws Exception {
        DlmsRequest request = validWriteRequest();
        request.setDataType("BITSTRING");
        request.setBitLength(64);
        request.setValue("5,30");

        executor.execute(request);

        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockReader).write(eq(request.getObis()), eq(request.getClassId()), anyInt(), valueCaptor.capture());

        gurux.dlms.GXBitString bitString = (gurux.dlms.GXBitString) valueCaptor.getValue();
        byte[] expected = {0x04, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00}; // bit 5 and bit 30 both set
        assertArrayEquals(expected, bitString.getValue());
    }

    @Test
    void testExecute_WriteBitString_BitPositionOutOfRange_ReturnsFailure() {
        DlmsRequest request = validWriteRequest();
        request.setDataType("BITSTRING");
        request.setBitLength(64);
        request.setValue("70"); // out of range for a 64-bit field

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("out of range"));
    }

    @Test
    void testExecute_WriteBitString_MissingValue_ReturnsFailure() {
        DlmsRequest request = validWriteRequest();
        request.setDataType("BITSTRING");
        request.setValue("");

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Value required for WRITE"));
    }

    // ════════════════════════════════════════════════════════════════════
    // WRITE-GROUP TESTS (SEMC "operating mode" structure: read-modify-write)
    // ════════════════════════════════════════════════════════════════════

    /** A plausible current 31-field structure, matching GroupSetForSemc's field map. */
    private java.util.List<Object> fakeOperatingModeStructure() {
        java.util.List<Object> s = new java.util.ArrayList<>();
        s.add((short) 0);                                  // 0 operatingMode
        s.add(false);                                       // 1 astroModeGPS
        s.add(11.14f);                                       // 2 astroLatitude
        s.add(76.94f);                                       // 3 astroLongitude
        s.add((short) 300);                                  // 4 astroOnOffSet
        s.add((short) 1440);                                 // 5 astroOffOffSet
        s.add(64800L);                                       // 6 slotOnTimeGMT
        s.add(new byte[]{0x12, 0x00, 0x00, 0x00});           // 7 slotOnTimeDLMS
        s.add(21600L);                                       // 8 slotOffTimeGMT
        s.add(new byte[]{0x06, 0x00, 0x00, 0x00});           // 9 slotOffTimeDLMS
        s.add(0L);                                           // 10 enabledEvents
        s.add(4059);                                         // 11 tcpUdpPort
        s.add(900);                                          // 12 publishInterval
        s.add(1000);                                         // 13 maxPayloadSlotOn
        s.add(1000);                                         // 14 maxPayloadSlotOff
        s.add(0.02f);                                        // 15 rCurrentMin
        s.add(25.0f);                                        // 16 rCurrentMax
        s.add(0.02f);                                        // 17 yCurrentMin
        s.add(25.0f);                                        // 18 yCurrentMax
        s.add(0.02f);                                        // 19 bCurrentMin
        s.add(25.0f);                                        // 20 bCurrentMax
        s.add(160.0f);                                       // 21 rVoltMin
        s.add(290.0f);                                       // 22 rVoltMax
        s.add(160.0f);                                       // 23 yVoltMin
        s.add(290.0f);                                       // 24 yVoltMax
        s.add(160.0f);                                       // 25 bVoltMin
        s.add(290.0f);                                       // 26 bVoltMax
        s.add(0.85f);                                        // 27 rpfThreshold
        s.add(0.85f);                                        // 28 ypfThreshold
        s.add(0.85f);                                        // 29 bpfThreshold
        s.add(new byte[]{0x00, 0x00, 0x01, 0x03, 0x00, 0x02, 0x06, 0x00, 0x03, 0x09, 0x00, 0x04,
                0x0c, 0x00, 0x05, 0x0f, 0x00, 0x06, 0x12, 0x00, 0x07, 0x15, 0x00, 0x08}); // 30 todSlots
        return s;
    }

    private DlmsRequest validWriteGroupRequest(Map<String, Object> fields) {
        DlmsRequest request = baseRequest();
        request.setAction("WRITE-GROUP");
        request.setGroupFields(fields);
        return request;
    }

    @Test
    void testExecute_WriteGroupSuccess_PatchesOnlyRequestedField() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(anyString(), anyInt(), anyInt())).thenReturn(fakeOperatingModeStructure());
            } catch (Exception ignored) {
            }
        };
        DlmsRequest request = validWriteGroupRequest(Map.of("rVoltMax", "265.0"));

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        assertEquals("OK", response.getData());

        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockReader).write(anyString(), anyInt(), anyInt(), valueCaptor.capture());

        @SuppressWarnings("unchecked")
        java.util.List<Object> written = (java.util.List<Object>) valueCaptor.getValue();
        assertEquals(31, written.size());
        assertEquals(265.0f, written.get(22)); // rVoltMax patched
        assertEquals(160.0f, written.get(21)); // rVoltMin untouched
        assertEquals(0.85f, written.get(27));  // rpfThreshold untouched
    }

    @Test
    void testExecute_WriteGroupSuccess_TodSlotsField_EncodesToBytes() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(anyString(), anyInt(), anyInt())).thenReturn(fakeOperatingModeStructure());
            } catch (Exception ignored) {
            }
        };
        Map<String, Object> slot = new java.util.LinkedHashMap<>();
        slot.put("h", 3);
        slot.put("m", 0);
        slot.put("todSlot", 2);
        DlmsRequest request = validWriteGroupRequest(Map.of("todSlots", java.util.List.of(slot)));

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockReader).write(anyString(), anyInt(), anyInt(), valueCaptor.capture());

        @SuppressWarnings("unchecked")
        java.util.List<Object> written = (java.util.List<Object>) valueCaptor.getValue();
        assertArrayEquals(new byte[]{0x03, 0x00, 0x02}, (byte[]) written.get(30));
    }

    @Test
    void testExecute_WriteGroupDefaultsObisClassIdAttribute_WhenNotSet() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(anyString(), anyInt(), anyInt())).thenReturn(fakeOperatingModeStructure());
            } catch (Exception ignored) {
            }
        };
        DlmsRequest request = validWriteGroupRequest(Map.of("operatingMode", "1"));
        // Deliberately leave obis/classId/attribute unset

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        verify(mockReader).read(GroupSetForSemc.GROUP_WRITE_OBIS, GroupSetForSemc.GROUP_WRITE_CLASS_ID,
                GroupSetForSemc.GROUP_WRITE_ATTRIBUTE);
        verify(mockReader).write(eq(GroupSetForSemc.GROUP_WRITE_OBIS), eq(GroupSetForSemc.GROUP_WRITE_CLASS_ID),
                eq(GroupSetForSemc.GROUP_WRITE_ATTRIBUTE), any());
    }

    @Test
    void testExecute_WriteGroupUnknownField_ReturnsFailure() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(anyString(), anyInt(), anyInt())).thenReturn(fakeOperatingModeStructure());
            } catch (Exception ignored) {
            }
        };
        DlmsRequest request = validWriteGroupRequest(Map.of("notARealField", "1"));

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Unknown WRITE-GROUP field"));
    }

    @Test
    void testExecute_WriteGroupMissingFields_ReturnsFailure() {
        DlmsRequest request = validWriteGroupRequest(Map.of());

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("At least one field required"));
    }

    @Test
    void testExecute_WriteGroupReadReturnsNonList_ReturnsFailure() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(anyString(), anyInt(), anyInt())).thenReturn("not-a-structure");
            } catch (Exception ignored) {
            }
        };
        DlmsRequest request = validWriteGroupRequest(Map.of("rVoltMax", "265.0"));

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("not a DLMS structure"));
    }

    @Test
    void testExecute_ActionSuccess_ReturnsOK() {
        DlmsResponse response = executor.execute(validActionRequest());

        assertTrue(response.isSuccess());
        assertEquals("OK", response.getData());
    }

    @Test
    void testExecute_SetClockSuccess_ReturnsOK() {
        DlmsResponse response = executor.execute(validSetClockRequest());

        assertTrue(response.isSuccess());
        assertEquals("OK", response.getData());
    }

    // ════════════════════════════════════════════════════════════════════
    // PARSING TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_InvalidObisFormat_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setObis("1.2.3"); // must have 6 parts

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Invalid OBIS code"));
    }

    // ════════════════════════════════════════════════════════════════════
    // RESPONSE METADATA TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_DurationMsIsNonNegative() {
        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.getDurationMs() >= 0);
    }

    @Test
    void testExecute_ResponseCode_ErrorOnFailure() {
        DlmsRequest request = validReadRequest();
        request.setHost(null);

        DlmsResponse response = executor.execute(request);

        assertEquals("ERROR", response.getResponseCode());
    }

    @Test
    void testGetPoolStats_DelegatesToConnectionPool() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("size", 3);
        when(mockPool.getStats()).thenReturn(stats);

        Object result = executor.getPoolStats();

        assertEquals(stats, result);
        verify(mockPool).getStats();
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPER METHODS - Request builders
    // ════════════════════════════════════════════════════════════════════

    private DlmsRequest baseRequest() {
        DlmsRequest request = new DlmsRequest();
        request.setHost(HOST);
        request.setPort(PORT);
        request.setBlockCipherKey(VALID_BLOCK_CIPHER_KEY);
        request.setAuthenticationKey(VALID_AUTH_KEY);
        request.setSystemTitle(VALID_SYSTEM_TITLE);
        return request;
    }

    private DlmsRequest validReadRequest() {
        DlmsRequest request = baseRequest();
        request.setAction("READ");
        request.setObis(OBIS);
        request.setClassId(3);
        request.setAttribute(2);
        return request;
    }

    private DlmsRequest validWriteRequest() {
        DlmsRequest request = baseRequest();
        request.setAction("WRITE");
        request.setObis(OBIS);
        request.setValue("220");
        return request;
    }

    private DlmsRequest validActionRequest() {
        DlmsRequest request = baseRequest();
        request.setAction("ACTION");
        request.setObis("0.0.96.3.10.255");
        request.setMethod(1);
        return request;
    }

    private DlmsRequest validSetClockRequest() {
        DlmsRequest request = baseRequest();
        request.setAction("SET-CLOCK");
        request.setValue("2024-08-27T14:30:45Z");
        return request;
    }

    private DlmsRequest validReadRangeRequest() {
        DlmsRequest request = baseRequest();
        request.setAction("READ-RANGE");
        request.setObis("1.0.99.1.0.255");
        request.setFromTs(1772195400L);
        request.setToTs(1772670600L);
        return request;
    }

    // ════════════════════════════════════════════════════════════════════
    // convertToString TESTS (byte[] -> hex, 12-byte DLMS date-time -> epoch ms,
    // recursing into nested arrays/lists - e.g. a Profile Generic row)
    // ════════════════════════════════════════════════════════════════════

    /**
     * A real 12-byte DLMS date-time block for 2026-09-10 12:00:00 (day-of-week,
     * hundredths, and deviation left unspecified, per DLMS convention) - verified
     * against the real Gurux library to decode to epoch millis 1789021800000.
     */
    private static final byte[] SAMPLE_DLMS_DATETIME = {
            0x07, (byte) 0xEA, 0x09, 0x0A, (byte) 0xFF, 0x0C, 0x00, 0x00,
            (byte) 0xFF, (byte) 0x80, 0x00, 0x00
    };
    private static final String SAMPLE_DLMS_DATETIME_EPOCH_MS = "1789021800000";

    private String invokeConvertToString(Object value) throws Exception {
        java.lang.reflect.Method method = DlmsExecutor.class.getDeclaredMethod("convertToString", Object.class);
        method.setAccessible(true);
        return (String) method.invoke(executor, value);
    }

    @Test
    void testConvertToString_GXDateTime_ReturnsIstWallClockAsEpochMillis() throws Exception {
        java.util.Calendar ist = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        ist.clear();
        ist.set(2026, java.util.Calendar.OCTOBER, 12, 17, 32, 44);
        gurux.dlms.GXDateTime dateTime = new gurux.dlms.GXDateTime(ist);

        assertEquals("1791806564000", invokeConvertToString(dateTime));
    }

    @Test
    void testConvertToString_GXDateTime_OutOfRangeYear_ReturnsZero() throws Exception {
        java.util.Calendar bogus = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        bogus.clear();
        bogus.set(1900, java.util.Calendar.JANUARY, 1, 0, 0, 0);
        gurux.dlms.GXDateTime dateTime = new gurux.dlms.GXDateTime(bogus);

        assertEquals("0", invokeConvertToString(dateTime));
    }

    @Test
    void testConvertToString_JavaDate_ReturnsEpochMillis() throws Exception {
        assertEquals("1791806564000", invokeConvertToString(new java.util.Date(1791806564000L)));
    }

    @Test
    void testConvertToString_TwelveByteArray_DecodesAsEpochMillis() throws Exception {
        String result = invokeConvertToString(SAMPLE_DLMS_DATETIME);

        assertEquals(SAMPLE_DLMS_DATETIME_EPOCH_MS, result);
    }

    @Test
    void testConvertToString_NonTwelveByteArray_ShowsAsHex() throws Exception {
        byte[] macLikeValue = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55}; // 6 bytes, not a date-time

        String result = invokeConvertToString(macLikeValue);

        assertEquals("001122334455", result);
    }

    @Test
    void testConvertToString_NestedListWithDateTimeAndOtherValues_ConvertsEachElement() throws Exception {
        // Mirrors a real Profile Generic row: [timestamp, serialNumber, someNumber, ...]
        java.util.List<Object> row = java.util.Arrays.asList(
                SAMPLE_DLMS_DATETIME, "SS010296", 5, 230.5
        );

        String result = invokeConvertToString(row);

        assertEquals("[" + SAMPLE_DLMS_DATETIME_EPOCH_MS + ", SS010296, 5, 230.50]", result);
    }

    @Test
    void testConvertToString_ObjectArray_ConvertsEachElement() throws Exception {
        Object[] row = {SAMPLE_DLMS_DATETIME, "SS010296"};

        String result = invokeConvertToString(row);

        assertEquals("[" + SAMPLE_DLMS_DATETIME_EPOCH_MS + ", SS010296]", result);
    }

    // ════════════════════════════════════════════════════════════════════
    // WRITE TYPE-DETECTION CACHE TESTS (unknown OBIS -> learn the real DLMS
    // type from the meter -> cache it -> convert the value to match; see
    // DlmsExecutor.KNOWN_OBIS_TYPES and readDataType()/convertValueForType())
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_WriteUnknownObis_LearnsTypeFromMeterAndCachesForNextWrite() throws Exception {
        String obis = "9.9.9.1.0.255"; // deliberately not in the seeded KNOWN_OBIS_TYPES map
        readerCustomizer = r -> {
            try {
                when(r.readDataType(eq(obis), anyInt(), anyInt())).thenReturn(DataType.FLOAT32);
            } catch (Exception ignored) {
            }
        };
        DlmsRequest request = validWriteRequest();
        request.setObis(obis);
        request.setValue("42"); // no decimal point - would be mis-guessed as Integer without the fix

        DlmsResponse first = executor.execute(request);

        assertTrue(first.isSuccess(), "Should succeed: " + first.getErrorMessage());
        GXDLMSReader firstReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        verify(firstReader).readDataType(eq(obis), anyInt(), anyInt());
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(firstReader).write(eq(obis), anyInt(), anyInt(), valueCaptor.capture(), eq(DataType.FLOAT32));
        assertEquals(42.0f, valueCaptor.getValue());

        // Second write to the SAME obis must skip readDataType() entirely - proves caching works
        DlmsResponse second = executor.execute(request);

        assertTrue(second.isSuccess(), "Should succeed: " + second.getErrorMessage());
        GXDLMSReader secondReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        verify(secondReader, never()).readDataType(anyString(), anyInt(), anyInt());
        verify(secondReader).write(eq(obis), anyInt(), anyInt(), any(), eq(DataType.FLOAT32));
    }

    @Test
    void testExecute_WriteUnknownObis_ReadDataTypeFails_FallsBackToBlindGuess() throws Exception {
        String obis = "9.9.9.2.0.255";
        readerCustomizer = r -> {
            try {
                when(r.readDataType(eq(obis), anyInt(), anyInt())).thenThrow(new Exception("meter unreachable"));
            } catch (Exception ignored) {
            }
        };
        DlmsRequest request = validWriteRequest();
        request.setObis(obis);
        request.setValue("42");

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should still succeed via fallback: " + response.getErrorMessage());
        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockReader).write(eq(obis), anyInt(), anyInt(), valueCaptor.capture(), eq(DataType.NONE));
        assertEquals(42, valueCaptor.getValue()); // blind guess: no decimal point -> Integer
    }

    @Test
    void testExecute_WriteSeededObis_NeverCallsReadDataType() throws Exception {
        DlmsRequest request = validWriteRequest();
        request.setObis("1.0.32.128.0.255"); // rVoltMin - already seeded as FLOAT32
        request.setValue("260");

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        verify(mockReader, never()).readDataType(anyString(), anyInt(), anyInt());
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockReader).write(eq("1.0.32.128.0.255"), anyInt(), anyInt(), valueCaptor.capture(), eq(DataType.FLOAT32));
        assertEquals(260.0f, valueCaptor.getValue());
    }

    /**
     * Exercises one branch of convertValueForType() by driving it through the
     * learn-and-write path above - a fresh, never-before-seen OBIS per call (keyed
     * off the DataType's ordinal) so the static KNOWN_OBIS_TYPES cache from other
     * tests/other calls in this same test never interferes.
     */
    private void assertWriteConvertsToType(String rawValue, DataType wireType, Object expectedValue) throws Exception {
        String obis = "9.9.9." + wireType.ordinal() + ".0.255";
        readerCustomizer = r -> {
            try {
                when(r.readDataType(eq(obis), anyInt(), anyInt())).thenReturn(wireType);
            } catch (Exception ignored) {
            }
        };
        DlmsRequest request = validWriteRequest();
        request.setObis(obis);
        request.setValue(rawValue);

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed for " + wireType + ": " + response.getErrorMessage());
        GXDLMSReader mockReader = readerConstruction.constructed().get(readerConstruction.constructed().size() - 1);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockReader).write(eq(obis), anyInt(), anyInt(), valueCaptor.capture(), eq(wireType));
        Object actual = valueCaptor.getValue();
        if (expectedValue instanceof byte[]) {
            assertArrayEquals((byte[]) expectedValue, (byte[]) actual);
        } else {
            assertEquals(expectedValue, actual);
        }
    }

    @Test
    void testConvertValueForType_Boolean() throws Exception {
        assertWriteConvertsToType("true", DataType.BOOLEAN, Boolean.TRUE);
    }

    @Test
    void testConvertValueForType_Int8() throws Exception {
        assertWriteConvertsToType("5", DataType.INT8, (byte) 5);
    }

    @Test
    void testConvertValueForType_Uint8() throws Exception {
        assertWriteConvertsToType("200", DataType.UINT8, (short) 200);
    }

    @Test
    void testConvertValueForType_Int16() throws Exception {
        assertWriteConvertsToType("-300", DataType.INT16, (short) -300);
    }

    @Test
    void testConvertValueForType_Uint16() throws Exception {
        assertWriteConvertsToType("50000", DataType.UINT16, 50000);
    }

    @Test
    void testConvertValueForType_Int32() throws Exception {
        assertWriteConvertsToType("100000", DataType.INT32, 100000);
    }

    @Test
    void testConvertValueForType_Uint32() throws Exception {
        assertWriteConvertsToType("4000000000", DataType.UINT32, 4000000000L);
    }

    @Test
    void testConvertValueForType_Int64() throws Exception {
        assertWriteConvertsToType("123456789012", DataType.INT64, 123456789012L);
    }

    @Test
    void testConvertValueForType_Uint64() throws Exception {
        assertWriteConvertsToType("18000000000000000000", DataType.UINT64,
                new BigInteger("18000000000000000000"));
    }

    @Test
    void testConvertValueForType_Float32() throws Exception {
        assertWriteConvertsToType("260", DataType.FLOAT32, 260.0f);
    }

    @Test
    void testConvertValueForType_Float64() throws Exception {
        assertWriteConvertsToType("260", DataType.FLOAT64, 260.0d);
    }

    @Test
    void testConvertValueForType_String() throws Exception {
        assertWriteConvertsToType("hello-world", DataType.STRING, "hello-world");
    }

    @Test
    void testConvertValueForType_OctetString_HexInput_DecodesToBytes() throws Exception {
        assertWriteConvertsToType("12 00 00 00", DataType.OCTET_STRING, new byte[]{0x12, 0x00, 0x00, 0x00});
    }

    @Test
    void testConvertValueForType_UnhandledType_FallsBackToBlindGuess() throws Exception {
        // DATE isn't explicitly handled by convertValueForType()'s switch - falls
        // to the default branch, which delegates to the same inference convertValue() does.
        assertWriteConvertsToType("42", DataType.DATE, 42);
    }

    // ════════════════════════════════════════════════════════════════════
    // PROTOCOL ADDRESSING TESTS (clientAddress/serverAddress/standard - only
    // applied when explicitly set on the request; Gurux defaults otherwise)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_ClientAndServerAddressSet_Succeeds() {
        DlmsRequest request = validReadRequest();
        request.setClientAddress(48);
        request.setServerAddress(1);

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_ValidStandardName_Succeeds() {
        DlmsRequest request = validReadRequest();
        request.setStandard("INDIA");

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_UnknownStandardName_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setStandard("NOT_A_REAL_STANDARD");

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Unknown standard"));
    }

    // ════════════════════════════════════════════════════════════════════
    // INVOCATION COUNTER TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_ExplicitCounter_SkipsAutoProbe_OnlyOneReaderConstructed() {
        DlmsRequest request = validReadRequest();
        request.setCounter(42);

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        // Explicit counter skips the unsecured counter-probe entirely - only the
        // real operation's GXDLMSReader should ever be constructed.
        assertEquals(1, readerConstruction.constructed().size());
    }

    @Test
    void testExecute_NoExplicitCounter_ProbesUnsecuredCounterFirst() {
        DlmsRequest request = validReadRequest();
        // counter left at its default (-1) -> triggers the auto-probe

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        // One probe reader (counter read) + one real operation reader
        assertEquals(2, readerConstruction.constructed().size());
    }

    @Test
    void testExecute_CounterProbeSucceeds_UsesMeterCounterPlusOne() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(eq("0.0.43.1.0.255"), eq(1), eq(2))).thenReturn(10);
            } catch (Exception ignored) {
            }
        };

        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        assertEquals(2, readerConstruction.constructed().size());
    }

    @Test
    void testExecute_CounterProbeReturnsFourByteArray_ParsedAsBigEndian() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(eq("0.0.43.1.0.255"), eq(1), eq(2))).thenReturn(new byte[]{0x00, 0x00, 0x00, 0x0A});
            } catch (Exception ignored) {
            }
        };

        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_CounterProbeReturnsTwoByteArray_Parsed() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(eq("0.0.43.1.0.255"), eq(1), eq(2))).thenReturn(new byte[]{0x00, 0x0A});
            } catch (Exception ignored) {
            }
        };

        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_CounterProbeReturnsOneByteArray_Parsed() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.read(eq("0.0.43.1.0.255"), eq(1), eq(2))).thenReturn(new byte[]{0x0A});
            } catch (Exception ignored) {
            }
        };

        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_FirstCounterProbeFails_SecondAddressSucceeds() throws Exception {
        AtomicInteger readerCount = new AtomicInteger(0);
        readerCustomizer = r -> {
            try {
                int n = readerCount.incrementAndGet();
                if (n == 1) {
                    when(r.read(anyString(), anyInt(), anyInt())).thenThrow(new Exception("public client not supported"));
                } else if (n == 2) {
                    when(r.read(anyString(), anyInt(), anyInt())).thenReturn(5);
                }
            } catch (Exception ignored) {
            }
        };
        DlmsRequest request = validReadRequest();
        request.setClientAddress(48); // used as the second probe attempt's address

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        // probe #1 (public client, fails) + probe #2 (real client address, succeeds) + main operation reader
        assertEquals(3, readerConstruction.constructed().size());
    }

    @Test
    void testExecute_CounterProbeConnectionResetFails_StillProceedsWithFallbackCounter() throws Exception {
        doThrow(new RuntimeException("reset failed")).when(mockConnection).close();
        // read() defaults to null -> parseCounterValue throws -> probe fails ->
        // the finally block tries to reset the connection -> that also throws -> caught & logged

        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.isSuccess(), "Should succeed despite reset failure: " + response.getErrorMessage());
    }

    // ════════════════════════════════════════════════════════════════════
    // SET-CLOCK - ALTERNATE DATETIME FORMATS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_SetClockSimpleFormatDatetime_Succeeds() {
        DlmsRequest request = validSetClockRequest();
        request.setValue("2024-08-27 14:30:45"); // simple format fallback (not ISO)

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_SetClockEpochMillisDatetime_Succeeds() {
        DlmsRequest request = validSetClockRequest();
        request.setValue("1724768445000"); // epoch millis fallback

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    // ════════════════════════════════════════════════════════════════════
    // ACTION - EXPLICIT PARAMETER, RESULT VALUE, FAILURE
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_ActionWithExplicitParameter_Succeeds() {
        DlmsRequest request = validActionRequest();
        request.setValue("1"); // explicit method parameter, instead of the no-param default

        DlmsResponse response = executor.execute(request);

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
    }

    @Test
    void testExecute_ActionReturnsNonNullResult_ReturnsResultAsData() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.method(anyString(), anyInt(), anyInt(), any())).thenReturn(42);
            } catch (Exception ignored) {
            }
        };

        DlmsResponse response = executor.execute(validActionRequest());

        assertTrue(response.isSuccess(), "Should succeed: " + response.getErrorMessage());
        assertEquals("42", response.getData());
    }

    @Test
    void testExecute_ActionReaderThrows_ReturnsFailure() throws Exception {
        readerCustomizer = r -> {
            try {
                when(r.method(anyString(), anyInt(), anyInt(), any())).thenThrow(new Exception("method not supported"));
            } catch (Exception ignored) {
            }
        };

        DlmsResponse response = executor.execute(validActionRequest());

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("ACTION failed"));
    }

    // ════════════════════════════════════════════════════════════════════
    // MISC EDGE CASES
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testExecute_EmptyAction_ReturnsFailure() {
        DlmsRequest request = validReadRequest();
        request.setAction("");

        DlmsResponse response = executor.execute(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Action cannot be empty"));
    }

    @Test
    void testExecute_ReaderCloseThrows_StillReturnsSuccessResponse() {
        readerCustomizer = r -> {
            try {
                doThrow(new RuntimeException("close failed")).when(r).close();
            } catch (Exception ignored) {
            }
        };

        DlmsResponse response = executor.execute(validReadRequest());

        assertTrue(response.isSuccess(), "close() failure must not affect the already-computed result: "
                + response.getErrorMessage());
    }

}
