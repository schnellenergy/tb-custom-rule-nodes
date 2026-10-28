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
import gurux.dlms.GXBitString;
import gurux.dlms.GXDateTime;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.DataType;
import gurux.dlms.enums.InterfaceType;
import gurux.dlms.enums.Standard;
import gurux.net.GXNet;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DLMS Protocol Executor.
 * Executes actual DLMS operations (READ, WRITE, ACTION) on smart meters.
 *
 * Workflow:
 * 1. Get TCP connection from pool
 * 2. Create GXDLMSSecureClient2 (extends Gurux's real DLMS client)
 * 3. Create GXDLMSReader with client + media
 * 4. Initialize connection (SNRM/AARQ handshake)
 * 5. Execute operation using Gurux API
 * 6. Return result
 *
 * Thread-Safe: Can be called concurrently from multiple threads
 */
@Slf4j
public class DlmsExecutor {

    private final DlmsClientPool connectionPool;

    /**
     * OBIS -> real DLMS wire type, for WRITE. A plain-attribute WRITE can only be
     * encoded correctly if it matches the EXACT type the meter's object model
     * expects (e.g. FLOAT32 vs FLOAT64, or a 16-bit vs 32-bit unsigned) - guessing
     * purely from the shape of the incoming value string (e.g. "260" vs "260.0")
     * is not reliable and was the root cause of writes to manufacturer-specific
     * objects (e.g. SEMC's rVoltMin/rVoltMax) being rejected by the meter.
     *
     * Seeded from the manufacturer's own protocol document ("StreetLight Smart
     * meter Protocol - V1.19", the "Attribute Single command Set" sheet) -
     * individually-addressable ("raw profile") fields marked green (READ/WRITE,
     * supported for single-command SET) only. Fields marked gray (read-only),
     * blue/yellow (read/write but deliberately excluded from single-command SET -
     * e.g. RTC, TCP-UDP_port, the ServerDomainName push-config strings) are
     * intentionally NOT listed here; those go through their own dedicated paths
     * (SET-CLOCK, WRITE-GROUP) instead. "EnabledEvents" (bit-string) is likewise
     * omitted - it's already handled by the separate dataType="BITSTRING" path in
     * executeWrite(), which runs before this cache is ever consulted. "TOD Slot
     * Setting" is a variable-length list (Slot_num*3 bytes), not a simple scalar,
     * so it doesn't fit this type-per-OBIS scheme.
     *
     * Any OBIS not listed here (a field this document doesn't cover, or a
     * different meter model) is learned automatically on its first WRITE (see
     * executeWrite()): read the attribute once to ask the meter what type it
     * really is, use that type for the write, then cache it here so every
     * subsequent write to the same OBIS skips the extra read.
     *
     * Shared/static rather than per-request: within one meter model/vendor
     * integration (this class targets one manufacturer's fleet), the same OBIS
     * always has the same type, so the mapping is safe to reuse across every
     * request, not just the one that discovered/seeded it.
     */
    private static final Map<String, DataType> KNOWN_OBIS_TYPES = new ConcurrentHashMap<>();

    static {
        // -- Operating mode / astro / slot timing --
        KNOWN_OBIS_TYPES.put("1.131.128.0.0.255", DataType.UINT8);   // operatingMode (unsigned)
        KNOWN_OBIS_TYPES.put("1.131.129.1.0.255", DataType.BOOLEAN); // astroModeGPS
        KNOWN_OBIS_TYPES.put("1.131.129.2.0.255", DataType.FLOAT32); // astroLatitude
        KNOWN_OBIS_TYPES.put("1.131.129.3.0.255", DataType.FLOAT32); // astroLongitude
        KNOWN_OBIS_TYPES.put("1.131.129.6.0.255", DataType.INT16);   // astroOnOffSet (long)
        KNOWN_OBIS_TYPES.put("1.131.129.7.0.255", DataType.INT16);   // astroOffOffSet (long)
        KNOWN_OBIS_TYPES.put("1.131.129.8.0.255", DataType.UINT32);  // slotOnTime, GMT format (double-long-unsigned)
        KNOWN_OBIS_TYPES.put("1.131.129.8.1.255", DataType.OCTET_STRING); // slotOnTime, DLMS format
        KNOWN_OBIS_TYPES.put("1.131.129.9.0.255", DataType.UINT32);  // slotOffTime, GMT format
        KNOWN_OBIS_TYPES.put("1.131.129.9.1.255", DataType.OCTET_STRING); // slotOffTime, DLMS format

        // -- Publish / payload settings --
        KNOWN_OBIS_TYPES.put("1.131.130.2.0.255", DataType.UINT16);  // publishInterval (long-unsigned)
        KNOWN_OBIS_TYPES.put("1.131.130.3.0.255", DataType.UINT16);  // maxPayloadSlotOn
        KNOWN_OBIS_TYPES.put("1.131.130.4.0.255", DataType.UINT16);  // maxPayloadSlotOff

        // -- Current thresholds (R/Y/B phase) --
        KNOWN_OBIS_TYPES.put("1.0.31.128.0.255", DataType.FLOAT32);  // rCurrentMin
        KNOWN_OBIS_TYPES.put("1.0.31.128.1.255", DataType.FLOAT32);  // rCurrentMax
        KNOWN_OBIS_TYPES.put("1.0.51.128.0.255", DataType.FLOAT32);  // yCurrentMin
        KNOWN_OBIS_TYPES.put("1.0.51.128.1.255", DataType.FLOAT32);  // yCurrentMax
        KNOWN_OBIS_TYPES.put("1.0.71.128.0.255", DataType.FLOAT32);  // bCurrentMin
        KNOWN_OBIS_TYPES.put("1.0.71.128.1.255", DataType.FLOAT32);  // bCurrentMax

        // -- Voltage thresholds (R/Y/B phase) --
        KNOWN_OBIS_TYPES.put("1.0.32.128.0.255", DataType.FLOAT32);  // rVoltMin (verified live 2026-09-10)
        KNOWN_OBIS_TYPES.put("1.0.32.128.1.255", DataType.FLOAT32);  // rVoltMax (verified live 2026-09-10)
        KNOWN_OBIS_TYPES.put("1.0.52.128.0.255", DataType.FLOAT32);  // yVoltMin
        KNOWN_OBIS_TYPES.put("1.0.52.128.1.255", DataType.FLOAT32);  // yVoltMax
        KNOWN_OBIS_TYPES.put("1.0.72.128.0.255", DataType.FLOAT32);  // bVoltMin
        KNOWN_OBIS_TYPES.put("1.0.72.128.1.255", DataType.FLOAT32);  // bVoltMax

        // -- Power-factor thresholds (R/Y/B phase) --
        KNOWN_OBIS_TYPES.put("1.0.33.128.0.255", DataType.FLOAT32);  // rpfThreshold
        KNOWN_OBIS_TYPES.put("1.0.53.128.0.255", DataType.FLOAT32);  // ypfThreshold
        KNOWN_OBIS_TYPES.put("1.0.73.128.0.255", DataType.FLOAT32);  // bpfThreshold
    }

    public DlmsExecutor(DlmsClientPool connectionPool) {
        this.connectionPool = connectionPool;
    }

    /**
     * Execute a DLMS operation on a smart meter (with Semaphore-based pooling).
     *
     * Uses borrowConnection/returnConnection pattern for fair resource allocation:
     * ├─ borrowConnection: Acquires semaphore permit (waits if full)
     * ├─ Execute operation
     * ├─ returnConnection: Releases permit (wakes waiting threads)
     * └─ Supports 100+ meters without data loss ✅
     *
     * @param request DlmsRequest containing operation parameters
     * @return DlmsResponse with result or error
     */
    public DlmsResponse execute(DlmsRequest request) {
        long startTime = System.currentTimeMillis();
        DlmsResponse response = new DlmsResponse();
        GXDLMSReader reader = null;
        GXNet connection = null;

        try {
            // Step 1: Validate request
            if (request == null) {
                throw new IllegalArgumentException("Request cannot be null");
            }
            if (request.getHost() == null || request.getHost().isEmpty()) {
                throw new IllegalArgumentException("Host cannot be empty");
            }
            if (request.getPort() <= 0 || request.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid port: " + request.getPort());
            }

            log.debug("DLMS Execute: {}:{} action={}", request.getHost(), request.getPort(), request.getAction());

            // Step 2: Borrow TCP connection from pool (semaphore-based)
            try {
                connection = connectionPool.borrowConnection(request.getHost(), request.getPort());
            } catch (java.util.concurrent.TimeoutException te) {
                throw new Exception("Connection pool exhausted for " + request.getHost() + ":" + request.getPort() +
                                  " - all slots in use");
            }

            // Step 3: Determine invocation counter - either explicitly supplied (skips
            // the unsecured meter probe entirely, for meters that don't handle it well)
            // or read from the meter as usual (OBIS 0.0.43.1.0.255, unsecured).
            int nextCounter;
            if (request.getCounter() >= 0) {
                nextCounter = request.getCounter();
                log.debug("Using explicitly supplied invocation counter: {}", nextCounter);
            } else {
                GXDLMSSecureClient2 tempClient = new GXDLMSSecureClient2(true);
                tempClient.setInterfaceType(InterfaceType.WRAPPER);
                applyProtocolAddressing(tempClient, request);
                int currentMeterCounter = readCounterFromMeter(tempClient, connection, request.getClientAddress());
                nextCounter = currentMeterCounter + 1;
                log.debug("Meter counter: {}, Next counter: {}", currentMeterCounter, nextCounter);
            }

            // Step 4: Create final DLMS Secure Client with counter from meter
            GXDLMSSecureClient2 dlmsClient = new GXDLMSSecureClient2(true);
            dlmsClient.setInterfaceType(InterfaceType.WRAPPER);
            applyProtocolAddressing(dlmsClient, request);
            dlmsClient.setInvocationCounter(nextCounter);

            // Step 5: Create DLMS Reader (handles protocol communication)
            reader = new GXDLMSReader(dlmsClient, connection);
            if (request.getTimeout() > 0) {
                reader.setWaitTime(request.getTimeout() * 1000);
            }

            // Step 6: Configure security - HLS-with-encryption (password + keys) if a
            // password was supplied, otherwise the default HLSE/GMAC (keys only) mode.
            // Some meters require the former rather than pure HLSE.
            if (request.getPassword() != null && !request.getPassword().isEmpty()) {
                dlmsClient.configureHLSWithEncryption(
                    request.getPassword(),
                    request.getBlockCipherKey(),
                    request.getAuthenticationKey(),
                    request.getSystemTitle()
                );
            } else {
                dlmsClient.configureHLSE(
                    request.getBlockCipherKey(),
                    request.getAuthenticationKey(),
                    request.getSystemTitle()
                );
            }

            // Step 7: Initialize connection (AARQ handshake, secured per the mode chosen above)
            reader.initializeConnection();

            // Step 8: Execute operation based on action type
            String result = executeOperation(reader, dlmsClient, request);

            // Step 9: Build success response
            response.setSuccess(true);
            response.setResponseCode("0");
            response.setData(result);
            response.setErrorMessage(null);

            log.info("DLMS Success: {}:{} action={} duration={}ms",
                request.getHost(), request.getPort(), request.getAction(),
                System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            response.setSuccess(false);
            response.setResponseCode("ERROR");
            response.setData(null);
            response.setErrorMessage(e.getMessage());

            log.error("DLMS Error: {}:{} action={} - {}",
                request != null ? request.getHost() : "null",
                request != null ? request.getPort() : 0,
                request != null ? request.getAction() : "null",
                e.getMessage(), e);

            // If connection had error, discard it (don't reuse)
            if (connection != null && request != null) {
                connectionPool.discardConnection(connection, request.getHost(), request.getPort());
                connection = null;
            }

        } finally {
            // Close reader
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e) {
                    log.warn("Error closing reader: {}", e.getMessage());
                }
            }

            // Return connection to pool (so it can be reused)
            if (connection != null) {
                connectionPool.returnConnection(connection, request.getHost(), request.getPort());
                connection = null;
            }
        }

        response.setDurationMs(System.currentTimeMillis() - startTime);
        return response;
    }

    /**
     * Execute specific DLMS operation type.
     *
     * @param reader GXDLMSReader connected to meter
     * @param dlmsClient GXDLMSSecureClient2 (Gurux DLMS client)
     * @param request Operation parameters
     * @return Operation result as string
     * @throws Exception if operation fails
     */
    private String executeOperation(GXDLMSReader reader, GXDLMSSecureClient2 dlmsClient, DlmsRequest request) throws Exception {
        String action = request.getAction();

        if (action == null || action.isEmpty()) {
            throw new IllegalArgumentException("Action cannot be empty");
        }

        try {
            switch (action.toUpperCase()) {
                case "READ":
                    return executeRead(reader, dlmsClient, request);

                case "READ-RANGE":
                    return executeReadRange(reader, dlmsClient, request);

                case "WRITE":
                    return executeWrite(reader, dlmsClient, request);

                case "WRITE-GROUP":
                    return executeWriteGroup(reader, dlmsClient, request);

                case "ACTION":
                    return executeAction(reader, dlmsClient, request);

                case "SET-CLOCK":
                    return executeSetClock(reader, dlmsClient, request);

                default:
                    throw new IllegalArgumentException("Unknown action: " + action);
            }
        } catch (Exception e) {
            throw new Exception("Operation " + action + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * READ operation: Get value from meter (secured with HLSE).
     * Sends encrypted READ APDU to meter and receives encrypted response.
     *
     * @param reader GXDLMSReader connected to meter
     * @param dlmsClient GXDLMSSecureClient2 (Gurux DLMS client)
     * @param request Contains obis, classId, attribute
     * @return Meter value as string
     * @throws Exception if read fails
     */
    private String executeRead(GXDLMSReader reader, GXDLMSSecureClient2 dlmsClient, DlmsRequest request) throws Exception {
        if (request.getObis() == null || request.getObis().isEmpty()) {
            throw new IllegalArgumentException("OBIS code required for READ");
        }

        int attributeIndex = request.getAttribute() > 0 ? request.getAttribute() : 2;
        log.debug("READ OBIS: {} class: {} attr: {}", request.getObis(), request.getClassId(), attributeIndex);

        try {
            // Validate OBIS format early for a clear error message
            parseObisToLogicalName(request.getObis());

            // Build the GET request, send it (encrypted + GMAC-signed by Gurux), and
            // block until the meter's (decrypted, verified) reply is fully received
            Object data = reader.read(request.getObis(), request.getClassId(), attributeIndex);
            if (data == null) {
                log.warn("READ returned null value for OBIS {}", request.getObis());
                return "";
            }

            String result = convertToString(data);
            log.info("READ success: OBIS {} = {} (encrypted+GMAC verified)", request.getObis(), result);
            return result;

        } catch (Exception e) {
            log.error("READ operation failed for OBIS {}: {}", request.getObis(), e.getMessage(), e);
            throw new Exception("READ failed: " + e.getMessage(), e);
        }
    }

    /**
     * READ-RANGE operation: selective-access range read of a Profile Generic
     * buffer (block load survey / MR history) - returns only the rows whose
     * capture time falls between fromTs and toTs, instead of the whole buffer.
     *
     * @param reader GXDLMSReader connected to meter
     * @param dlmsClient GXDLMSSecureClient2
     * @param request Contains obis, fromTs, toTs (both epoch seconds)
     * @return the matching rows, formatted the same way a Profile Generic row
     *         read via plain READ already is (see convertToString)
     * @throws Exception if the range is invalid, or the read fails
     */
    private String executeReadRange(GXDLMSReader reader, GXDLMSSecureClient2 dlmsClient, DlmsRequest request) throws Exception {
        if (request.getObis() == null || request.getObis().isEmpty()) {
            throw new IllegalArgumentException("OBIS code required for READ-RANGE");
        }
        if (request.getFromTs() <= 0 || request.getToTs() <= 0) {
            throw new IllegalArgumentException("fromTs and toTs (epoch seconds) both required for READ-RANGE");
        }
        if (request.getToTs() <= request.getFromTs()) {
            throw new IllegalArgumentException("toTs must be after fromTs");
        }

        log.debug("READ-RANGE OBIS: {} from: {} to: {}", request.getObis(), request.getFromTs(), request.getToTs());

        try {
            parseObisToLogicalName(request.getObis());

            Date from = new Date(request.getFromTs() * 1000);
            Date to = new Date(request.getToTs() * 1000);

            Object[] rows = reader.readRange(request.getObis(), from, to);
            String result = convertToString(rows);
            log.info("READ-RANGE success: OBIS {} returned {} row(s) (encrypted+GMAC verified)",
                    request.getObis(), rows == null ? 0 : rows.length);
            return result;

        } catch (Exception e) {
            log.error("READ-RANGE operation failed for OBIS {}: {}", request.getObis(), e.getMessage(), e);
            throw new Exception("READ-RANGE failed: " + e.getMessage(), e);
        }
    }

    /**
     * WRITE operation: Set value on meter (secured with HLSE).
     * Sends encrypted WRITE APDU to meter to update a register/attribute.
     *
     * Common use cases:
     * - Set time: OBIS 0.0.1.0.0.255 (Clock)
     * - Enable/disable tariff: OBIS 0.0.13.0.0.255
     * - Configuration changes
     *
     * @param reader GXDLMSReader connected to meter
     * @param dlmsClient GXDLMSSecureClient2
     * @param request Contains obis, classId, attribute, value
     * @return "OK" if successful
     * @throws Exception if write fails
     */
    private String executeWrite(GXDLMSReader reader, GXDLMSSecureClient2 dlmsClient, DlmsRequest request) throws Exception {
        if (request.getObis() == null || request.getObis().isEmpty()) {
            throw new IllegalArgumentException("OBIS code required for WRITE");
        }
        if (request.getValue() == null || request.getValue().isEmpty()) {
            throw new IllegalArgumentException("Value required for WRITE");
        }

        int attributeIndex = request.getAttribute() > 0 ? request.getAttribute() : 2;
        log.debug("WRITE OBIS: {} value: {} (class: {} attr: {})",
            request.getObis(), request.getValue(), request.getClassId(), attributeIndex);

        try {
            parseObisToLogicalName(request.getObis());

            if ("BITSTRING".equalsIgnoreCase(request.getDataType())) {
                GXBitString bits = buildBitStringValue(request.getValue(), request.getBitLength());
                reader.write(request.getObis(), request.getClassId(), attributeIndex, bits);
                log.info("WRITE success: OBIS {} set to {} (encrypted+GMAC verified)", request.getObis(), bits);
                return "OK";
            }

            // Fast path: OBIS already known (either seeded, or learned from an
            // earlier write this JVM has made) - skip straight to writing.
            // Fallback path: unknown OBIS - read the attribute once to ask the
            // meter what type it really expects, use that for this write, then
            // cache it so every future write to this OBIS takes the fast path.
            DataType wireType = KNOWN_OBIS_TYPES.get(request.getObis());
            if (wireType == null) {
                try {
                    wireType = reader.readDataType(request.getObis(), request.getClassId(), attributeIndex);
                    KNOWN_OBIS_TYPES.put(request.getObis(), wireType);
                    log.info("Learned DLMS wire type for OBIS {}: {} (cached for future writes)",
                            request.getObis(), wireType);
                } catch (Exception e) {
                    log.warn("Could not pre-read OBIS {} to detect its real type ({}) - " +
                            "falling back to type inference from the value string", request.getObis(), e.getMessage());
                }
            }

            Object value = (wireType != null)
                    ? convertValueForType(request.getValue(), wireType)
                    : convertValue(request.getValue());
            log.debug("Converted value: {} (type: {}, wire type: {})",
                    value, value.getClass().getSimpleName(), wireType);

            // Build the SET request, send it (encrypted + GMAC-signed), and block
            // until the meter confirms the update (or reports an error)
            reader.write(request.getObis(), request.getClassId(), attributeIndex, value,
                    wireType != null ? wireType : DataType.NONE);

            log.info("WRITE success: OBIS {} set to {} (encrypted+GMAC verified)", request.getObis(), value);
            return "OK";

        } catch (Exception e) {
            log.error("WRITE operation failed for OBIS {} = {}: {}",
                request.getObis(), request.getValue(), e.getMessage(), e);
            throw new Exception("WRITE failed: " + e.getMessage(), e);
        }
    }

    /**
     * WRITE-GROUP operation: read-modify-write for a manufacturer-specific,
     * multi-field structure (e.g. SEMC's "operating mode" object). Only the
     * fields present in request.getGroupFields() are changed; every other
     * field in the structure is read from the meter and written back
     * unchanged, so a partial update never clobbers unrelated settings.
     *
     * OBIS/classId/attribute default to SEMC's operating-mode structure if
     * left unset on the request - see GroupSetForSemc.
     *
     * @param reader GXDLMSReader connected to meter
     * @param dlmsClient GXDLMSSecureClient2
     * @param request Contains groupFields (required), optionally obis/classId/attribute
     * @return "OK" if successful
     * @throws Exception if the read, a field conversion, or the write fails
     */
    private String executeWriteGroup(GXDLMSReader reader, GXDLMSSecureClient2 dlmsClient, DlmsRequest request) throws Exception {
        if (request.getGroupFields() == null || request.getGroupFields().isEmpty()) {
            throw new IllegalArgumentException("At least one field required for WRITE-GROUP. Available fields: " +
                    GroupSetForSemc.fieldNames());
        }

        String obis = request.getObis() != null && !request.getObis().isEmpty()
                ? request.getObis() : GroupSetForSemc.GROUP_WRITE_OBIS;
        int classId = request.getClassId() > 0 ? request.getClassId() : GroupSetForSemc.GROUP_WRITE_CLASS_ID;
        int attributeIndex = request.getAttribute() > 0 ? request.getAttribute() : GroupSetForSemc.GROUP_WRITE_ATTRIBUTE;

        log.debug("WRITE-GROUP OBIS: {} fields: {}", obis, request.getGroupFields().keySet());

        try {
            Object current = reader.read(obis, classId, attributeIndex);
            if (!(current instanceof java.util.List)) {
                throw new IllegalArgumentException("Read value is not a DLMS structure (expected a List), got: " +
                        (current == null ? "null" : current.getClass().getSimpleName()));
            }
            @SuppressWarnings("unchecked")
            java.util.List<Object> structure = (java.util.List<Object>) current;

            for (java.util.Map.Entry<String, Object> override : request.getGroupFields().entrySet()) {
                String fieldName = override.getKey();
                Integer fieldIndex = GroupSetForSemc.FIELD_INDEX.get(fieldName);
                if (fieldIndex == null) {
                    throw new IllegalArgumentException("Unknown WRITE-GROUP field: '" + fieldName +
                            "'. Available: " + GroupSetForSemc.fieldNames());
                }
                if (fieldIndex >= structure.size()) {
                    throw new IllegalArgumentException("Field '" + fieldName + "' index " + fieldIndex +
                            " is out of range (structure has " + structure.size() + " fields)");
                }
                Object existing = structure.get(fieldIndex);
                Object converted = GroupSetForSemc.convertFieldValue(existing, override.getValue(), fieldName);
                structure.set(fieldIndex, converted);
                log.debug("WRITE-GROUP patched [{}] {}: {} -> {}", fieldIndex, fieldName, existing, converted);
            }

            reader.write(obis, classId, attributeIndex, structure);
            log.info("WRITE-GROUP success: OBIS {} fields changed: {} (encrypted+GMAC verified)",
                    obis, request.getGroupFields().keySet());
            return "OK";

        } catch (Exception e) {
            log.error("WRITE-GROUP operation failed for OBIS {}: {}", obis, e.getMessage(), e);
            throw new Exception("WRITE-GROUP failed: " + e.getMessage(), e);
        }
    }

    /**
     * ACTION operation: Execute method on meter (secured with HLSE).
     * Invokes a meter method (e.g., relay connect/disconnect, reset counter, etc).
     *
     * Common use cases:
     * - Relay connect: OBIS 0.0.96.3.10.255 method 1
     * - Relay disconnect: OBIS 0.0.96.3.10.255 method 2
     * - Reset billing: OBIS 0.0.43.0.0.255 method 1
     * - Remote restart: OBIS 0.0.1.0.0.255 method 1
     *
     * @param reader GXDLMSReader connected to meter
     * @param dlmsClient GXDLMSSecureClient2
     * @param request Contains obis, classId, method, value (optional parameter)
     * @return Method result or "OK"
     * @throws Exception if action fails
     */
    private String executeAction(GXDLMSReader reader, GXDLMSSecureClient2 dlmsClient, DlmsRequest request) throws Exception {
        if (request.getObis() == null || request.getObis().isEmpty()) {
            throw new IllegalArgumentException("OBIS code required for ACTION");
        }
        if (request.getMethod() <= 0) {
            throw new IllegalArgumentException("Valid method index required for ACTION");
        }

        log.debug("ACTION OBIS: {} method: {} param: {}",
            request.getObis(), request.getMethod(), request.getValue());

        try {
            parseObisToLogicalName(request.getObis());

            // Prepare method parameter (if provided)
            Object param = null;
            if (request.getValue() != null && !request.getValue().isEmpty()) {
                param = convertValue(request.getValue());
                log.debug("Method parameter: {} (type: {})", param, param.getClass().getSimpleName());
            }

            // Build the ACTION request, send it (encrypted + GMAC-signed), and block
            // until the meter's method result (or error) is fully received
            Object result = reader.method(request.getObis(), request.getClassId(), request.getMethod(), param);
            String resultStr = result != null ? convertToString(result) : "OK";

            // Log the action result
            if ("OK".equals(resultStr)) {
                log.info("ACTION success: OBIS {} method {} executed (encrypted+GMAC verified)",
                    request.getObis(), request.getMethod());
            } else {
                log.info("ACTION success: OBIS {} method {} returned: {} (encrypted+GMAC verified)",
                    request.getObis(), request.getMethod(), resultStr);
            }
            return resultStr;

        } catch (Exception e) {
            log.error("ACTION operation failed for OBIS {} method {}: {}",
                request.getObis(), request.getMethod(), e.getMessage(), e);
            throw new Exception("ACTION failed: " + e.getMessage(), e);
        }
    }

    /**
     * SET-CLOCK operation: Set meter's internal clock (secured with HLSE).
     *
     * @param reader GXDLMSReader connected to meter
     * @param dlmsClient GXDLMSSecureClient2
     * @param request Contains value (ISO datetime)
     * @return "OK" if successful
     * @throws Exception if set-clock fails
     */
    private String executeSetClock(GXDLMSReader reader, GXDLMSSecureClient2 dlmsClient, DlmsRequest request) throws Exception {
        if (request.getValue() == null || request.getValue().isEmpty()) {
            throw new IllegalArgumentException("Datetime value required for SET-CLOCK");
        }

        log.debug("SET-CLOCK value: {}", request.getValue());

        try {
            String datetimeStr = request.getValue();
            Date datetime = (Date) parseDateTime(datetimeStr);
            GXDateTime meterTime = new GXDateTime(datetime);

            // Clock's "time" attribute is conventionally OBIS 0.0.1.0.0.255, class 8
            // (Clock), attribute 2 - used unless the request specifies otherwise.
            String obis = request.getObis() != null && !request.getObis().isEmpty()
                    ? request.getObis() : "0.0.1.0.0.255";
            int classId = request.getClassId() > 0 ? request.getClassId() : 8;
            int attributeIndex = request.getAttribute() > 0 ? request.getAttribute() : 2;

            // Vendor-agnostic type detection - same cache/fallback mechanism used
            // for plain WRITE (see KNOWN_OBIS_TYPES). Different meters encode a
            // Clock's time attribute differently on the wire: some use the DLMS
            // spec's dedicated DATETIME tag (0x19), others wrap the same 12 raw
            // bytes as an OCTET_STRING (0x09) instead - a common real-world
            // inconsistency between the spec and how firmware actually expects it
            // encoded. Gurux's auto-detection (DataType.NONE) always resolves a
            // GXDateTime to DATETIME, which isn't correct for every meter - so
            // instead we ask THIS meter what type it actually uses for this
            // attribute, the same way plain WRITE does for any other OBIS.
            DataType wireType = KNOWN_OBIS_TYPES.get(obis);
            if (wireType == null) {
                try {
                    wireType = reader.readDataType(obis, classId, attributeIndex);
                    KNOWN_OBIS_TYPES.put(obis, wireType);
                    log.info("Learned DLMS wire type for OBIS {}: {} (cached for future writes)", obis, wireType);
                } catch (Exception e) {
                    log.warn("Could not pre-read OBIS {} to detect its real type ({}) - " +
                            "falling back to the DLMS-standard DATETIME encoding", obis, e.getMessage());
                }
            }

            // Build the SET request, send it (encrypted + GMAC-signed), and block
            // until the meter confirms the clock update (or reports an error)
            reader.write(obis, classId, attributeIndex, meterTime,
                    wireType != null ? wireType : DataType.DATETIME);

            log.info("Clock set to: {}", datetimeStr);
            return "OK";

        } catch (Exception e) {
            log.error("SET-CLOCK operation failed: {}", e.getMessage());
            throw new Exception("SET-CLOCK failed: " + e.getMessage(), e);
        }
    }

    /**
     * Convert OBIS code to DLMS Logical Name (6 bytes).
     *
     * @param obis OBIS code string (e.g., "1.0.94.91.7.255")
     * @return 6-byte logical name
     * @throws Exception if OBIS is invalid
     */
    private byte[] parseObisToLogicalName(String obis) throws Exception {
        try {
            String[] parts = obis.split("\\.");
            if (parts.length != 6) {
                throw new IllegalArgumentException("OBIS must have 6 parts, got: " + parts.length);
            }

            byte[] ln = new byte[6];
            for (int i = 0; i < 6; i++) {
                ln[i] = (byte) Integer.parseInt(parts[i]);
            }
            return ln;
        } catch (Exception e) {
            throw new Exception("Invalid OBIS code: " + obis, e);
        }
    }

    /**
     * Convert string value to appropriate Java type (int, float, bool, string).
     *
     * @param value String value
     * @return Converted value
     */
    private Object convertValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        try {
            if (value.matches("-?\\d+")) {
                return Integer.parseInt(value);
            }
            if (value.matches("-?\\d+\\.\\d+")) {
                return Double.parseDouble(value);
            }
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(value);
            }
            return value;
        } catch (Exception e) {
            log.warn("Failed to convert value '{}', using as string", value);
            return value;
        }
    }

    /**
     * Convert a string value to match a SPECIFIC, known-correct DLMS wire type -
     * the reliable counterpart to convertValue()'s blind guessing. Used for WRITE
     * once the attribute's real type is known (either from the cache or a fresh
     * read-detect), so the value is encoded exactly as the meter's object model
     * expects instead of inferred from the string's shape.
     *
     * @param value raw string value from the request
     * @param dataType the exact DLMS type to convert into
     * @return the value as the Java type Gurux expects for that DataType
     */
    private Object convertValueForType(String value, DataType dataType) {
        switch (dataType) {
            case BOOLEAN:
                return Boolean.parseBoolean(value);
            case INT8:
                return Byte.parseByte(value);
            case UINT8:
                return Short.parseShort(value);
            case INT16:
                return Short.parseShort(value);
            case UINT16:
                return Integer.parseInt(value);
            case INT32:
                return Integer.parseInt(value);
            case UINT32:
                return Long.parseLong(value);
            case INT64:
                return Long.parseLong(value);
            case UINT64:
                return new BigInteger(value);
            case FLOAT32:
                return Float.parseFloat(value);
            case FLOAT64:
                return Double.parseDouble(value);
            case STRING:
            case STRING_UTF8:
                return value;
            case OCTET_STRING: {
                // e.g. SEMC's slotOnTime/slotOffTime (DLMS_format) fields: accept
                // a hex string like "12 00 00 00" or "12000000". If the value isn't
                // actually hex (e.g. the caller targeted the wrong attribute and
                // this type was learned for something else entirely), fail with a
                // clear message instead of a raw NumberFormatException.
                String hex = value.replaceAll("\\s+", "");
                if (hex.isEmpty() || hex.length() % 2 != 0 || !hex.matches("[0-9a-fA-F]+")) {
                    throw new IllegalArgumentException("Expected a hex string for an OCTET_STRING attribute, got: '"
                            + value + "' - check you're targeting the correct attribute index");
                }
                byte[] bytes = new byte[hex.length() / 2];
                for (int i = 0; i < hex.length(); i += 2) {
                    bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
                }
                return bytes;
            }
            default:
                // Unhandled/ambiguous type (e.g. NONE, structures, date/time,
                // bit-string) - those either aren't produced by this fallback in
                // practice or need their own explicit handling elsewhere
                // (BITSTRING/SET-CLOCK/WRITE-GROUP already do). Fall back to the
                // same inference convertValue() already does.
                return convertValue(value);
        }
    }

    /**
     * Build a DLMS bit-string value (e.g. a fault/status flag register) with the given
     * bit positions set to 1 and everything else 0, MSB-first within each byte - matching
     * the same bit numbering the meter's own fault/event code table uses.
     *
     * Example: buildBitStringValue("30", 64) sets only bit 30 (e.g. "MCB Trip") in a
     * 64-bit field, producing the same on-the-wire bytes as writing that flag directly.
     *
     * @param bitPositions comma-separated bit positions to set, e.g. "30" or "5,30"
     * @param bitLength total width of the bit-string in bits (must be > 0)
     * @return a GXBitString ready to pass to GXDLMSReader.write()
     */
    private GXBitString buildBitStringValue(String bitPositions, int bitLength) {
        if (bitPositions == null || bitPositions.isEmpty()) {
            throw new IllegalArgumentException("At least one bit position required for BITSTRING value");
        }
        if (bitLength <= 0) {
            throw new IllegalArgumentException("bitLength must be positive, got: " + bitLength);
        }

        byte[] bytes = new byte[(bitLength + 7) / 8];
        for (String part : bitPositions.split(",")) {
            int bit = Integer.parseInt(part.trim());
            if (bit < 0 || bit >= bitLength) {
                throw new IllegalArgumentException("Bit position " + bit + " out of range for a " + bitLength + "-bit field");
            }
            int byteIndex = bit / 8;
            int bitInByte = 7 - (bit % 8); // MSB-first within each byte
            bytes[byteIndex] |= (1 << bitInByte);
        }
        // GXBitString's second constructor argument is padBits (unused bits in the
        // last byte, 0-7) - NOT the total bit length. Passing bitLength directly
        // (as this did previously) silently produces a GXBitString whose own
        // .length() no longer matches the data, even though the raw bytes are
        // still correct - confirmed via gurux.dlms 4.0.81's actual bytecode.
        int padBits = (8 - (bitLength % 8)) % 8;
        return new GXBitString(bytes, padBits);
    }

    /**
     * DLMS date-time values are encoded as exactly 12 raw bytes (year, month, day,
     * day-of-week, hour, minute, second, hundredths, deviation, status) - see
     * convertByteArrayToString() for the decoding.
     */
    private static final int DLMS_DATETIME_BYTE_LENGTH = 12;

    /**
     * Convert any value type to string for response. Recurses into arrays/lists
     * (e.g. a Profile Generic row), since a byte[] representing a timestamp or an
     * identifier is often nested inside one rather than being the top-level value.
     *
     * @param value Value to convert
     * @return String representation
     */
    private String convertToString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number) {
            Number num = (Number) value;
            if (num instanceof Double || num instanceof Float) {
                return String.format("%.2f", num);
            }
            return num.toString();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "true" : "false";
        }
        if (value instanceof byte[]) {
            return convertByteArrayToString((byte[]) value);
        }
        if (value instanceof java.util.List) {
            return convertIterableToString((java.util.List<?>) value);
        }
        if (value instanceof Object[]) {
            return convertIterableToString(java.util.Arrays.asList((Object[]) value));
        }
        if (value instanceof GXDateTime) {
            return convertDateTimeToEpochMillis((GXDateTime) value);
        }
        if (value instanceof java.util.Date) {
            return String.valueOf(((java.util.Date) value).getTime());
        }
        return value.toString();
    }

    /**
     * Profile Generic columns (e.g. MR history) arrive as Gurux date-time objects,
     * not raw bytes. Rebuilds the meter's wall-clock time in METER_TIME_ZONE, the
     * same way convertByteArrayToString() does for block load timestamps, so both
     * buffers produce identical epoch values. Returns "0" for dates the meter did not
     * fill in (no calendar, or a year outside 2000-2100).
     */
    private String convertDateTimeToEpochMillis(GXDateTime dateTime) {
        java.util.Calendar raw = dateTime.getMeterCalendar();
        if (raw == null) {
            return "0";
        }
        int year = raw.get(java.util.Calendar.YEAR);
        if (year < 2000 || year > 2100) {
            return "0";
        }
        java.util.Calendar corrected = java.util.Calendar.getInstance(METER_TIME_ZONE);
        corrected.clear();
        corrected.set(year, raw.get(java.util.Calendar.MONTH),
                raw.get(java.util.Calendar.DAY_OF_MONTH), raw.get(java.util.Calendar.HOUR_OF_DAY),
                raw.get(java.util.Calendar.MINUTE), raw.get(java.util.Calendar.SECOND));
        return String.valueOf(corrected.getTimeInMillis());
    }

    private String convertIterableToString(java.util.List<?> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(convertToString(items.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Timezone this meter fleet's date-time fields are recorded in. Deliberately
     * fixed rather than derived from the DLMS "deviation" field embedded in each
     * date-time value: Gurux's own handling of that field is unreliable (observed:
     * an IST meter's deviation of +330 minutes produced a GXDateTime attached to
     * timezone GMT-05:30 instead of GMT+05:30, silently doubling the offset when
     * converted to epoch millis - 12:30:57 local came out as 23:30:57). The meter's
     * raw wall-clock fields (year/month/day/hour/minute/second) are trustworthy on
     * their own; only Gurux's timezone attachment is not.
     */
    private static final java.util.TimeZone METER_TIME_ZONE = java.util.TimeZone.getTimeZone("Asia/Kolkata");

    /**
     * A byte array coming back from the meter is either a DLMS date-time (always
     * exactly 12 bytes) or an opaque identifier (MAC address, serial, etc.). A
     * 12-byte value is decoded into an epoch-milliseconds timestamp; anything else
     * is shown as hex.
     */
    private String convertByteArrayToString(byte[] bytes) {
        if (bytes.length == DLMS_DATETIME_BYTE_LENGTH) {
            try {
                Object dateTime = gurux.dlms.GXDLMSClient.changeType(bytes, gurux.dlms.enums.DataType.DATETIME);
                if (dateTime instanceof GXDateTime) {
                    java.util.Calendar raw = ((GXDateTime) dateTime).getMeterCalendar();
                    java.util.Calendar corrected = java.util.Calendar.getInstance(METER_TIME_ZONE);
                    corrected.clear();
                    corrected.set(raw.get(java.util.Calendar.YEAR), raw.get(java.util.Calendar.MONTH),
                            raw.get(java.util.Calendar.DAY_OF_MONTH), raw.get(java.util.Calendar.HOUR_OF_DAY),
                            raw.get(java.util.Calendar.MINUTE), raw.get(java.util.Calendar.SECOND));
                    return String.valueOf(corrected.getTimeInMillis());
                }
            } catch (Exception e) {
                log.debug("12-byte value is not a valid DLMS date-time, showing as hex instead: {}", e.getMessage());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Parse datetime string to appropriate format for meter.
     * Supports: ISO format (2024-08-27T14:30:45Z), epoch milliseconds
     *
     * @param datetime Datetime string
     * @return Parsed datetime object
     * @throws Exception if parsing fails
     */
    private Object parseDateTime(String datetime) throws Exception {
        if (datetime == null || datetime.isEmpty()) {
            throw new IllegalArgumentException("DateTime required");
        }

        try {
            // Try ISO format first: "2024-08-27T14:30:45Z"
            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
            Date date = isoFormat.parse(datetime.replace("Z", "+0000"));
            return new Date(date.getTime());

        } catch (ParseException e1) {
            try {
                // Try simple format: "2024-08-27 14:30:45"
                SimpleDateFormat simpleFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                Date date = simpleFormat.parse(datetime);
                return new Date(date.getTime());

            } catch (ParseException e2) {
                try {
                    // Try epoch milliseconds
                    long epochMs = Long.parseLong(datetime);
                    return new Date(epochMs);

                } catch (NumberFormatException e3) {
                    throw new Exception("Invalid datetime format: " + datetime +
                        " (expected: ISO format or yyyy-MM-dd HH:mm:ss or epoch milliseconds)", e1);
                }
            }
        }
    }

    /**
     * Apply optional client/server address and national standard variant, if the
     * request specifies them. Left untouched (Gurux defaults apply) when unset -
     * some meters require specific values here to recognize the client at all.
     *
     * @param client the DLMS client to configure
     * @param request may specify clientAddress, serverAddress, standard
     * @throws Exception if an unrecognized standard name is given
     */
    private void applyProtocolAddressing(GXDLMSSecureClient2 client, DlmsRequest request) throws Exception {
        if (request.getClientAddress() > 0) {
            client.setClientAddress(request.getClientAddress());
        }
        if (request.getServerAddress() > 0) {
            client.setServerAddress(request.getServerAddress());
        }
        if (request.getStandard() != null && !request.getStandard().isEmpty()) {
            try {
                client.setStandard(Standard.valueOf(request.getStandard().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new Exception("Unknown standard: " + request.getStandard() +
                        " (expected one of: DLMS, INDIA, ITALY, SAUDI_ARABIA, IDIS, SPAIN)", e);
            }
        }
    }

    /**
     * DLMS-reserved "Public Client" address. Many meters expose the invocation
     * counter only to this address, unauthenticated - the real client address
     * (e.g. 48) is often reserved for the secured session only and may not answer
     * this unsecured question at all.
     */
    private static final int PUBLIC_CLIENT_ADDRESS = 16;

    /**
     * Read the invocation counter from the meter (OBIS 0.0.43.1.0.255), unsecured.
     * Tries the DLMS "Public Client" address (16) first - the standard, dedicated
     * address many meters reserve for exactly this kind of unauthenticated read -
     * then falls back to the real client address (still unsecured) if that fails.
     * Falls back to counter=0 if neither works (meter will reject if its actual
     * counter is higher - this is a last resort, not a safe guess).
     *
     * @param client GXDLMSSecureClient2 to use for the probe
     * @param connection TCP connection to meter (reset between attempts)
     * @param realClientAddress the client address the real secured session will use
     *        (0 if not explicitly configured), tried as a second attempt
     * @return Current invocation counter value from meter (0 if both attempts fail)
     */
    private int readCounterFromMeter(GXDLMSSecureClient2 client, IGXMedia connection, int realClientAddress) {
        log.info("Reading invocation counter from meter (OBIS 0.0.43.1.0.255 unsecured)");

        Integer counter = tryReadCounterUnsecured(client, connection, PUBLIC_CLIENT_ADDRESS);
        if (counter != null) {
            return counter;
        }

        if (realClientAddress > 0 && realClientAddress != PUBLIC_CLIENT_ADDRESS) {
            counter = tryReadCounterUnsecured(client, connection, realClientAddress);
            if (counter != null) {
                return counter;
            }
        }

        log.warn("Could not read invocation counter via client {} or {} - falling back to counter=0 " +
                "(meter will reject if its actual counter is higher)", PUBLIC_CLIENT_ADDRESS, realClientAddress);
        return 0;
    }

    /**
     * One unsecured counter-read attempt using the given client address. The
     * connection is closed and reopened afterward regardless of outcome, so a
     * partial/failed handshake here can't leave the socket in a bad state for
     * whatever uses it next (a second attempt, or the real secured session).
     *
     * @return the counter value, or null if this attempt failed
     */
    private Integer tryReadCounterUnsecured(GXDLMSSecureClient2 client, IGXMedia connection, int clientAddress) {
        try {
            client.setClientAddress(clientAddress);
            // Set to NO_SECURITY for the counter read - this happens BEFORE the real
            // HLSE association, so no encryption is available yet. A DLMS association
            // (AARQ/AARE) is still required even for an unsecured conversation.
            client.setAuthentication(Authentication.NONE);

            GXDLMSReader counterReader = new GXDLMSReader(client, connection);
            counterReader.initializeConnection();
            // Invocation counter is conventionally a plain Data object (class 1),
            // attribute 2 holds its value.
            Object counterValue = counterReader.read("0.0.43.1.0.255", 1, 2);

            int result = parseCounterValue(counterValue);
            log.info("✅ Counter read SUCCESS via client address {}: {}", clientAddress, result);
            return result;
        } catch (Exception e) {
            log.warn("Counter read via client address {} failed: {}", clientAddress, e.getMessage());
            return null;
        } finally {
            try {
                connection.close();
                connection.open();
            } catch (Exception e) {
                log.warn("Failed to reset connection after counter probe attempt: {}", e.getMessage());
            }
        }
    }

    /**
     * Convert a counter attribute's raw value (Number, or a big/16/8-bit-endian
     * byte array) into a plain int.
     *
     * @throws IllegalArgumentException if the value is null or an unrecognized type
     */
    private int parseCounterValue(Object counterValue) {
        if (counterValue == null) {
            throw new IllegalArgumentException("Meter returned null value");
        }
        if (counterValue instanceof Number) {
            return ((Number) counterValue).intValue();
        }
        if (counterValue instanceof byte[]) {
            byte[] bytes = (byte[]) counterValue;
            if (bytes.length >= 4) {
                return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) |
                        ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
            } else if (bytes.length == 2) {
                return ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
            } else if (bytes.length == 1) {
                return bytes[0] & 0xFF;
            }
            throw new IllegalArgumentException("Unexpected counter byte length: " + bytes.length);
        }
        throw new IllegalArgumentException("Unknown response type: " + counterValue.getClass().getSimpleName());
    }

    public Object getPoolStats() {
        return connectionPool.getStats();
    }
}
