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

import gurux.dlms.GXICipher;
import gurux.dlms.enums.Authentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit Tests for GXDLMSSecureClient2
 *
 * This is the security-critical class responsible for HLS/HLSE authentication
 * setup. A prior production incident took 50 meters offline because keys were
 * validated but never wired into Gurux's cipher engine - these tests exist to
 * catch that exact class of regression (see testConfigureHLSE_*_WiresKeysToCipher).
 *
 * Test Categories:
 * ✓ Invocation Counter (4): get/set/increment behavior
 * ✓ HLS (password) Authentication (3): valid/null/empty password
 * ✓ HLSE Key Validation (9): missing/invalid-length/invalid-hex keys
 * ✓ HLSE Cipher Wiring (4): keys actually reach Gurux's cipher, not just validated
 * ✓ Hex Parsing Edge Cases (4): spaces, case, ASCII-derived keys, 32-byte cipher key
 */
public class GXDLMSSecureClient2Test {

    private static final String VALID_BLOCK_CIPHER_KEY = "00112233445566778899AABBCCDDEEFF";
    private static final String VALID_AUTH_KEY = "FFEEDDCCBBAA99887766554433221100";
    private static final String VALID_SYSTEM_TITLE = "48455330000001FF";

    private GXDLMSSecureClient2 client;

    @BeforeEach
    void setUp() {
        client = new GXDLMSSecureClient2(true);
    }

    // ════════════════════════════════════════════════════════════════════
    // INVOCATION COUNTER TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testConstructor_InitializesInvocationCounterToZero() {
        assertEquals(0, client.getInvocationCounter());
    }

    @Test
    void testSetInvocationCounter_UpdatesValue() {
        client.setInvocationCounter(12345);

        assertEquals(12345, client.getInvocationCounter());
    }

    @Test
    void testIncrementInvocationCounter_IncreasesByOne() {
        client.setInvocationCounter(100);

        client.incrementInvocationCounter();

        assertEquals(101, client.getInvocationCounter());
    }

    @Test
    void testIncrementInvocationCounter_MultipleCalls_AccumulateCorrectly() {
        client.setInvocationCounter(0);

        client.incrementInvocationCounter();
        client.incrementInvocationCounter();
        client.incrementInvocationCounter();

        assertEquals(3, client.getInvocationCounter());
    }

    // ════════════════════════════════════════════════════════════════════
    // HLS (PASSWORD) AUTHENTICATION TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testConfigureHLS_ValidPassword_SetsHighAuthentication() throws Exception {
        client.configureHLS("12345678");

        assertEquals(Authentication.HIGH, client.getAuthentication());
    }

    @Test
    void testConfigureHLS_NullPassword_ThrowsException() {
        Exception ex = assertThrows(Exception.class, () -> client.configureHLS(null));
        assertTrue(ex.getMessage().contains("Password required"));
    }

    @Test
    void testConfigureHLS_EmptyPassword_ThrowsException() {
        Exception ex = assertThrows(Exception.class, () -> client.configureHLS(""));
        assertTrue(ex.getMessage().contains("Password required"));
    }

    // ════════════════════════════════════════════════════════════════════
    // HLS-WITH-ENCRYPTION TESTS (password + keys, Authentication.HIGH -
    // a different mechanism than HLSE/GMAC, needed by some meters in the field)
    // ════════════════════════════════════════════════════════════════════

    private static final String TEST_PASSWORD = "SALSMSCHNELL1060";

    @Test
    void testConfigureHLSWithEncryption_ValidInputs_SetsHighAuthentication() throws Exception {
        client.configureHLSWithEncryption(TEST_PASSWORD, VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE);

        assertEquals(Authentication.HIGH, client.getAuthentication());
    }

    @Test
    void testConfigureHLSWithEncryption_WiresKeysAndAuthenticationEncryptionSecurity() throws Exception {
        client.configureHLSWithEncryption(TEST_PASSWORD, VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE);

        GXICipher cipher = client.getCipher();
        assertNotNull(cipher);
        assertArrayEquals(hexToBytes(VALID_BLOCK_CIPHER_KEY), cipher.getBlockCipherKey());
        assertArrayEquals(hexToBytes(VALID_AUTH_KEY), cipher.getAuthenticationKey());
        assertArrayEquals(hexToBytes(VALID_SYSTEM_TITLE), cipher.getSystemTitle());
        assertEquals(gurux.dlms.enums.Security.AUTHENTICATION_ENCRYPTION, cipher.getSecurity());
    }

    @Test
    void testConfigureHLSWithEncryption_NullPassword_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSWithEncryption(null, VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Password required"));
    }

    @Test
    void testConfigureHLSWithEncryption_EmptyPassword_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSWithEncryption("", VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Password required"));
    }

    @Test
    void testConfigureHLSWithEncryption_MissingBlockCipherKey_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSWithEncryption(TEST_PASSWORD, null, VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Block Cipher Key required"));
    }

    @Test
    void testConfigureHLSWithEncryption_MissingAuthenticationKey_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSWithEncryption(TEST_PASSWORD, VALID_BLOCK_CIPHER_KEY, null, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Authentication Key required"));
    }

    @Test
    void testConfigureHLSWithEncryption_MissingSystemTitle_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSWithEncryption(TEST_PASSWORD, VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, null));
        assertTrue(ex.getMessage().contains("System Title required"));
    }

    @Test
    void testConfigureHLSWithEncryption_InvalidKeyLength_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSWithEncryption(TEST_PASSWORD, "1234", VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("must be 16 bytes"));
    }

    @Test
    void testConfigureHLSWithEncryption_DoesNotAffectPlainHLSE() throws Exception {
        // Regression check: adding this new mode must not change the existing,
        // already-shipped HLSE (no password) behavior on a fresh client instance.
        GXDLMSSecureClient2 hlseClient = new GXDLMSSecureClient2(true);

        hlseClient.configureHLSE(VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE);

        assertEquals(Authentication.HIGH_GMAC, hlseClient.getAuthentication());
    }

    // ════════════════════════════════════════════════════════════════════
    // HLSE KEY VALIDATION TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testConfigureHLSE_ValidKeys_SetsHighGmacAuthentication() throws Exception {
        client.configureHLSE(VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE);

        assertEquals(Authentication.HIGH_GMAC, client.getAuthentication());
    }

    @Test
    void testConfigureHLSE_NullBlockCipherKey_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE(null, VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Block Cipher Key required"));
    }

    @Test
    void testConfigureHLSE_EmptyBlockCipherKey_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE("", VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Block Cipher Key required"));
    }

    @Test
    void testConfigureHLSE_NullAuthenticationKey_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE(VALID_BLOCK_CIPHER_KEY, null, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Authentication Key required"));
    }

    @Test
    void testConfigureHLSE_NullSystemTitle_ThrowsException() {
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE(VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, null));
        assertTrue(ex.getMessage().contains("System Title required"));
    }

    @Test
    void testConfigureHLSE_BlockCipherKeyTooShort_ThrowsException() {
        // 4 hex chars = 2 bytes; must be exactly 16
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE("1234", VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("must be 16 bytes"));
    }

    @Test
    void testConfigureHLSE_AuthenticationKeyWrongLength_ThrowsException() {
        // 8 hex chars = 4 bytes; must be exactly 16
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE(VALID_BLOCK_CIPHER_KEY, "00112233", VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Authentication Key must be 16 bytes"));
    }

    @Test
    void testConfigureHLSE_SystemTitleWrongLength_ThrowsException() {
        // 8 hex chars = 4 bytes; must be exactly 8
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE(VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, "00112233"));
        assertTrue(ex.getMessage().contains("System Title must be 8 bytes"));
    }

    @Test
    void testConfigureHLSE_InvalidHexCharacters_ThrowsException() {
        // 'Z' is not a valid hex digit
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Invalid hex character"));
    }

    @Test
    void testConfigureHLSE_OddLengthHexString_ThrowsException() {
        // 31 chars (odd) cannot be split into whole bytes
        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE("0011223344556677889900112233445", VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("even length"));
    }

    // ════════════════════════════════════════════════════════════════════
    // HLSE CIPHER WIRING TESTS (regression tests for the production incident)
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testConfigureHLSE_WiresBlockCipherKeyToGuruxCipher() throws Exception {
        client.configureHLSE(VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE);

        GXICipher cipher = client.getCipher();
        assertNotNull(cipher, "Cipher must be reachable after configureHLSE");
        assertArrayEquals(hexToBytes(VALID_BLOCK_CIPHER_KEY), cipher.getBlockCipherKey(),
                "Block cipher key bytes must actually reach Gurux's cipher, not just pass validation");
    }

    @Test
    void testConfigureHLSE_WiresAuthenticationKeyToGuruxCipher() throws Exception {
        client.configureHLSE(VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE);

        GXICipher cipher = client.getCipher();
        assertArrayEquals(hexToBytes(VALID_AUTH_KEY), cipher.getAuthenticationKey(),
                "Authentication key bytes must actually reach Gurux's cipher, not just pass validation");
    }

    @Test
    void testConfigureHLSE_WiresSystemTitleToGuruxCipher() throws Exception {
        client.configureHLSE(VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE);

        GXICipher cipher = client.getCipher();
        assertArrayEquals(hexToBytes(VALID_SYSTEM_TITLE), cipher.getSystemTitle(),
                "System title bytes must actually reach Gurux's cipher, not just pass validation");
    }

    @Test
    void testConfigureHLSE_SetsAuthenticationEncryptionSecurity() throws Exception {
        // Regression test: left unset, Gurux defaults to Security.NONE and sends the
        // application context name for an unciphered connection - meters requiring the
        // ciphered LN context reject the AARQ outright ("Connection is permanently
        // rejected... Meter expects Logical Name referencing with secured connection")
        // before any keys are even checked. Found via a real meter rejecting this
        // exact no-password (HLSE) path, even though the keys themselves were correct.
        client.configureHLSE(VALID_BLOCK_CIPHER_KEY, VALID_AUTH_KEY, VALID_SYSTEM_TITLE);

        GXICipher cipher = client.getCipher();
        assertEquals(gurux.dlms.enums.Security.AUTHENTICATION_ENCRYPTION, cipher.getSecurity(),
                "HLSE must set the cipher's security level, or the meter rejects the connection before checking keys");
    }

    @Test
    void testConfigureHLSE_KeysAreNotSwapped() throws Exception {
        // Distinct keys catch a copy-paste bug that wires EK/AK to the wrong setter
        String distinctCipherKey = "11111111111111111111111111111111";
        String distinctAuthKey = "22222222222222222222222222222222";

        client.configureHLSE(distinctCipherKey, distinctAuthKey, VALID_SYSTEM_TITLE);

        GXICipher cipher = client.getCipher();
        assertArrayEquals(hexToBytes(distinctCipherKey), cipher.getBlockCipherKey());
        assertArrayEquals(hexToBytes(distinctAuthKey), cipher.getAuthenticationKey());
        assertFalse(java.util.Arrays.equals(cipher.getBlockCipherKey(), cipher.getAuthenticationKey()),
                "Block cipher key and authentication key must not be swapped/identical");
    }

    // ════════════════════════════════════════════════════════════════════
    // HEX PARSING EDGE CASE TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testConfigureHLSE_KeysWithSpaces_ParsedCorrectly() throws Exception {
        client.configureHLSE(
                "00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF",
                "FF EE DD CC BB AA 99 88 77 66 55 44 33 22 11 00",
                "48 45 53 30 00 00 01 FF");

        GXICipher cipher = client.getCipher();
        assertArrayEquals(hexToBytes(VALID_BLOCK_CIPHER_KEY), cipher.getBlockCipherKey());
        assertArrayEquals(hexToBytes(VALID_AUTH_KEY), cipher.getAuthenticationKey());
    }

    @Test
    void testConfigureHLSE_LowercaseHex_ParsedCorrectly() throws Exception {
        client.configureHLSE(VALID_BLOCK_CIPHER_KEY.toLowerCase(), VALID_AUTH_KEY.toLowerCase(),
                VALID_SYSTEM_TITLE.toLowerCase());

        GXICipher cipher = client.getCipher();
        assertArrayEquals(hexToBytes(VALID_BLOCK_CIPHER_KEY), cipher.getBlockCipherKey());
    }

    @Test
    void testConfigureHLSE_32ByteBlockCipherKey_ThrowsException() {
        // Gurux's GXCiphering only accepts a 16-byte AES-128 block cipher key;
        // a 32-byte (AES-256) key is rejected even though it is valid hex.
        String key32 = VALID_BLOCK_CIPHER_KEY + VALID_BLOCK_CIPHER_KEY.substring(0, 32);
        assertEquals(64, key32.length());

        Exception ex = assertThrows(Exception.class,
                () -> client.configureHLSE(key32, VALID_AUTH_KEY, VALID_SYSTEM_TITLE));
        assertTrue(ex.getMessage().contains("Block Cipher Key must be 16 bytes"));
    }

    @Test
    void testConfigureHLSE_AsciiDerivedTestKeys_MatchManualHexConversion() throws Exception {
        // Meter test keys are often plain ASCII (e.g. "ssssssssssssssss", "12345678").
        // This verifies that hex-encoding them byte-for-byte before calling
        // configureHLSE produces exactly the bytes the meter expects.
        String asciiKey = "ssssssssssssssss";
        String asciiSystemTitle = "12345678";
        String hexKey = asciiToHex(asciiKey);
        String hexSystemTitle = asciiToHex(asciiSystemTitle);

        client.configureHLSE(hexKey, hexKey, hexSystemTitle);

        GXICipher cipher = client.getCipher();
        assertArrayEquals(asciiKey.getBytes(java.nio.charset.StandardCharsets.US_ASCII), cipher.getBlockCipherKey());
        assertArrayEquals(asciiSystemTitle.getBytes(java.nio.charset.StandardCharsets.US_ASCII), cipher.getSystemTitle());
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════

    private static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("\\s", "");
        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            result[i / 2] = (byte) Integer.parseInt(clean.substring(i, i + 2), 16);
        }
        return result;
    }

    private static String asciiToHex(String ascii) {
        StringBuilder sb = new StringBuilder();
        for (byte b : ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
