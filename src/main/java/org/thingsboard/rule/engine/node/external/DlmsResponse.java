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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO (Data Transfer Object) for DLMS downlink response.
 * Returned by DlmsExecutor after DLMS operation completes.
 *
 * Flows: SmartMeter → DlmsExecutor → DlmsResponse → TbDlmsDownlinkNode → RuleChain
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DlmsResponse {

    // ===== Success/Failure Status =====
    /** Operation success flag: true if meter responded successfully, false otherwise */
    private boolean success;

    /** Meter response code (e.g., "0" for success, "ERROR" for failure) */
    private String responseCode;

    // ===== Response Data =====
    /** Meter data (populated for READ operations, null for WRITE/ACTION operations) */
    private String data;

    /** Error message (populated if success=false) */
    private String errorMessage;

    // ===== Metadata =====
    /** Total operation duration in milliseconds */
    private long durationMs;

    /**
     * Helper method to check if operation succeeded
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Helper method to get data or error message
     */
    public String getResult() {
        return success ? data : errorMessage;
    }
}
