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
    /** Operation type: "READ", "WRITE", "ACTION", "SET-CLOCK", "WRITE-GROUP", "READ-RANGE" */
    private String action;

    /**
     * Start/end of the range to read, only used for "READ-RANGE" (a selective-access
     * read of a Profile Generic buffer, e.g. block load survey / MR history) - epoch
     * seconds, matching the RPC's own fromTs/toTs convention. Both required together.
     */
    private long fromTs;
    private long toTs;

    /** Value to write (for WRITE, SET-CLOCK, ACTION operations) */
    private String value;

    /**
     * Optional explicit DLMS data type for WRITE, when the attribute is not a plain
     * number/string/boolean. Currently supported: "BITSTRING" (a flag/status register
     * where individual bits mean individual things, e.g. fault codes).
     *
     * When set to "BITSTRING": `value` is a comma-separated list of bit positions to set
     * to 1 (all others 0), e.g. "30" or "5,30" - matching the bit numbering used by the
     * meter's own fault/event code table. `bitLength` controls the total width.
     */
    private String dataType;

    /** Total bit-string width, only used when dataType="BITSTRING" (default: 64) */
    private int bitLength = 64;

    /** Method index (for ACTION operations, e.g., 1 for relay disconnect, 2 for connect) */
    private int method;

    /**
     * Named field values for "WRITE-GROUP" (a manufacturer-specific structure with
     * several named settings, e.g. SEMC's "operating mode" object). Only the fields
     * present here are changed; everything else in the structure is read from the
     * meter and left untouched. Field names and their meaning are entirely vendor-
     * specific - see SemcOperatingModeCodec for the current SEMC field list.
     *
     * If `obis`/`classId`/`attribute` are left unset, they default to SEMC's
     * operating-mode structure (1.131.128.128.128.255, class 1, attribute 2) -
     * confirmed via a live READ against a real meter; other SEMC meter variants
     * may use a different OBIS for this same structure.
     */
    private java.util.Map<String, Object> groupFields;

    // ===== Security Parameters =====
    /** Block Cipher Key (EK) for AES-128-GCM encryption (hex string, 16 bytes) */
    private String blockCipherKey;

    /** Authentication Key (AK) for HMAC authentication (hex string, 16 bytes) */
    private String authenticationKey;

    /** System Title for sender identification (hex string, 8 bytes, e.g., "HES00001") */
    private String systemTitle;

    /**
     * Optional password. When provided alongside the keys above, selects HLS-with-
     * encryption mode (Authentication.HIGH + AUTHENTICATION_ENCRYPTION) instead of the
     * default HLSE/GMAC mode - some meters require this combined password+key mode
     * rather than pure HLSE. Leave unset/empty for the default HLSE (no password) mode.
     */
    private String password;

    // ===== Protocol Addressing Parameters (optional; leave unset for Gurux defaults) =====
    /**
     * DLMS client (HDLC/wrapper) address. Some meters require a specific value here
     * to be recognized (e.g. 48 for certain India-standard meters). 0 = not set, use
     * the Gurux library default.
     */
    private int clientAddress;

    /**
     * DLMS server (meter) address. 0 = not set, use the Gurux library default.
     */
    private int serverAddress;

    /**
     * Optional explicit invocation counter value. When set (>= 0), skips the normal
     * unsecured meter probe entirely and uses this value directly for the one secured
     * association - matching how some external tools/meters expect the counter to be
     * supplied rather than read back first. Leave unset (-1, the default) to read the
     * counter from the meter as usual.
     */
    private int counter = -1;

    /**
     * Optional national/regional DLMS standard variant, e.g. "INDIA". Matches
     * gurux.dlms.enums.Standard names (case-insensitive). Leave unset for the
     * generic/international default.
     */
    private String standard;

    // ===== Timeout Parameters =====
    /** Timeout in seconds for meter response (default: 30 seconds) */
    private int timeout = 30;
}
