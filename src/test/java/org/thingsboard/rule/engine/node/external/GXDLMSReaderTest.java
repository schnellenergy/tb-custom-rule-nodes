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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import gurux.common.IGXMedia;
import gurux.common.ReceiveParameters;
import gurux.dlms.GXReplyData;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.DataType;
import gurux.dlms.enums.InterfaceType;
import gurux.dlms.enums.ObjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit Tests for GXDLMSReader
 *
 * This class drives the actual wire conversation with the meter (send/receive,
 * association handshake, GET/SET/ACTION). Both the transport (IGXMedia) and the
 * DLMS client (GXDLMSSecureClient2) are mocked, so these tests verify GXDLMSReader's
 * own orchestration - which Gurux calls it makes, in what order, with what data -
 * without needing a real network or a real meter. The actual byte-level protocol
 * correctness (framing, encryption, GMAC) is Gurux's own responsibility and can
 * only be finally verified against a real or simulated meter.
 *
 * Test Categories:
 * ✓ readDLMSPacket (6): no-op on empty input, send/receive, timeout, wait time
 * ✓ initializeConnection (3): WRAPPER vs HDLC, AARQ multi-frame
 * ✓ read/write/method (7): classId->ObjectType mapping, error handling, parameters
 * ✓ close (2): closes open media, no-op if already closed
 */
public class GXDLMSReaderTest {

    private IGXMedia mockMedia;
    private GXDLMSSecureClient2 mockClient;
    private GXDLMSReader reader;

    @BeforeEach
    void setUp() throws Exception {
        mockMedia = mock(IGXMedia.class);
        mockClient = mock(GXDLMSSecureClient2.class);
        reader = new GXDLMSReader(mockClient, mockMedia);

        // Required by readDLMSPacket's "synchronized (media.getSynchronous())" - a mocked
        // Object-returning method defaults to null, which would NPE on synchronized(null).
        when(mockMedia.getSynchronous()).thenReturn(new Object());
    }

    // ════════════════════════════════════════════════════════════════════
    // readDLMSPacket TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testReadDLMSPacket_NullData_NoOp() throws Exception {
        GXReplyData reply = new GXReplyData();

        reader.readDLMSPacket(null, reply);

        verify(mockMedia, never()).send(any(), any());
        verify(mockMedia, never()).receive(any());
    }

    @Test
    void testReadDLMSPacket_EmptyData_NoOp() throws Exception {
        GXReplyData reply = new GXReplyData();

        reader.readDLMSPacket(new byte[0], reply);

        verify(mockMedia, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadDLMSPacket_SendsRequestAndWaitsForReply() throws Exception {
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);
        byte[] request = {0x01, 0x02, 0x03};
        GXReplyData reply = new GXReplyData();

        reader.readDLMSPacket(request, reply);

        verify(mockMedia).send(request, null);
        verify(mockMedia).receive(any(ReceiveParameters.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadDLMSPacket_MeterDoesNotRespond_ThrowsClearException() throws Exception {
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(false);
        GXReplyData reply = new GXReplyData();

        Exception ex = assertThrows(Exception.class,
                () -> reader.readDLMSPacket(new byte[]{0x01}, reply));
        assertTrue(ex.getMessage().contains("did not respond"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadDLMSPacket_DefaultWaitTime_Is5000ms() throws Exception {
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);
        GXReplyData reply = new GXReplyData();

        reader.readDLMSPacket(new byte[]{0x01}, reply);

        ArgumentCaptor<ReceiveParameters> captor = ArgumentCaptor.forClass(ReceiveParameters.class);
        verify(mockMedia).receive(captor.capture());
        assertEquals(5000, captor.getValue().getWaitTime());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadDLMSPacket_CustomWaitTime_IsUsed() throws Exception {
        reader.setWaitTime(9999);
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);
        GXReplyData reply = new GXReplyData();

        reader.readDLMSPacket(new byte[]{0x01}, reply);

        ArgumentCaptor<ReceiveParameters> captor = ArgumentCaptor.forClass(ReceiveParameters.class);
        verify(mockMedia).receive(captor.capture());
        assertEquals(9999, captor.getValue().getWaitTime());
    }

    // ════════════════════════════════════════════════════════════════════
    // WRAPPER TRANSPORT TESTS - real meter over plain TCP has no end-marker byte;
    // every reply starts with an 8-byte header whose last 2 bytes give the payload
    // length, so a complete reply takes two receive() calls: header, then payload.
    // ════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void testReadDLMSPacket_WrapperMode_ZeroLengthPayload_SingleReceiveCall() throws Exception {
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        // 8-byte header, last 2 bytes (payload length) = 0
        when(mockMedia.receive(any(ReceiveParameters.class))).thenAnswer(invocation -> {
            ReceiveParameters<byte[]> p = invocation.getArgument(0);
            p.setReply(new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
            return true;
        });
        GXReplyData reply = new GXReplyData();

        reader.readDLMSPacket(new byte[]{0x01}, reply);

        verify(mockMedia, times(1)).receive(any(ReceiveParameters.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadDLMSPacket_WrapperMode_NonZeroPayload_ReadsHeaderThenExactPayloadLength() throws Exception {
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        // Header says 3 bytes of payload follow
        byte[] header = {0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03};
        byte[] payload = {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC};

        ArgumentCaptor<ReceiveParameters> captor = ArgumentCaptor.forClass(ReceiveParameters.class);
        when(mockMedia.receive(captor.capture())).thenAnswer(invocation -> {
            ReceiveParameters<byte[]> p = invocation.getArgument(0);
            p.setReply(p.getCount() == 8 ? header : payload);
            return true;
        });
        GXReplyData reply = new GXReplyData();

        reader.readDLMSPacket(new byte[]{0x01}, reply);

        verify(mockMedia, times(2)).receive(any(ReceiveParameters.class));
        // First call asked for exactly the 8-byte header, second for exactly 3 payload bytes
        assertEquals(8, captor.getAllValues().get(0).getCount());
        assertEquals(3, captor.getAllValues().get(1).getCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadDLMSPacket_WrapperMode_HeaderTimeout_ThrowsException() throws Exception {
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(false);
        GXReplyData reply = new GXReplyData();

        Exception ex = assertThrows(Exception.class,
                () -> reader.readDLMSPacket(new byte[]{0x01}, reply));
        assertTrue(ex.getMessage().contains("wrapper header"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadDLMSPacket_WrapperMode_PayloadTimeout_ThrowsException() throws Exception {
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        byte[] header = {0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03};
        when(mockMedia.receive(any(ReceiveParameters.class))).thenAnswer(invocation -> {
            ReceiveParameters<byte[]> p = invocation.getArgument(0);
            if (p.getCount() == 8) {
                p.setReply(header);
                return true;
            }
            return false; // payload never arrives
        });
        GXReplyData reply = new GXReplyData();

        Exception ex = assertThrows(Exception.class,
                () -> reader.readDLMSPacket(new byte[]{0x01}, reply));
        assertTrue(ex.getMessage().contains("wrapper payload"));
    }

    // ════════════════════════════════════════════════════════════════════
    // initializeConnection TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void testInitializeConnection_WrapperInterface_SkipsSnrmHandshake() throws Exception {
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        when(mockClient.getAuthentication()).thenReturn(Authentication.NONE);
        when(mockClient.aarqRequest()).thenReturn(new byte[][]{{0x01}});
        stubWrapperHeaderReceive();

        reader.initializeConnection();

        verify(mockClient, never()).snrmRequest();
        verify(mockClient, never()).parseUAResponse(any(byte[].class));
        verify(mockClient).aarqRequest();
        verify(mockClient).parseAareResponse(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testInitializeConnection_HdlcInterface_SendsSnrmBeforeAarq() throws Exception {
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.HDLC);
        when(mockClient.getAuthentication()).thenReturn(Authentication.NONE);
        when(mockClient.snrmRequest()).thenReturn(new byte[]{0x7E, 0x01});
        when(mockClient.aarqRequest()).thenReturn(new byte[][]{{0x02}});
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);

        reader.initializeConnection();

        verify(mockClient).snrmRequest();
        verify(mockClient).parseUAResponse(any(gurux.dlms.GXByteBuffer.class));
        verify(mockClient).aarqRequest();
        verify(mockClient).parseAareResponse(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testInitializeConnection_MultiFrameAarq_SendsEveryFrame() throws Exception {
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        when(mockClient.getAuthentication()).thenReturn(Authentication.NONE);
        when(mockClient.aarqRequest()).thenReturn(new byte[][]{{0x01}, {0x02}, {0x03}});
        stubWrapperHeaderReceive();

        reader.initializeConnection();

        verify(mockMedia).send(new byte[]{0x01}, null);
        verify(mockMedia).send(new byte[]{0x02}, null);
        verify(mockMedia).send(new byte[]{0x03}, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testInitializeConnection_LowOrNoneAuthentication_SkipsSecondHandshakeStep() throws Exception {
        // Regression check for the actual bug found against a real meter: the plain
        // AARQ/AARE exchange alone is NOT the final authenticated response for HIGH-level
        // security - a separate application-association exchange is required. But for
        // NONE/LOW authentication, there IS no such second step, and calling
        // parseApplicationAssociationResponse() on the plain AARE reply fails.
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        when(mockClient.getAuthentication()).thenReturn(Authentication.NONE);
        when(mockClient.aarqRequest()).thenReturn(new byte[][]{{0x01}});
        stubWrapperHeaderReceive();

        reader.initializeConnection();

        verify(mockClient).parseAareResponse(any());
        verify(mockClient, never()).getApplicationAssociationRequest();
        verify(mockClient, never()).parseApplicationAssociationResponse(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testInitializeConnection_HighGmacAuthentication_PerformsSecondHandshakeStep() throws Exception {
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        when(mockClient.getAuthentication()).thenReturn(Authentication.HIGH_GMAC);
        when(mockClient.aarqRequest()).thenReturn(new byte[][]{{0x01}});
        when(mockClient.getApplicationAssociationRequest()).thenReturn(new byte[][]{{0x02}});
        stubWrapperHeaderReceive();

        reader.initializeConnection();

        verify(mockClient).parseAareResponse(any());
        verify(mockClient).getApplicationAssociationRequest();
        verify(mockClient).parseApplicationAssociationResponse(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testInitializeConnection_HighAuthentication_PerformsSecondHandshakeStep() throws Exception {
        // HLS-with-encryption mode uses Authentication.HIGH (not HIGH_GMAC) - must
        // also get the second handshake step, since HIGH > LOW too.
        when(mockClient.getInterfaceType()).thenReturn(InterfaceType.WRAPPER);
        when(mockClient.getAuthentication()).thenReturn(Authentication.HIGH);
        when(mockClient.aarqRequest()).thenReturn(new byte[][]{{0x01}});
        when(mockClient.getApplicationAssociationRequest()).thenReturn(new byte[][]{{0x02}});
        stubWrapperHeaderReceive();

        reader.initializeConnection();

        verify(mockClient).getApplicationAssociationRequest();
        verify(mockClient).parseApplicationAssociationResponse(any());
    }

    // ════════════════════════════════════════════════════════════════════
    // read / write / method TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    @SuppressWarnings("unchecked")
    void testRead_MapsClassIdToObjectType_AndReturnsValue() throws Exception {
        when(mockClient.read(eq("1.0.94.91.7.255"), eq(ObjectType.REGISTER), eq(2)))
                .thenReturn(new byte[][]{{0x01}});
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);
        stubGetDataToSetValue("230.5");

        Object value = reader.read("1.0.94.91.7.255", 3, 2);

        assertEquals("230.5", value);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRead_MeterErrorCode_ThrowsException() throws Exception {
        when(mockClient.read(anyString(), any(ObjectType.class), anyInt()))
                .thenReturn(new byte[][]{{0x01}});
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);
        stubGetDataToSetError(3);

        Exception ex = assertThrows(Exception.class, () -> reader.read("1.0.94.91.7.255", 3, 2));
        assertTrue(ex.getMessage().contains("error code: 3"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testWrite_MapsClassIdToObjectType_NoExceptionOnSuccess() throws Exception {
        when(mockClient.write(eq("0.0.1.0.0.255"), eq(220), eq(DataType.NONE), eq(ObjectType.CLOCK), eq(2)))
                .thenReturn(new byte[][]{{0x01}});
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);

        assertDoesNotThrow(() -> reader.write("0.0.1.0.0.255", 8, 2, 220));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testWrite_MeterErrorCode_ThrowsException() throws Exception {
        when(mockClient.write(anyString(), any(), any(DataType.class), any(ObjectType.class), anyInt()))
                .thenReturn(new byte[][]{{0x01}});
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);
        stubGetDataToSetError(5);

        Exception ex = assertThrows(Exception.class, () -> reader.write("1.0.94.91.7.255", 3, 2, "value"));
        assertTrue(ex.getMessage().contains("error code: 5"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMethod_WithParameter_UsesNoneDataType() throws Exception {
        when(mockClient.method(eq("0.0.96.3.10.255"), eq(ObjectType.DISCONNECT_CONTROL), eq(1), eq(1), eq(DataType.NONE)))
                .thenReturn(new byte[][]{{0x01}});
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);

        reader.method("0.0.96.3.10.255", 70, 1, 1);

        verify(mockClient).method("0.0.96.3.10.255", ObjectType.DISCONNECT_CONTROL, 1, 1, DataType.NONE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMethod_WithoutParameter_DefaultsToPlaceholderInt8() throws Exception {
        when(mockClient.method(eq("0.0.96.3.10.255"), any(ObjectType.class), eq(2), eq(0), eq(DataType.INT8)))
                .thenReturn(new byte[][]{{0x01}});
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);

        reader.method("0.0.96.3.10.255", 70, 2, null);

        verify(mockClient).method(eq("0.0.96.3.10.255"), any(ObjectType.class), eq(2), eq(0), eq(DataType.INT8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMethod_MeterErrorCode_ThrowsException() throws Exception {
        when(mockClient.method(anyString(), any(ObjectType.class), anyInt(), any(), any(DataType.class)))
                .thenReturn(new byte[][]{{0x01}});
        when(mockMedia.receive(any(ReceiveParameters.class))).thenReturn(true);
        stubGetDataToSetError(7);

        Exception ex = assertThrows(Exception.class, () -> reader.method("0.0.96.3.10.255", 70, 1, null));
        assertTrue(ex.getMessage().contains("error code: 7"));
    }

    // ════════════════════════════════════════════════════════════════════
    // close TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testClose_MediaOpen_ClosesMedia() {
        when(mockMedia.isOpen()).thenReturn(true);

        reader.close();

        verify(mockMedia).close();
    }

    @Test
    void testClose_MediaAlreadyClosed_DoesNotCallClose() {
        when(mockMedia.isOpen()).thenReturn(false);

        reader.close();

        verify(mockMedia, never()).close();
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════

    /**
     * dlmsClient.getData(byte[], GXReplyData, GXReplyData) mutates the reply object
     * passed in - simulate a successful parse by setting a value on it directly.
     */
    private void stubGetDataToSetValue(Object value) throws Exception {
        doAnswer(invocation -> {
            GXReplyData reply = invocation.getArgument(1);
            reply.setValue(value);
            return true;
        }).when(mockClient).getData(nullable(byte[].class), any(GXReplyData.class), any(GXReplyData.class));
    }

    private void stubGetDataToSetError(int errorCode) throws Exception {
        doAnswer(invocation -> {
            GXReplyData reply = invocation.getArgument(1);
            reply.setError(errorCode);
            return true;
        }).when(mockClient).getData(nullable(byte[].class), any(GXReplyData.class), any(GXReplyData.class));
    }

    /**
     * For WRAPPER transport, readDLMSPacket reads the 8-byte header first, then that
     * many more bytes based on the header's length field. Stub media.receive() to hand
     * back a fake header with payload length = 0, so a single receive() call per frame
     * is enough (matching what these tests verify).
     */
    @SuppressWarnings("unchecked")
    private void stubWrapperHeaderReceive() {
        doAnswer(invocation -> {
            ReceiveParameters<byte[]> p = invocation.getArgument(0);
            p.setReply(new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
            return true;
        }).when(mockMedia).receive(any(ReceiveParameters.class));
    }
}
