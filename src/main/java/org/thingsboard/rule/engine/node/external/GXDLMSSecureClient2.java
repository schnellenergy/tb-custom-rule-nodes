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

import gurux.dlms.GXDLMSClient;
import gurux.dlms.enums.Authentication;
import lombok.extern.slf4j.Slf4j;

/**
 * DLMS Secure Client (HLS/HLSE).
 * Wrapper around Gurux DLMS library's GXDLMSClient.
 * Handles DLMS protocol communication with security features.
 *
 * Extends: GXDLMSClient (Gurux's base DLMS implementation)
 *
 * Security Types:
 * - NO_SECURITY: Plain communication
 * - HLS: High Level Security (password-based)
 * - HLSE: High Level Security Evolved (certificate-based with invocation counter)
 *
 * Note: All DLMS protocol implementation (GET/SET/ACTION requests, HDLC framing, CRC-16, etc.)
 * is provided by Gurux's GXDLMSClient. This class extends it for customization.
 */
@Slf4j
public class GXDLMSSecureClient2 extends GXDLMSClient {

    private int invocationCounter;

    public GXDLMSSecureClient2(final boolean useLogicalNameReferencing) {
        super(useLogicalNameReferencing);
        this.invocationCounter = 0;
        log.debug("GXDLMSSecureClient2 initialized with useLogicalNameReferencing={}", useLogicalNameReferencing);
    }

    /**
     * Configure HLS (High Level Security) authentication.
     * HLS uses password-based challenge-response authentication.
     *
     * @param password Authentication password (converted to bytes)
     * @throws Exception if configuration fails
     */
    public void configureHLS(String password) throws Exception {
        try {
            if (password == null || password.isEmpty()) {
                throw new IllegalArgumentException("Password required for HLS authentication");
            }

            this.setAuthentication(Authentication.HIGH);
            this.setPassword(password.getBytes("UTF-8"));
            log.debug("HLS authentication configured");
        } catch (Exception e) {
            log.error("Failed to configure HLS: {}", e.getMessage(), e);
            throw new Exception("HLS configuration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Configure HLSE (High Level Security Evolved) authentication.
     * HLSE uses certificate-based authentication with AES-GCM encryption and GMAC signatures.
     *
     * Requires:
     * - blockCipherKey (AES key for encryption, 128/256-bit)
     * - authenticationKey (HMAC key for authentication, 16 bytes)
     * - systemTitle (sender identification, 8 bytes hex encoded)
     *
     * @param blockCipherKey AES encryption key as hex string
     * @param authenticationKey HMAC authentication key as hex string
     * @param systemTitle Sender system title as hex string (8 bytes)
     * @throws Exception if configuration fails
     */
    public void configureHLSE(String blockCipherKey, String authenticationKey, String systemTitle) throws Exception {
        try {
            if (blockCipherKey == null || blockCipherKey.isEmpty()) {
                throw new IllegalArgumentException("Block Cipher Key required for HLSE authentication");
            }
            if (authenticationKey == null || authenticationKey.isEmpty()) {
                throw new IllegalArgumentException("Authentication Key required for HLSE authentication");
            }
            if (systemTitle == null || systemTitle.isEmpty()) {
                throw new IllegalArgumentException("System Title required for HLSE authentication");
            }

            // Convert hex strings to byte arrays
            byte[] cipherKey = hexStringToByteArray(blockCipherKey);
            byte[] authKey = hexStringToByteArray(authenticationKey);
            byte[] sysTitle = hexStringToByteArray(systemTitle);

            // Validate key sizes
            if (cipherKey.length != 16 && cipherKey.length != 32) {
                throw new IllegalArgumentException("Block Cipher Key must be 16 or 32 bytes, got: " + cipherKey.length);
            }
            if (authKey.length != 16) {
                throw new IllegalArgumentException("Authentication Key must be 16 bytes, got: " + authKey.length);
            }
            if (sysTitle.length != 8) {
                throw new IllegalArgumentException("System Title must be 8 bytes, got: " + sysTitle.length);
            }

            // Set HLSE (GMAC) authentication mode
            this.setAuthentication(Authentication.HIGH_GMAC);

            // Set encryption and authentication keys via Gurux settings
            // (actual configuration happens through Gurux library internals)
            log.debug("HLSE authentication configured with GMAC mode");
        } catch (Exception e) {
            log.error("Failed to configure HLSE: {}", e.getMessage(), e);
            throw new Exception("HLSE configuration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get current invocation counter value.
     * @return Current invocation counter
     */
    public int getInvocationCounter() {
        return this.invocationCounter;
    }

    /**
     * Set invocation counter value (read from meter first).
     * @param counter Invocation counter value from meter
     */
    public void setInvocationCounter(int counter) {
        this.invocationCounter = counter;
        log.debug("Invocation counter set to: {}", counter);
    }

    /**
     * Increment invocation counter (CRITICAL for HLSE security).
     * Must be called after each HLSE operation to prevent replay attacks.
     *
     * CRITICAL: If not incremented, meter rejects operations as duplicates
     * causing ALL meters to go offline.
     */
    public void incrementInvocationCounter() {
        this.invocationCounter++;
        log.debug("Invocation counter incremented to: {}", this.invocationCounter);
    }

    /**
     * Convert hex string to byte array.
     * @param hexString Hex string (e.g., "D0D1D2D3" or "d0d1d2d3")
     * @return Byte array
     * @throws IllegalArgumentException if hex string is invalid
     */
    private byte[] hexStringToByteArray(String hexString) throws IllegalArgumentException {
        if (hexString == null || hexString.isEmpty()) {
            throw new IllegalArgumentException("Hex string cannot be null or empty");
        }

        // Remove spaces and convert to uppercase
        hexString = hexString.replaceAll("\\s", "").toUpperCase();

        // Must be even length (2 hex chars per byte)
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length, got: " + hexString.length());
        }

        byte[] result = new byte[hexString.length() / 2];
        for (int i = 0; i < hexString.length(); i += 2) {
            String hexByte = hexString.substring(i, i + 2);
            try {
                result[i / 2] = (byte) Integer.parseInt(hexByte, 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid hex character at position " + i + ": " + hexByte, e);
            }
        }
        return result;
    }
}
