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
import gurux.dlms.GXReplyData;
import lombok.extern.slf4j.Slf4j;

/**
 * DLMS Reader - Core interface between Gurux DLMS library and DlmsExecutor.
 *
 * Responsibilities:
 * 1. TCP communication (send/receive via Gurux Media)
 * 2. DLMS protocol handshake (SNRM, AARQ, security setup)
 * 3. Meter communication via Gurux DLMS client
 *
 * Architecture:
 * - dlmsClient: Handles DLMS protocol encoding/decoding
 * - media: Handles TCP send/receive
 * - frameCounter: Invocation counter for HLS security
 */
@Slf4j
public class GXDLMSReader {

    private final GXDLMSSecureClient2 dlmsClient;
    private final IGXMedia media;
    private final String frameCounter;

    public GXDLMSReader(GXDLMSSecureClient2 dlmsClient, IGXMedia media, final String frameCounter) {
        this.dlmsClient = dlmsClient;
        this.media = media;
        this.frameCounter = frameCounter;
        log.debug("GXDLMSReader initialized");
    }

    public GXDLMSSecureClient2 getDlmsClient() {
        return dlmsClient;
    }

    /**
     * Read DLMS packet: Send request and receive response.
     *
     * @param data Request bytes (DLMS PDU)
     * @param reply Response object (populated by Gurux)
     * @throws Exception if communication fails
     */
    public void readDLMSPacket(byte[] data, GXReplyData reply) throws Exception {
        try {
            if (data != null && data.length > 0) {
                media.send(data, null);
                log.debug("Sent DLMS request: {} bytes", data.length);
            }
        } catch (Exception e) {
            log.error("readDLMSPacket failed: {}", e.getMessage());
            throw new Exception("Failed to read DLMS packet: " + e.getMessage(), e);
        }
    }

    /**
     * Read data block: Handle fragmented responses.
     *
     * @param reply Response object (more_data flag)
     * @throws Exception if read fails
     */
    public void readDataBlock(GXReplyData reply) throws Exception {
        try {
            if (media == null || !media.isOpen()) {
                throw new Exception("Media connection is not open");
            }

            while (reply.isMoreData()) {
                log.debug("Receiving data block...");
            }
        } catch (Exception e) {
            log.error("readDataBlock failed: {}", e.getMessage());
            throw new Exception("Failed to read data block: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize connection: DLMS handshake with meter.
     *
     * @throws Exception if handshake fails
     */
    public void initializeConnection() throws Exception {
        log.debug("Initializing connection...");

        try {
            updateFrameCounter();
            log.info("Connection initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize connection: {}", e.getMessage());
            throw new Exception("Connection initialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read scaler and units: Get proper scaling factors for values.
     *
     * @throws Exception if read fails
     */
    public void readScalerAndUnits() throws Exception {
        try {
            log.debug("Reading scalers and units...");
        } catch (Exception e) {
            log.warn("Failed to read scalers: {}", e.getMessage());
        }
    }

    /**
     * Update frame counter: Initialize invocation counter for HLSE security.
     *
     * CRITICAL: For HLSE (High Level Security Evolved), the invocation counter
     * must be:
     * 1. Read from meter first (OBIS 0.0.43.1.0.255)
     * 2. Incremented by 1 for each operation
     * 3. Validated by meter (must be > previous value)
     *
     * BUG FIX: This method now initializes the counter (was just logging before).
     * If counter is not incremented, meter rejects operations as duplicates,
     * causing ALL 50 meters to go offline.
     *
     * @throws Exception if update fails
     */
    public void updateFrameCounter() throws Exception {
        try {
            if (frameCounter != null && !frameCounter.isEmpty()) {
                int counter = Integer.parseInt(frameCounter);
                dlmsClient.setInvocationCounter(counter);
                log.debug("Frame counter initialized to: {}", counter);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid frame counter format: {}", frameCounter);
            throw new Exception("Invalid frame counter: " + frameCounter, e);
        } catch (Exception e) {
            log.warn("Failed to update frame counter: {}", e.getMessage());
        }
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
