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
package org.thingsboard.rule.engine.node.external.manual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.thingsboard.rule.engine.node.external.DlmsClientPool;
import org.thingsboard.rule.engine.node.external.DlmsExecutor;
import org.thingsboard.rule.engine.node.external.DlmsRequest;
import org.thingsboard.rule.engine.node.external.DlmsResponse;

/**
 * Manual, standalone runner for testing DLMS communication against a REAL meter,
 * completely outside of ThingsBoard - no rule chain, no deployment needed.
 *
 * This is NOT a JUnit test - it has no @Test methods and its name does not match
 * Surefire's test patterns, so "mvn test" will never pick it up or run it. It is
 * meant to be run manually, by hand, whenever you want to point this code at a
 * real device.
 *
 * ── HOW TO RUN ──────────────────────────────────────────────────────────────
 *
 * 1. Build the classpath once (from the project root):
 *      mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 *      mvn -q compile test-compile
 *
 * 2. Run it (READ example):
 *      java -cp "target/classes:target/test-classes:$(cat /tmp/cp.txt)" \
 *        org.thingsboard.rule.engine.node.external.manual.RealMeterManualRunner \
 *        READ 192.168.1.50 4059 1.0.94.91.7.255 3 2 \
 *        00112233445566778899AABBCCDDEEFF FFEEDDCCBBAA99887766554433221100 48455330000001FF
 *
 * 3. For WRITE, add the value as the last argument; for ACTION, replace attribute
 *    with the method index and add an optional parameter. See printUsage() below
 *    for the exact argument order per action.
 *
 * The response (success/failure, data, error message, duration) is printed to
 * the console - no ThingsBoard, no rule chain, just this code talking directly
 * to your meter over the network.
 * ────────────────────────────────────────────────────────────────────────────
 */
public final class RealMeterManualRunner {

    private RealMeterManualRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        try {
            DlmsRequest request = buildRequest(args);

            System.out.println("Connecting to " + request.getHost() + ":" + request.getPort()
                    + " action=" + request.getAction() + " obis=" + request.getObis());

            DlmsClientPool pool = new DlmsClientPool(1, 5000);
            DlmsExecutor executor = new DlmsExecutor(pool);

            long start = System.currentTimeMillis();
            DlmsResponse response = executor.execute(request);
            long elapsed = System.currentTimeMillis() - start;

            System.out.println();
            System.out.println("═══════════════════════════════════════════");
            System.out.println("RESULT");
            System.out.println("═══════════════════════════════════════════");
            System.out.println("success        : " + response.isSuccess());
            System.out.println("responseCode   : " + response.getResponseCode());
            System.out.println("data           : " + response.getData());
            System.out.println("errorMessage   : " + response.getErrorMessage());
            System.out.println("durationMs     : " + response.getDurationMs() + " (wall clock: " + elapsed + "ms)");
            System.out.println("═══════════════════════════════════════════");

            pool.shutdown();
            System.exit(response.isSuccess() ? 0 : 2);

        } catch (Exception e) {
            System.err.println("Failed to run: " + e.getMessage());
            e.printStackTrace();
            printUsage();
            System.exit(1);
        }
    }

    private static DlmsRequest buildRequest(String[] rawArgs) {
        // Pull out optional "--name=value" flags first, wherever they appear, so the
        // existing positional argument parsing below doesn't need to change.
        // Presence of a password selects HLS-with-encryption mode instead of the
        // default HLSE/GMAC mode - see DlmsExecutor/GXDLMSSecureClient2.
        String password = null;
        int clientAddress = 0;
        int serverAddress = 0;
        String standard = null;
        int counter = -1;
        // Optional overrides for the OBIS/classId/attribute an action targets, for
        // actions whose positional syntax doesn't otherwise take them (SET-CLOCK,
        // WRITE-GROUP) - both default to the standard Clock object / SEMC's
        // operating-mode structure respectively if left unset. Harmless no-ops for
        // READ/WRITE/ACTION, which already set these positionally.
        String obisOverride = null;
        int classIdOverride = 0;
        int attributeOverride = 0;
        // WRITE-GROUP only: name=value pairs to patch in the current structure.
        // "value" for a scalar field is a plain string; for "todSlots" it must be
        // a JSON array, e.g. --groupField=todSlots=[{"h":3,"m":0,"todSlot":2}]
        Map<String, Object> groupFields = new LinkedHashMap<>();
        List<String> filtered = new ArrayList<>();
        for (String arg : rawArgs) {
            if (arg.startsWith("--password=")) {
                password = arg.substring("--password=".length());
            } else if (arg.startsWith("--clientAddress=")) {
                clientAddress = Integer.parseInt(arg.substring("--clientAddress=".length()));
            } else if (arg.startsWith("--serverAddress=")) {
                serverAddress = Integer.parseInt(arg.substring("--serverAddress=".length()));
            } else if (arg.startsWith("--standard=")) {
                standard = arg.substring("--standard=".length());
            } else if (arg.startsWith("--counter=")) {
                counter = Integer.parseInt(arg.substring("--counter=".length()));
            } else if (arg.startsWith("--obis=")) {
                obisOverride = arg.substring("--obis=".length());
            } else if (arg.startsWith("--classId=")) {
                classIdOverride = Integer.parseInt(arg.substring("--classId=".length()));
            } else if (arg.startsWith("--attribute=")) {
                attributeOverride = Integer.parseInt(arg.substring("--attribute=".length()));
            } else if (arg.startsWith("--groupField=")) {
                String pair = arg.substring("--groupField=".length());
                int eq = pair.indexOf('=');
                if (eq < 1) {
                    throw new IllegalArgumentException("--groupField requires name=value format (got: " + pair + ")");
                }
                String fieldName = pair.substring(0, eq);
                String rawValue = pair.substring(eq + 1);
                groupFields.put(fieldName, "todSlots".equals(fieldName) ? parseTodSlotsJson(rawValue) : rawValue);
            } else {
                filtered.add(arg);
            }
        }
        String[] args = filtered.toArray(new String[0]);

        // args[0] = action
        String action = args[0].toUpperCase();
        DlmsRequest request = new DlmsRequest();
        request.setAction(action);
        request.setPassword(password);
        request.setClientAddress(clientAddress);
        request.setServerAddress(serverAddress);
        request.setCounter(counter);
        request.setStandard(standard);
        if (!groupFields.isEmpty()) {
            request.setGroupFields(groupFields);
        }

        switch (action) {
            case "READ":
                // READ host port obis classId attribute blockCipherKey authKey systemTitle
                requireArgs(args, 9, "READ");
                request.setHost(args[1]);
                request.setPort(Integer.parseInt(args[2]));
                request.setObis(args[3]);
                request.setClassId(Integer.parseInt(args[4]));
                request.setAttribute(Integer.parseInt(args[5]));
                request.setBlockCipherKey(args[6]);
                request.setAuthenticationKey(args[7]);
                request.setSystemTitle(args[8]);
                break;

            case "WRITE":
                // WRITE host port obis classId attribute value blockCipherKey authKey systemTitle
                requireArgs(args, 10, "WRITE");
                request.setHost(args[1]);
                request.setPort(Integer.parseInt(args[2]));
                request.setObis(args[3]);
                request.setClassId(Integer.parseInt(args[4]));
                request.setAttribute(Integer.parseInt(args[5]));
                request.setValue(args[6]);
                request.setBlockCipherKey(args[7]);
                request.setAuthenticationKey(args[8]);
                request.setSystemTitle(args[9]);
                break;

            case "ACTION":
                // ACTION host port obis classId method [param] blockCipherKey authKey systemTitle
                requireArgs(args, 9, "ACTION");
                request.setHost(args[1]);
                request.setPort(Integer.parseInt(args[2]));
                request.setObis(args[3]);
                request.setClassId(Integer.parseInt(args[4]));
                request.setMethod(Integer.parseInt(args[5]));
                if (args.length == 10) {
                    request.setValue(args[6]);
                    request.setBlockCipherKey(args[7]);
                    request.setAuthenticationKey(args[8]);
                    request.setSystemTitle(args[9]);
                } else {
                    request.setBlockCipherKey(args[6]);
                    request.setAuthenticationKey(args[7]);
                    request.setSystemTitle(args[8]);
                }
                break;

            case "READ-RANGE":
                // READ-RANGE host port obis fromTs toTs blockCipherKey authKey systemTitle
                // obis: 1.0.99.1.0.255 (block load survey) or 1.0.99.2.0.255 (MR history).
                // fromTs/toTs: epoch seconds.
                requireArgs(args, 9, "READ-RANGE");
                request.setHost(args[1]);
                request.setPort(Integer.parseInt(args[2]));
                request.setObis(args[3]);
                request.setFromTs(Long.parseLong(args[4]));
                request.setToTs(Long.parseLong(args[5]));
                request.setBlockCipherKey(args[6]);
                request.setAuthenticationKey(args[7]);
                request.setSystemTitle(args[8]);
                break;

            case "SET-CLOCK":
                // SET-CLOCK host port datetime blockCipherKey authKey systemTitle
                requireArgs(args, 7, "SET-CLOCK");
                request.setHost(args[1]);
                request.setPort(Integer.parseInt(args[2]));
                request.setValue(args[3]);
                request.setBlockCipherKey(args[4]);
                request.setAuthenticationKey(args[5]);
                request.setSystemTitle(args[6]);
                break;

            case "WRITE-GROUP":
                // WRITE-GROUP host port blockCipherKey authKey systemTitle --groupField=name=value ...
                // obis/classId/attribute default to SEMC's operating-mode structure if omitted.
                requireArgs(args, 6, "WRITE-GROUP");
                request.setHost(args[1]);
                request.setPort(Integer.parseInt(args[2]));
                request.setBlockCipherKey(args[3]);
                request.setAuthenticationKey(args[4]);
                request.setSystemTitle(args[5]);
                if (groupFields.isEmpty()) {
                    throw new IllegalArgumentException("WRITE-GROUP requires at least one --groupField=name=value");
                }
                break;

            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }

        // Applied after the positional parsing above, so these fill in (or override)
        // the OBIS/classId/attribute for whichever action was requested - primarily
        // useful for SET-CLOCK and WRITE-GROUP, which don't take them positionally.
        if (obisOverride != null) {
            request.setObis(obisOverride);
        }
        if (classIdOverride > 0) {
            request.setClassId(classIdOverride);
        }
        if (attributeOverride > 0) {
            request.setAttribute(attributeOverride);
        }

        request.setTimeout(30);
        return request;
    }

    private static void requireArgs(String[] args, int minCount, String action) {
        if (args.length < minCount) {
            throw new IllegalArgumentException(action + " requires at least " + (minCount - 1) + " arguments after the action name");
        }
    }

    private static List<Map<String, Object>> parseTodSlotsJson(String json) {
        try {
            return new ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("todSlots must be a JSON array like "
                    + "[{\"h\":3,\"m\":0,\"todSlot\":2}] - failed to parse: " + e.getMessage(), e);
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  READ      host port obis classId attribute blockCipherKey authKey systemTitle [flags]");
        System.out.println("  WRITE     host port obis classId attribute value blockCipherKey authKey systemTitle [flags]");
        System.out.println("  ACTION    host port obis classId method [param] blockCipherKey authKey systemTitle [flags]");
        System.out.println("  READ-RANGE host port obis fromTs toTs blockCipherKey authKey systemTitle [flags]");
        System.out.println("  SET-CLOCK host port datetime blockCipherKey authKey systemTitle [flags]");
        System.out.println("  WRITE-GROUP host port blockCipherKey authKey systemTitle --groupField=name=value [...] [flags]");
        System.out.println();
        System.out.println("  Optional flags (can appear anywhere in the argument list):");
        System.out.println("    --password=<pwd>        If present, uses HLS-with-encryption mode (Authentication.HIGH");
        System.out.println("                             + AUTHENTICATION_ENCRYPTION) instead of the default HLSE/GMAC");
        System.out.println("                             mode - some meters require this combined password+key mode.");
        System.out.println("    --clientAddress=<n>      DLMS client address, if the meter requires a specific value.");
        System.out.println("    --serverAddress=<n>      DLMS server (meter) address, if not the Gurux default.");
        System.out.println("    --standard=<name>        National standard variant, e.g. INDIA.");
        System.out.println("    --obis=<code>            Override the OBIS code SET-CLOCK/WRITE-GROUP target (both");
        System.out.println("                             default to the standard Clock object / SEMC's operating-mode");
        System.out.println("                             structure respectively). No-op for READ/WRITE/ACTION, which");
        System.out.println("                             already take it positionally.");
        System.out.println("    --classId=<n>            Override the class ID, same actions/defaults as --obis=.");
        System.out.println("    --attribute=<n>          Override the attribute index, same actions/defaults as --obis=.");
        System.out.println("    --groupField=name=value  WRITE-GROUP only, repeatable. Patches one named field in");
        System.out.println("                             SEMC's operating-mode structure; all other fields are read");
        System.out.println("                             from the meter and left untouched. For \"todSlots\", value");
        System.out.println("                             must be a JSON array: [{\"h\":3,\"m\":0,\"todSlot\":2}, ...]");
        System.out.println();
        System.out.println("Example (READ, default HLSE mode):");
        System.out.println("  READ 192.168.1.50 4059 1.0.94.91.7.255 3 2 \\");
        System.out.println("       00112233445566778899AABBCCDDEEFF FFEEDDCCBBAA99887766554433221100 48455330000001FF");
        System.out.println();
        System.out.println("Example (READ, HLS-with-encryption mode):");
        System.out.println("  READ 192.168.1.50 4059 1.0.94.91.7.255 3 2 \\");
        System.out.println("       73737373737373737373737373737373 73737373737373737373737373737373 3132333435363738 \\");
        System.out.println("       --password=SALSMSCHNELL1060");
        System.out.println();
        System.out.println("Example (WRITE-GROUP, patch rVoltMax and todSlots):");
        System.out.println("  WRITE-GROUP 192.168.1.50 4059 \\");
        System.out.println("       73737373737373737373737373737373 73737373737373737373737373737373 3132333435363738 \\");
        System.out.println("       --password=SALSMSCHNELL1060 --groupField=rVoltMax=265.0 \\");
        System.out.println("       --groupField=todSlots=[{\"h\":3,\"m\":0,\"todSlot\":2}]");
    }
}
