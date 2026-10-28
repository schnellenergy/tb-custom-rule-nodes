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

import gurux.dlms.GXBitString;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field map and value-encoding logic for SEMC's manufacturer-specific "operating
 * mode" structure - a 31-field DLMS structure (OBIS 1.131.128.128.128.255,
 * class 1, attribute 2) covering astro/GPS scheduling, slot timing, an event
 * bitmask, network config, current/voltage/power-factor thresholds, and a
 * time-of-day schedule.
 *
 * Field positions 0-29 are verified against the production DLMSCliTool.java
 * field map (org.thingsboard.integration.custom.basic, dlms-downlink-integration
 * project). Field 30 (todSlots) is NOT present in that map - it predates this
 * field being added to the protocol - and is instead verified against the
 * production uplink converter's byte-level decode logic.
 *
 * The OBIS itself (1.131.128.128.128.255, not the previously-assumed
 * 1.251.128.128.128.255) was confirmed by a live READ against a real meter -
 * see the manual verification recorded in the project history. Other SEMC
 * meter variants may use a different OBIS/field-count for this same kind of
 * structure; this constant is only confirmed correct for the meter type
 * tested.
 *
 * This is entirely vendor-specific: nothing here applies to a standard DLMS
 * meter or a different manufacturer's equivalent object, if one even exists.
 */
final class GroupSetForSemc {

    static final String GROUP_WRITE_OBIS = "1.131.128.128.128.255";
    static final int GROUP_WRITE_CLASS_ID = 1;
    static final int GROUP_WRITE_ATTRIBUTE = 2;
    static final int TOD_SLOTS_INDEX = 30;

    private GroupSetForSemc() {
    }

    /** Field name -> position within the structure (0-based). */
    static final Map<String, Integer> FIELD_INDEX = buildFieldIndex();

    private static Map<String, Integer> buildFieldIndex() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("operatingMode", 0);
        m.put("astroModeGPS", 1);
        m.put("astroLatitude", 2);
        m.put("astroLongitude", 3);
        m.put("astroOnOffSet", 4);
        m.put("astroOffOffSet", 5);
        m.put("slotOnTimeGMT", 6);
        m.put("slotOnTimeDLMS", 7);
        m.put("slotOffTimeGMT", 8);
        m.put("slotOffTimeDLMS", 9);
        m.put("enabledEvents", 10);
        m.put("tcpUdpPort", 11);
        m.put("publishInterval", 12);
        m.put("maxPayloadSlotOn", 13);
        m.put("maxPayloadSlotOff", 14);
        m.put("rCurrentMin", 15);
        m.put("rCurrentMax", 16);
        m.put("yCurrentMin", 17);
        m.put("yCurrentMax", 18);
        m.put("bCurrentMin", 19);
        m.put("bCurrentMax", 20);
        m.put("rVoltMin", 21);
        m.put("rVoltMax", 22);
        m.put("yVoltMin", 23);
        m.put("yVoltMax", 24);
        m.put("bVoltMin", 25);
        m.put("bVoltMax", 26);
        m.put("rpfThreshold", 27);
        m.put("ypfThreshold", 28);
        m.put("bpfThreshold", 29);
        m.put("todSlots", TOD_SLOTS_INDEX);
        return Collections.unmodifiableMap(m);
    }

    static String fieldNames() {
        return String.join(", ", FIELD_INDEX.keySet());
    }

    /**
     * Convert a raw value from a rule-chain payload into the correct Java type
     * for the given structure element, based on the CURRENT value already at
     * that position (read fresh from the meter) - matching the type Gurux
     * itself used when it decoded that same position. This mirrors the exact
     * approach used by the production DLMSCliTool.java's convertStructureElement.
     *
     * @param existing the value currently at this position (from a prior READ)
     * @param newValue the replacement value from the caller (String, Number,
     *        Boolean, or - for "todSlots" only - a List of maps with
     *        todSlot/h/m keys)
     * @param fieldName field name, for error messages and the todSlots special case
     */
    static Object convertFieldValue(Object existing, Object newValue, String fieldName) {
        if ("todSlots".equals(fieldName)) {
            return encodeTodSlots(newValue);
        }
        if (existing instanceof byte[]) {
            // The two DLMS-time fields (slotOnTimeDLMS/slotOffTimeDLMS): accept
            // a hex string like "12 00 00 00" or "12000000"
            String hex = String.valueOf(newValue).replaceAll("\\s+", "");
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < hex.length(); i += 2) {
                bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            }
            return bytes;
        }
        if (existing instanceof GXBitString) {
            // "enabledEvents": a packed bitmask (e.g. 21474836480 = bits 32 and 34
            // set) - encoded MSB-first within each byte, matching the exact byte
            // layout the production TCP path's longToTLVBytes() produces (cross-
            // verified against a real captured frame: 21474836480 -> bit 32 = 0x20,
            // bit 34 = 0x80, byte[4] = 0xA0, all other bytes 0x00).
            int bitLength = ((GXBitString) existing).length();
            long bitmask = Long.parseLong(String.valueOf(newValue));
            byte[] bytes = new byte[(bitLength + 7) / 8];
            for (int bit = 0; bit < bitLength; bit++) {
                if (((bitmask >>> bit) & 1L) != 0) {
                    int byteIndex = bit / 8;
                    int bitInByte = 7 - (bit % 8);
                    bytes[byteIndex] |= (1 << bitInByte);
                }
            }
            // GXBitString's second constructor argument is padBits (unused bits in
            // the last byte, 0-7) - NOT the total bit length. Passing bitLength
            // directly here (an easy mistake - see the same bug in
            // DlmsExecutor.buildBitStringValue()) silently produces a GXBitString
            // whose own .length() no longer matches the data, even though the raw
            // bytes are still correct.
            int padBits = (8 - (bitLength % 8)) % 8;
            return new GXBitString(bytes, padBits);
        }
        String raw = String.valueOf(newValue);
        if (existing instanceof Float) {
            return Float.parseFloat(raw);
        }
        if (existing instanceof Double) {
            return Double.parseDouble(raw);
        }
        if (existing instanceof Long) {
            return Long.parseLong(raw);
        }
        if (existing instanceof BigInteger) {
            return new BigInteger(raw);
        }
        if (existing instanceof Integer) {
            return Integer.parseInt(raw);
        }
        if (existing instanceof Short) {
            return Short.parseShort(raw);
        }
        if (existing instanceof Byte) {
            return Byte.parseByte(raw);
        }
        if (existing instanceof Boolean) {
            return Boolean.parseBoolean(raw);
        }
        // Unknown/unexpected existing type - keep as string rather than guessing
        return raw;
    }

    /**
     * Decode the todSlots element into a list of {todSlot, h, m} maps.
     *
     * Gurux hands back this structure element as a plain byte[] of (hour,
     * minute, slotNumber) triplets - the DLMS octet-string type tag and length
     * byte are protocol framing that Gurux already stripped during structure
     * decoding (the same pattern seen with the 4-byte DLMS-time sub-elements).
     *
     * @param raw the triplet bytes (length must be a multiple of 3)
     */
    static List<Map<String, Integer>> decodeTodSlots(byte[] raw) {
        List<Map<String, Integer>> slots = new ArrayList<>();
        if (raw == null) {
            return slots;
        }
        for (int i = 0; i + 3 <= raw.length; i += 3) {
            Map<String, Integer> entry = new LinkedHashMap<>();
            entry.put("h", raw[i] & 0xFF);
            entry.put("m", raw[i + 1] & 0xFF);
            entry.put("todSlot", raw[i + 2] & 0xFF);
            slots.add(entry);
        }
        return slots;
    }

    /**
     * Encode a list of {todSlot, h, m} entries back into the raw triplet bytes
     * (hour, minute, slotNumber per entry) - the inverse of decodeTodSlots().
     * Gurux adds the octet-string type tag and length byte itself when writing.
     *
     * @param value a List of Map entries (or any object exposing "h"/"m"/"todSlot"
     *        keys via Map, e.g. parsed from a JSON payload)
     */
    @SuppressWarnings("unchecked")
    static byte[] encodeTodSlots(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("todSlots must be a list of {todSlot, h, m} entries, got: " +
                    (value == null ? "null" : value.getClass().getSimpleName()));
        }
        List<?> list = (List<?>) value;
        byte[] result = new byte[list.size() * 3];
        int pos = 0;
        for (Object item : list) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("Each todSlots entry must be an object with todSlot/h/m, got: " + item);
            }
            Map<String, Object> entry = (Map<String, Object>) item;
            result[pos] = (byte) toInt(entry.get("h"));
            result[pos + 1] = (byte) toInt(entry.get("m"));
            result[pos + 2] = (byte) toInt(entry.get("todSlot"));
            pos += 3;
        }
        return result;
    }

    private static int toInt(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("todSlots entry is missing a required h/m/todSlot value");
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
