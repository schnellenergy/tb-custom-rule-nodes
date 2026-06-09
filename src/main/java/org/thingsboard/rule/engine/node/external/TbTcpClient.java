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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import org.thingsboard.server.common.msg.TbMsgDataType;

/**
 * TCP client for ThingsBoard Rule Engine nodes with configurable response
 * framing strategies: NONE (smart timeout), DELIMITER, and LENGTH_PREFIX.
 */
public class TbTcpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String host;
    private final int port;
    private final boolean tls;
    private final SSLSocketFactory sslSocketFactory;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean keepAlive;

    private Socket socket;
    // BufferedInputStream preserves unconsumed bytes across calls on keepAlive connections
    private BufferedInputStream bufferedIn;

    // ── Framing settings (set dynamically via setFramingSettings) ────────
    private String framingType = "NONE";
    private byte[] delimiterBytes = new byte[]{'\n'};
    private int fragmentTimeoutMs = 50;

    // LENGTH_PREFIX (Netty-style) settings
    private int maxFrameLength = 1048576;
    private int lengthFieldOffset = 0;
    private int lengthFieldLength = 4;
    private int lengthAdjustment = 0;
    private int initialBytesToStrip = 0;
    private ByteOrder lengthByteOrder = ByteOrder.BIG_ENDIAN;

    public TbTcpClient(String host, int port, boolean tls, SSLSocketFactory sslSocketFactory, int connectTimeoutMs,
            int readTimeoutMs, boolean keepAlive) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.sslSocketFactory = sslSocketFactory;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.keepAlive = keepAlive;
    }

    /**
     * Applies framing configuration from the rule node config.
     * Called after borrowing from the pool, before each sendRequest.
     */
    public void setFramingSettings(TbSendToTcpNodeConfiguration config) {
        if (config == null) {
            return;
        }
        this.framingType = config.getFramingType() != null ? config.getFramingType() : "NONE";

        // Delimiter
        String delimStr = config.getDelimiter();
        this.delimiterBytes = resolveDelimiterBytes(delimStr);

        // Smart-timeout fragment window
        this.fragmentTimeoutMs = config.getFragmentTimeoutMs() > 0 ? config.getFragmentTimeoutMs() : 50;

        // LENGTH_PREFIX
        this.maxFrameLength = config.getMaxFrameLength() > 0 ? config.getMaxFrameLength() : 1048576;
        this.lengthFieldOffset = config.getLengthFieldOffset();
        this.lengthFieldLength = config.getLengthFieldLength() > 0 ? config.getLengthFieldLength() : 4;
        this.lengthAdjustment = config.getLengthAdjustment();
        this.initialBytesToStrip = config.getInitialBytesToStrip();
        this.lengthByteOrder = "LITTLE_ENDIAN".equalsIgnoreCase(config.getLengthByteOrder())
                ? ByteOrder.LITTLE_ENDIAN
                : ByteOrder.BIG_ENDIAN;
    }

    /**
     * Connects to the server if not already connected.
     */
    public void connect() throws IOException {
        if (socket != null && !socket.isClosed() && socket.isConnected()) {
            return;
        }

        socket = tls && sslSocketFactory != null ? sslSocketFactory.createSocket()
                : SocketFactory.getDefault().createSocket();

        socket.setKeepAlive(keepAlive);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        bufferedIn = new BufferedInputStream(socket.getInputStream(), 8192);
    }

    /**
     * Checks if the client has an active connection.
     */
    public boolean isValid() {
        return socket != null && socket.isConnected() && !socket.isClosed()
                && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    /**
     * Closes the active connection.
     */
    public void close() {
        bufferedIn = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            } finally {
                socket = null;
            }
        }
    }

    /**
     * Sends the payload and returns the response as bytes.
     * Reconnects once if the pooled connection was closed by the remote end.
     */
    public byte[] sendRequest(byte[] payload) throws IOException {
        if (!isValid()) {
            connect();
        }
        try {
            return doSend(payload);
        } catch (SocketException | EOFException e) {
            // Stale pooled connection — remote closed it; reconnect and retry once
            close();
            connect();
            return doSend(payload);
        }
    }

    // ── Core send + framed read ─────────────────────────────────────────

    private byte[] doSend(byte[] payload) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(payload);
        out.flush();

        byte[] response;
        if ("DELIMITER".equalsIgnoreCase(framingType)) {
            response = readDelimiterFramed();
        } else if ("LENGTH_PREFIX".equalsIgnoreCase(framingType)) {
            response = readLengthPrefixFramed();
        } else {
            // NONE — smart timeout
            response = readSmartTimeout();
        }

        if (response == null || response.length == 0) {
            throw new IOException("No response received from TCP server");
        }
        return response;
    }

    // ── NONE / Smart Timeout ────────────────────────────────────────────

    /**
     * The first read blocks up to the full readTimeoutMs.
     * Once data arrives, subsequent reads use a short fragmentTimeoutMs window.
     * A SocketTimeoutException on the short window signals end-of-response.
     */
    private byte[] readSmartTimeout() throws IOException {
        InputStream in = bufferedIn;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];

        // Phase 1: first read at full readTimeoutMs (already set on socket)
        int len;
        try {
            len = in.read(tmp);
        } catch (SocketTimeoutException e) {
            // No data at all within readTimeoutMs
            return new byte[0];
        }
        if (len == -1) {
            throw new EOFException("Connection closed by peer (EOF)");
        }
        buf.write(tmp, 0, len);

        // Phase 2: switch to short timeout to drain trailing fragments
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(fragmentTimeoutMs);
            while (true) {
                try {
                    len = in.read(tmp);
                    if (len == -1) {
                        break; // remote closed
                    }
                    buf.write(tmp, 0, len);
                } catch (SocketTimeoutException e) {
                    // Quiet period expired — response complete
                    break;
                }
            }
        } finally {
            // Restore original timeout for the next request on this pooled connection
            socket.setSoTimeout(originalTimeout);
        }
        return buf.toByteArray();
    }

    // ── DELIMITER ───────────────────────────────────────────────────────

    /**
     * Reads byte-by-byte from the buffered stream until the delimiter
     * sequence is matched. Returns all bytes read EXCLUDING the delimiter.
     */
    private byte[] readDelimiterFramed() throws IOException {
        InputStream in = bufferedIn;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matchIndex = 0;

        while (true) {
            int b = in.read();
            if (b == -1) {
                if (buf.size() == 0) {
                    throw new EOFException("Connection closed by peer (EOF) before reading response");
                }
                // Connection closed before delimiter; return what we have
                break;
            }
            buf.write(b);

            if ((byte) b == delimiterBytes[matchIndex]) {
                matchIndex++;
                if (matchIndex == delimiterBytes.length) {
                    // Full delimiter matched — strip it from the result
                    byte[] data = buf.toByteArray();
                    byte[] result = new byte[data.length - delimiterBytes.length];
                    System.arraycopy(data, 0, result, 0, result.length);
                    return result;
                }
            } else {
                // Reset match; check if current byte starts a new match
                matchIndex = ((byte) b == delimiterBytes[0]) ? 1 : 0;
            }
        }
        return buf.toByteArray();
    }

    // ── LENGTH_PREFIX (Netty-style) ─────────────────────────────────────

    /**
     * Reads a length-prefixed frame using Netty-style configuration:
     *   lengthFieldOffset, lengthFieldLength, lengthAdjustment,
     *   initialBytesToStrip, maxFrameLength, lengthByteOrder.
     */
    private byte[] readLengthPrefixFramed() throws IOException {
        InputStream in = bufferedIn;

        // 1. Read everything up to and including the length field
        int headerSize = lengthFieldOffset + lengthFieldLength;
        byte[] header = new byte[headerSize];
        readFully(in, header, 0, headerSize);

        // 2. Decode the length value from the field
        long framePayloadLength = decodeLengthField(header, lengthFieldOffset, lengthFieldLength, lengthByteOrder);
        // Apply adjustment (can be negative, e.g. if length includes the header itself)
        long totalRemaining = framePayloadLength + lengthAdjustment;

        if (totalRemaining < 0) {
            throw new IOException("Negative frame length after adjustment: " + totalRemaining);
        }

        long totalFrameSize = headerSize + totalRemaining;
        if (totalFrameSize > maxFrameLength) {
            throw new IOException("Frame length " + totalFrameSize + " exceeds maxFrameLength " + maxFrameLength);
        }

        // 3. Read the remaining payload bytes
        byte[] fullFrame = new byte[(int) totalFrameSize];
        System.arraycopy(header, 0, fullFrame, 0, headerSize);
        if (totalRemaining > 0) {
            readFully(in, fullFrame, headerSize, (int) totalRemaining);
        }

        // 4. Strip leading bytes as configured
        if (initialBytesToStrip > 0) {
            if (initialBytesToStrip > fullFrame.length) {
                throw new IOException("initialBytesToStrip (" + initialBytesToStrip
                        + ") exceeds frame length (" + fullFrame.length + ")");
            }
            byte[] stripped = new byte[fullFrame.length - initialBytesToStrip];
            System.arraycopy(fullFrame, initialBytesToStrip, stripped, 0, stripped.length);
            return stripped;
        }
        return fullFrame;
    }

    /**
     * Decodes an unsigned integer from the length field bytes.
     */
    private static long decodeLengthField(byte[] header, int offset, int fieldLength, ByteOrder order) throws IOException {
        if (fieldLength < 1 || fieldLength > 8) {
            throw new IOException("Unsupported lengthFieldLength: " + fieldLength + ". Must be 1..8");
        }
        // Pad to 8 bytes for ByteBuffer
        byte[] padded = new byte[8];
        if (order == ByteOrder.BIG_ENDIAN) {
            System.arraycopy(header, offset, padded, 8 - fieldLength, fieldLength);
        } else {
            System.arraycopy(header, offset, padded, 0, fieldLength);
        }
        ByteBuffer bb = ByteBuffer.wrap(padded).order(order);
        return bb.getLong() & 0x7FFF_FFFF_FFFF_FFFFL; // treat as unsigned
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Reads exactly {@code len} bytes into {@code b} starting at {@code off}.
     * Blocks until all bytes are read or throws EOFException.
     */
    private void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int count = in.read(b, off + n, len - n);
            if (count < 0) {
                throw new EOFException("Reached EOF after reading " + n + " of " + len + " expected bytes");
            }
            n += count;
        }
    }

    /**
     * Resolves a user-supplied delimiter string (which may contain escape
     * sequences like {@code \n}, {@code \r\n}, {@code \u0000}) into raw bytes.
     */
    static byte[] resolveDelimiterBytes(String delim) {
        if (delim == null || delim.isEmpty()) {
            return new byte[]{'\n'};
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < delim.length()) {
            char c = delim.charAt(i);
            if (c == '\\' && i + 1 < delim.length()) {
                char next = delim.charAt(i + 1);
                switch (next) {
                    case 'n':  sb.append('\n');  i += 2; break;
                    case 'r':  sb.append('\r');  i += 2; break;
                    case 't':  sb.append('\t');  i += 2; break;
                    case 'f':  sb.append('\f');  i += 2; break;
                    case 'b':  sb.append('\b');  i += 2; break;
                    case '\\': sb.append('\\');  i += 2; break;
                    case '0':  sb.append('\0');  i += 2; break;
                    case 'u':
                        if (i + 5 < delim.length()) {
                            String hex = delim.substring(i + 2, i + 6);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 6;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                                i++;
                            }
                        } else {
                            sb.append(c);
                            i++;
                        }
                        break;
                    default:
                        sb.append(c);
                        i++;
                        break;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Public API (unchanged signatures) ───────────────────────────────

    /**
     * Sends a string payload and returns the response as a string using
     * TbMsgDataType.
     */
    public String sendRequest(String payload, TbMsgDataType dataType, TbMsgDataType responseType) throws IOException {
        byte[] payloadBytes;
        if (dataType == null || "TEXT".equalsIgnoreCase(dataType.name())) {
            payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        } else if ("BINARY".equalsIgnoreCase(dataType.name())) {
            payloadBytes = java.util.Base64.getDecoder().decode(payload);
        } else if ("JSON".equalsIgnoreCase(dataType.name())) {
            payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        } else {
            payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        }
        byte[] response = sendRequest(payloadBytes);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("response", decodeResponse(response, responseType));
        return node.toString();
    }

    /**
     * Decodes the response bytes according to the TbMsgDataType.
     */
    public String decodeResponse(byte[] response, TbMsgDataType dataType) {
        if (dataType == null || "TEXT".equalsIgnoreCase(dataType.name())) {
            return new String(response, StandardCharsets.UTF_8);
        } else if ("BINARY".equalsIgnoreCase(dataType.name())) {
            return java.util.Base64.getEncoder().encodeToString(response);
        } else if ("JSON".equalsIgnoreCase(dataType.name())) {
            return new String(response, StandardCharsets.UTF_8);
        } else {
            return new String(response, StandardCharsets.UTF_8);
        }
    }
}
