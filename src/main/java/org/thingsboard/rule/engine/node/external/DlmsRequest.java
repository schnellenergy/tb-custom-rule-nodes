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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO (Data Transfer Object) for DLMS downlink request.
 * Contains all parameters needed to execute a DLMS operation on a smart meter.
 *
 * Flows: RuleChain → TbDlmsDownlinkNode → DlmsRequest → DlmsExecutor → SmartMeter
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DlmsRequest {

    // ===== Network Parameters =====
    /** Smart meter IP address (e.g., "192.168.1.1" or IPv6) */
    private String host;

    /** Smart meter TCP port (e.g., 4059) */
    private int port;

    // ===== DLMS Parameters =====
    /** OBIS code (e.g., "1.0.94.91.7.255" for energy, "0.0.1.0.0.255" for clock) */
    private String obis;

    /** DLMS class ID (e.g., 7 for Profile Generic, 1 for Data, 8 for Clock) */
    private int classId;

    /** Attribute index (e.g., 2 for value, 3 for scaler) */
    private int attribute;

    // ===== Operation Parameters =====
    /** Operation type: "READ", "WRITE", "ACTION", "SET-CLOCK", "WRITE-GROUP" */
    private String action;

    /** Value to write (for WRITE, SET-CLOCK, ACTION operations) */
    private String value;

    /** Method index (for ACTION operations, e.g., 1 for relay disconnect, 2 for connect) */
    private int method;

    // ===== Security Parameters =====
    /** Block Cipher Key (EK) for AES-GCM encryption (hex string, 16 or 32 bytes) */
    private String blockCipherKey;

    /** Authentication Key (AK) for HMAC authentication (hex string, 16 bytes) */
    private String authenticationKey;

    /** System Title for sender identification (hex string, 8 bytes, e.g., "HES00001") */
    private String systemTitle;

    /** Invocation counter for replay protection (read from meter, incremented each operation) */
    private String counter;

    // ===== Timeout Parameters =====
    /** Timeout in seconds for meter response (default: 30 seconds) */
    private int timeout = 30;
}
