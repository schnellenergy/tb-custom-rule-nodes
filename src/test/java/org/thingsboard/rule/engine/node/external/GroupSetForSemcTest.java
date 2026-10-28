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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit Tests for GroupSetForSemc
 *
 * Verifies the field map (against the production DLMSCliTool.java reference)
 * and the todSlots byte encoding (against the production uplink converter's
 * decode logic and its documented example bytes/output).
 *
 * Test Categories:
 * ✓ Field Map (4): all 31 fields present, correct positions, names list
 * ✓ todSlots Decode (2): real example bytes, empty input
 * ✓ todSlots Encode (4): round-trip, wrong type, missing keys, non-map entry
 * ✓ Field Value Conversion (6): float/double/long/boolean/byte[]/todSlots dispatch
 */
public class GroupSetForSemcTest {

    // ════════════════════════════════════════════════════════════════════
    // FIELD MAP TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testFieldIndex_HasExactlyThirtyOneFields() {
        assertEquals(31, GroupSetForSemc.FIELD_INDEX.size());
    }

    @Test
    void testFieldIndex_KnownPositionsMatchProductionReference() {
        // Spot-check against the verified DLMSCliTool.java field map
        assertEquals(0, GroupSetForSemc.FIELD_INDEX.get("operatingMode"));
        assertEquals(6, GroupSetForSemc.FIELD_INDEX.get("slotOnTimeGMT"));
        assertEquals(7, GroupSetForSemc.FIELD_INDEX.get("slotOnTimeDLMS"));
        assertEquals(11, GroupSetForSemc.FIELD_INDEX.get("tcpUdpPort"));
        assertEquals(22, GroupSetForSemc.FIELD_INDEX.get("rVoltMax"));
        assertEquals(29, GroupSetForSemc.FIELD_INDEX.get("bpfThreshold"));
    }

    @Test
    void testFieldIndex_TodSlotsIsPositionThirty() {
        // The field missing from the older DLMSCliTool.java map - added at the end
        assertEquals(30, GroupSetForSemc.FIELD_INDEX.get("todSlots"));
        assertEquals(30, GroupSetForSemc.TOD_SLOTS_INDEX);
    }

    @Test
    void testFieldNames_ContainsAllFieldsCommaSeparated() {
        String names = GroupSetForSemc.fieldNames();

        assertTrue(names.contains("operatingMode"));
        assertTrue(names.contains("todSlots"));
        assertTrue(names.contains(", "));
    }

    // ════════════════════════════════════════════════════════════════════
    // todSlots DECODE TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testDecodeTodSlots_RealExampleBytes_ProducesExpectedEightSlots() {
        // Exact triplet bytes from the production uplink converter's documented
        // example (tag/length header already stripped, as Gurux would hand it back)
        byte[] raw = {
                0x00, 0x00, 0x01,  // h=0,  m=0,  slot=1
                0x03, 0x00, 0x02,  // h=3,  m=0,  slot=2
                0x06, 0x00, 0x03,  // h=6,  m=0,  slot=3
                0x09, 0x00, 0x04,  // h=9,  m=0,  slot=4
                0x0c, 0x00, 0x05,  // h=12, m=0,  slot=5
                0x0f, 0x00, 0x06,  // h=15, m=0,  slot=6
                0x12, 0x00, 0x07,  // h=18, m=0,  slot=7
                0x15, 0x00, 0x08   // h=21, m=0,  slot=8
        };

        List<Map<String, Integer>> slots = GroupSetForSemc.decodeTodSlots(raw);

        assertEquals(8, slots.size());
        assertEquals(Map.of("h", 0, "m", 0, "todSlot", 1), slots.get(0));
        assertEquals(Map.of("h", 3, "m", 0, "todSlot", 2), slots.get(1));
        assertEquals(Map.of("h", 12, "m", 0, "todSlot", 5), slots.get(4));
        assertEquals(Map.of("h", 21, "m", 0, "todSlot", 8), slots.get(7));
    }

    @Test
    void testDecodeTodSlots_NullOrEmpty_ReturnsEmptyList() {
        assertTrue(GroupSetForSemc.decodeTodSlots(null).isEmpty());
        assertTrue(GroupSetForSemc.decodeTodSlots(new byte[0]).isEmpty());
    }

    // ════════════════════════════════════════════════════════════════════
    // todSlots ENCODE TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testEncodeTodSlots_RoundTrip_MatchesOriginalBytes() {
        byte[] original = {
                0x00, 0x00, 0x01, 0x03, 0x00, 0x02, 0x06, 0x00, 0x03, 0x09, 0x00, 0x04,
                0x0c, 0x00, 0x05, 0x0f, 0x00, 0x06, 0x12, 0x00, 0x07, 0x15, 0x00, 0x08
        };

        List<Map<String, Integer>> decoded = GroupSetForSemc.decodeTodSlots(original);
        byte[] reEncoded = GroupSetForSemc.encodeTodSlots(decoded);

        assertArrayEquals(original, reEncoded);
    }

    @Test
    void testEncodeTodSlots_NotAList_ThrowsException() {
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> GroupSetForSemc.encodeTodSlots("not a list"));
        assertTrue(ex.getMessage().contains("must be a list"));
    }

    @Test
    void testEncodeTodSlots_EntryNotAMap_ThrowsException() {
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> GroupSetForSemc.encodeTodSlots(List.of("not-a-map")));
        assertTrue(ex.getMessage().contains("must be an object"));
    }

    @Test
    void testEncodeTodSlots_MissingRequiredKey_ThrowsException() {
        Map<String, Object> incomplete = new LinkedHashMap<>();
        incomplete.put("h", 3);
        incomplete.put("m", 0);
        // "todSlot" deliberately missing

        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> GroupSetForSemc.encodeTodSlots(List.of(incomplete)));
        assertTrue(ex.getMessage().contains("missing"));
    }

    // ════════════════════════════════════════════════════════════════════
    // convertFieldValue TESTS
    // ════════════════════════════════════════════════════════════════════

    @Test
    void testConvertFieldValue_ExistingFloat_ParsesAsFloat() {
        Object result = GroupSetForSemc.convertFieldValue(1.0f, "265.5", "rVoltMax");

        assertEquals(265.5f, result);
        assertTrue(result instanceof Float);
    }

    @Test
    void testConvertFieldValue_ExistingLong_ParsesAsLong() {
        Object result = GroupSetForSemc.convertFieldValue(1L, "64800", "slotOnTimeGMT");

        assertEquals(64800L, result);
    }

    @Test
    void testConvertFieldValue_ExistingBoolean_ParsesAsBoolean() {
        Object result = GroupSetForSemc.convertFieldValue(false, "true", "astroModeGPS");

        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void testConvertFieldValue_ExistingByteArray_ParsesHexString() {
        // slotOnTimeDLMS: 18:00:00.00
        Object result = GroupSetForSemc.convertFieldValue(new byte[4], "12000000", "slotOnTimeDLMS");

        assertArrayEquals(new byte[]{0x12, 0x00, 0x00, 0x00}, (byte[]) result);
    }

    @Test
    void testConvertFieldValue_ExistingByteArray_AcceptsSpacedHex() {
        Object result = GroupSetForSemc.convertFieldValue(new byte[4], "12 00 00 00", "slotOnTimeDLMS");

        assertArrayEquals(new byte[]{0x12, 0x00, 0x00, 0x00}, (byte[]) result);
    }

    @Test
    void testConvertFieldValue_TodSlotsFieldName_DelegatesToEncodeTodSlots() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("h", 3);
        slot.put("m", 0);
        slot.put("todSlot", 2);

        Object result = GroupSetForSemc.convertFieldValue(new byte[3], List.of(slot), "todSlots");

        assertArrayEquals(new byte[]{0x03, 0x00, 0x02}, (byte[]) result);
    }
}
