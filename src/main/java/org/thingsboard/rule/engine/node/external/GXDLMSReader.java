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

import gurux.common.IGXMedia;
import gurux.common.ReceiveParameters;
import gurux.dlms.GXReplyData;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.DataType;
import gurux.dlms.enums.InterfaceType;
import gurux.dlms.enums.ObjectType;
import gurux.dlms.objects.GXDLMSProfileGeneric;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;

/**
 * DLMS Reader - drives the actual wire conversation with the meter.
 *
 * Responsibilities:
 * 1. TCP send/receive (via Gurux Media), including multi-frame reassembly
 * 2. DLMS association handshake (SNRM/UA for HDLC, AARQ/AARE always)
 * 3. GET/SET/ACTION requests, using the Gurux client to build/parse each request
 *
 * Every public operation here follows the same pattern used throughout the Gurux
 * DLMS client examples: build request frame(s) with the client, send each frame,
 * block until the meter's reply is fully received (possibly across several
 * frames), then let the client parse/decrypt/verify it into a GXReplyData.
 */
@Slf4j
public class GXDLMSReader {

    private static final int DEFAULT_WAIT_TIME_MS = 5000;

    private final GXDLMSSecureClient2 dlmsClient;
    private final IGXMedia media;
    private int waitTimeMs = DEFAULT_WAIT_TIME_MS;

    public GXDLMSReader(GXDLMSSecureClient2 dlmsClient, IGXMedia media) {
        this.dlmsClient = dlmsClient;
        this.media = media;
        log.debug("GXDLMSReader initialized");
    }

    public GXDLMSSecureClient2 getDlmsClient() {
        return dlmsClient;
    }

    /**
     * How long to wait for the meter to answer a single frame, in milliseconds.
     * @param waitTimeMs wait time in milliseconds
     */
    public void setWaitTime(int waitTimeMs) {
        this.waitTimeMs = waitTimeMs;
    }

    /**
     * Number of bytes in a DLMS Wrapper header (IEC 62056-47): 2 bytes version,
     * 2 bytes source wPort, 2 bytes destination wPort, 2 bytes payload length.
     */
    private static final int WRAPPER_HEADER_LENGTH = 8;

    /**
     * Send one request frame and block until the meter's full reply has arrived,
     * reassembling multi-frame replies automatically.
     *
     * @param data Request bytes (a single DLMS/HDLC frame). No-op if null/empty.
     * @param reply Response object, populated (and decrypted/verified) by Gurux.
     * @throws Exception if the meter does not answer in time, or communication fails
     */
    public void readDLMSPacket(byte[] data, GXReplyData reply) throws Exception {
        if (data == null || data.length == 0) {
            return;
        }
        try {
            GXReplyData notify = new GXReplyData();
            dlmsClient.getData(sendAndReceiveFrame(data), reply, notify);

            // Some replies (larger data blocks) arrive across several frames;
            // keep asking for the next block until the client says it's complete.
            while (reply.isMoreData()) {
                byte[] next = dlmsClient.receiverReady(reply);
                dlmsClient.getData(sendAndReceiveFrame(next), reply, notify);
            }
        } catch (Exception e) {
            log.error("readDLMSPacket failed: {}", e.getMessage());
            throw new Exception("Failed to read DLMS packet: " + e.getMessage(), e);
        }
    }

    /**
     * Send one request frame and block until the meter's complete reply frame has
     * arrived, returning the full frame bytes (header included) for the client to parse.
     *
     * For WRAPPER transport (plain TCP/IP, no HDLC flag bytes) there is no single
     * end-marker byte to watch for - instead, every reply starts with a fixed 8-byte
     * header whose last 2 bytes say how many more bytes are coming. So this reads in
     * two steps: the 8-byte header first, then exactly that many more bytes.
     *
     * For HDLC transport, the 0x7E flag byte (given by media.getEop()) marks the end
     * of a frame, so a single Eop-based receive is enough.
     *
     * @param data request bytes to send
     * @return the complete reply frame
     * @throws Exception if the meter does not answer in time
     */
    private byte[] sendAndReceiveFrame(byte[] data) throws Exception {
        synchronized (media.getSynchronous()) {
            log.info("SEND ({} bytes): {}", data.length, toHex(data));
            media.send(data, null);

            if (dlmsClient.getInterfaceType() == InterfaceType.WRAPPER) {
                byte[] header = receiveExactly(WRAPPER_HEADER_LENGTH, "the wrapper header");
                log.info("RECV header ({} bytes): {}", header.length, toHex(header));
                int payloadLength = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                if (payloadLength == 0) {
                    return header;
                }
                byte[] payload = receiveExactly(payloadLength, "the wrapper payload");
                log.info("RECV payload ({} bytes): {}", payload.length, toHex(payload));
                byte[] full = new byte[header.length + payload.length];
                System.arraycopy(header, 0, full, 0, header.length);
                System.arraycopy(payload, 0, full, header.length, payload.length);
                return full;
            }

            ReceiveParameters<byte[]> p = new ReceiveParameters<>(byte[].class);
            p.setEop(media.getEop());
            p.setWaitTime(waitTimeMs);
            if (!media.receive(p)) {
                throw new Exception("Meter did not respond within " + waitTimeMs + "ms");
            }
            return p.getReply();
        }
    }

    /**
     * Blocks until exactly {@code count} bytes have been received.
     * Must be called while already holding the media's synchronous lock.
     */
    private byte[] receiveExactly(int count, String what) throws Exception {
        ReceiveParameters<byte[]> p = new ReceiveParameters<>(byte[].class);
        p.setCount(count);
        p.setWaitTime(waitTimeMs);
        if (!media.receive(p)) {
            throw new Exception("Meter did not send " + what + " (" + count + " bytes) within " + waitTimeMs + "ms");
        }
        return p.getReply();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    /**
     * Establish the DLMS association with the meter: SNRM/UA handshake if the
     * transport is HDLC, then AARQ/AARE, then - for anything above LOW security -
     * a SEPARATE application-association (HLS challenge-response) exchange.
     *
     * This second exchange is easy to miss: the AARQ/AARE alone only establishes
     * the association at the ACSE level. For HIGH/HIGH_GMAC/etc. authentication,
     * the meter still expects a follow-up request/response (the actual mutual
     * authentication challenge) before the association is fully secured. Calling
     * parseApplicationAssociationResponse() on the plain AARE reply (skipping this
     * second exchange) fails with a cryptic "Invalid data type" error, because
     * that method expects the challenge-response data, not a plain AARE.
     *
     * @throws Exception if the handshake fails or the meter rejects the association
     */
    public void initializeConnection() throws Exception {
        log.debug("Initializing connection (association handshake)...");
        try {
            GXReplyData reply = new GXReplyData();
            if (dlmsClient.getInterfaceType() == InterfaceType.HDLC) {
                readDLMSPacket(dlmsClient.snrmRequest(), reply);
                dlmsClient.parseUAResponse(reply.getData());
                reply.clear();
            }
            for (byte[] frame : dlmsClient.aarqRequest()) {
                readDLMSPacket(frame, reply);
            }
            dlmsClient.parseAareResponse(reply.getData());

            if (dlmsClient.getAuthentication().getValue() > Authentication.LOW.getValue()) {
                reply.clear();
                for (byte[] frame : dlmsClient.getApplicationAssociationRequest()) {
                    readDLMSPacket(frame, reply);
                }
                dlmsClient.parseApplicationAssociationResponse(reply.getData());
            }

            log.info("Association established with meter");
        } catch (Exception e) {
            log.error("Failed to initialize connection: {}", e.getMessage());
            throw new Exception("Connection initialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read one attribute from a COSEM object.
     *
     * @param obis OBIS code, e.g. "1.0.94.91.7.255"
     * @param classId DLMS class ID of the object (e.g. 3 for Register, 1 for Data)
     * @param attributeIndex attribute to read (1-based)
     * @return the parsed value from the meter
     * @throws Exception if the meter rejects the request or communication fails
     */
    public Object read(String obis, int classId, int attributeIndex) throws Exception {
        GXReplyData reply = new GXReplyData();
        for (byte[] frame : dlmsClient.read(obis, ObjectType.forValue(classId), attributeIndex)) {
            readDLMSPacket(frame, reply);
        }
        if (reply.getError() != 0) {
            throw new Exception("Meter returned error code: " + reply.getError());
        }
        return reply.getValue();
    }

    /**
     * Read one attribute purely to learn its real DLMS wire type (the value itself
     * is discarded). Used by the WRITE type-detection fallback: a write can only be
     * encoded correctly if it uses the SAME type the meter's object model actually
     * expects for that attribute (e.g. FLOAT32 vs INT32) - guessing from the Java
     * value alone is not reliable, since e.g. both an Integer and a smaller-width
     * unsigned type look identical as plain numbers.
     *
     * @param obis OBIS code
     * @param classId DLMS class ID of the object
     * @param attributeIndex attribute to inspect (1-based)
     * @return the DLMS DataType the meter reports for this attribute
     * @throws Exception if the meter rejects the request or communication fails
     */
    public DataType readDataType(String obis, int classId, int attributeIndex) throws Exception {
        GXReplyData reply = new GXReplyData();
        for (byte[] frame : dlmsClient.read(obis, ObjectType.forValue(classId), attributeIndex)) {
            readDLMSPacket(frame, reply);
        }
        if (reply.getError() != 0) {
            throw new Exception("Meter returned error code: " + reply.getError());
        }
        return reply.getValueType();
    }

    /**
     * Selective-access range read of a Profile Generic buffer (e.g. block load
     * survey / MR history) - asks the meter for only the rows whose capture time
     * falls between {@code from} and {@code to}, instead of the entire buffer.
     * Uses Gurux's own readRowsByRange(), which builds the same access-selection
     * GET request (range-descriptor selector) the production TCP path already
     * hand-builds for this same purpose.
     *
     * @param obis OBIS code of the Profile Generic object, e.g. "1.0.99.1.0.255"
     *        (block load survey) or "1.0.99.2.0.255" (MR history)
     * @param from start of the range (inclusive)
     * @param to end of the range (inclusive)
     * @return the matching rows, each row itself an array of captured column values
     * @throws Exception if the meter rejects the request or communication fails
     */
    public Object[] readRange(String obis, Date from, Date to) throws Exception {
        GXDLMSProfileGeneric profileGeneric = new GXDLMSProfileGeneric(obis);

        // A Profile Generic buffer's rows carry no embedded schema - the client must
        // separately learn what each column represents (attribute 3, "capture
        // objects") before it can decode the rows a range read returns. Skipping
        // this makes Gurux throw "Capture objects not read" once it tries to parse
        // the buffer, even though the range read itself succeeded on the wire.
        GXReplyData captureReply = new GXReplyData();
        for (byte[] frame : dlmsClient.read(obis, ObjectType.forValue(7), 3)) {
            readDLMSPacket(frame, captureReply);
        }
        if (captureReply.getError() != 0) {
            throw new Exception("Meter returned error code: " + captureReply.getError());
        }
        dlmsClient.updateValue(profileGeneric, 3, captureReply.getValue());

        GXReplyData reply = new GXReplyData();
        for (byte[] frame : dlmsClient.readRowsByRange(profileGeneric, from, to)) {
            readDLMSPacket(frame, reply);
        }
        if (reply.getError() != 0) {
            throw new Exception("Meter returned error code: " + reply.getError());
        }
        // Buffer attribute is always index 2 for Profile Generic - fixed by the
        // DLMS spec, not something that varies per object.
        dlmsClient.updateValue(profileGeneric, 2, reply.getValue());
        return profileGeneric.getBuffer();
    }

    /**
     * Write one attribute on a COSEM object, letting Gurux auto-detect the wire type
     * from the Java value (except a List - see class note above). Prefer the
     * explicit-type overload below when the attribute's real DLMS type is known;
     * auto-detection cannot distinguish e.g. FLOAT32 from FLOAT64, or a 16-bit
     * unsigned from a 32-bit one, from the Java value alone.
     *
     * @param obis OBIS code
     * @param classId DLMS class ID of the object
     * @param attributeIndex attribute to write (1-based)
     * @param value new value (type is auto-detected by Gurux from the Java type,
     *        except a List - see class note above)
     * @throws Exception if the meter rejects the write or communication fails
     */
    public void write(String obis, int classId, int attributeIndex, Object value) throws Exception {
        // Gurux's DataType.NONE auto-detection does NOT handle a List (a DLMS
        // "structure" - e.g. a multi-field config object read back with some
        // fields patched) - it throws "Invalid value" immediately. A List must
        // be told explicitly that it's a STRUCTURE. Verified directly against
        // the real Gurux library: NONE fails, STRUCTURE succeeds, for the exact
        // same List input.
        DataType dataType = (value instanceof java.util.List) ? DataType.STRUCTURE : DataType.NONE;
        write(obis, classId, attributeIndex, value, dataType);
    }

    /**
     * Write one attribute on a COSEM object using an explicit, known-correct DLMS
     * wire type - the reliable path, since it never relies on guessing the type
     * from the Java value's shape.
     *
     * @param obis OBIS code
     * @param classId DLMS class ID of the object
     * @param attributeIndex attribute to write (1-based)
     * @param value new value, already converted to match {@code dataType}
     * @param dataType the exact DLMS type the meter expects for this attribute
     * @throws Exception if the meter rejects the write or communication fails
     */
    public void write(String obis, int classId, int attributeIndex, Object value, DataType dataType) throws Exception {
        GXReplyData reply = new GXReplyData();
        for (byte[] frame : dlmsClient.write(obis, value, dataType, ObjectType.forValue(classId), attributeIndex)) {
            readDLMSPacket(frame, reply);
        }
        if (reply.getError() != 0) {
            throw new Exception("Meter returned error code: " + reply.getError() +
                    " (meter rejected write - may be read-only or an invalid value)");
        }
    }

    /**
     * Invoke a method (ACTION) on a COSEM object.
     *
     * @param obis OBIS code
     * @param classId DLMS class ID of the object
     * @param methodIndex method to invoke (1-based)
     * @param parameter method parameter, or null if the method takes no parameter
     * @return the method's return value, or null if it returned nothing
     * @throws Exception if the meter rejects the call or communication fails
     */
    public Object method(String obis, int classId, int methodIndex, Object parameter) throws Exception {
        GXReplyData reply = new GXReplyData();
        Object param = parameter != null ? parameter : 0;
        DataType paramType = parameter != null ? DataType.NONE : DataType.INT8;
        for (byte[] frame : dlmsClient.method(obis, ObjectType.forValue(classId), methodIndex, param, paramType)) {
            readDLMSPacket(frame, reply);
        }
        if (reply.getError() != 0) {
            throw new Exception("Meter returned error code: " + reply.getError() +
                    " (method may not exist or is not allowed)");
        }
        return reply.getValue();
    }

    /**
     * Close connection and cleanup.
     */
    public void close() {
        try {
            if (media != null && media.isOpen()) {
                media.close();
                log.info("Connection closed");
            }
        } catch (Exception e) {
            log.warn("Error closing connection: {}", e.getMessage());
        }
    }
}
