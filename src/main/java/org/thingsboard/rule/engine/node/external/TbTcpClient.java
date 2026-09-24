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
import java.net.ConnectException;
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
 *
 * <p>Changes for SmartLights_2.0 issues #4433 / #4434 / #4435:
 * <ul>
 *   <li>Per-call {@code connectTimeoutMs} / {@code readTimeoutMs} — derived
 *       from {@code rpcTimeoutMs} metadata by the node; stored defaults are
 *       used only as fallback.</li>
 *   <li>All I/O errors are phase-classified ({@link TbTcpException}) and close
 *       the socket immediately to prevent stale-socket reuse.</li>
 *   <li>Read-timeout never triggers a blind reconnect-and-retry; that decision
 *       belongs to the pool.</li>
 *   <li>Per-phase timing ({@code connectMs}, {@code writeMs}, {@code readMs})
 *       exposed for metadata enrichment.</li>
 * </ul>
 */
public class TbTcpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String host;
    private final int port;
    private final boolean tls;
    private final SSLSocketFactory sslSocketFactory;
    /** Static fallback connect timeout (ms) used when no per-call value is supplied. */
    private final int defaultConnectTimeoutMs;
    /** Static fallback read timeout (ms) used when no per-call value is supplied. */
    private final int defaultReadTimeoutMs;
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

    // ── Per-phase timing (ms) from the last sendRequest call ─────────────
    private long lastConnectMs = -1;
    private long lastWriteMs = -1;
    private long lastReadMs = -1;

    private volatile int currentConnectTimeoutMs = -1;
    private volatile int currentReadTimeoutMs = -1;
    private volatile String currentCmdId = null;

    public void setInvocationContext(int connectTimeoutMs, int readTimeoutMs, String cmdId) {
        this.currentConnectTimeoutMs = connectTimeoutMs;
        this.currentReadTimeoutMs = readTimeoutMs;
        this.currentCmdId = cmdId;
    }

    public TbTcpClient(String host, int port, boolean tls, SSLSocketFactory sslSocketFactory, int connectTimeoutMs,
            int readTimeoutMs, boolean keepAlive) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.sslSocketFactory = sslSocketFactory;
        this.defaultConnectTimeoutMs = connectTimeoutMs;
        this.defaultReadTimeoutMs = readTimeoutMs;
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
     * Connects to the server using the default (static fallback) timeouts.
     */
    public void connect() throws TbTcpException {
        connect(defaultConnectTimeoutMs, defaultReadTimeoutMs);
    }

    /**
     * Connects to the server using the supplied per-call timeout values.
     * Classifies {@link ConnectException} as a {@code CONNECT}-phase
     * {@link TbTcpException} so callers can route to the correct failure chain.
     *
     * @param connectTimeoutMs TCP handshake budget in milliseconds
     * @param readTimeoutMs    SO_TIMEOUT for subsequent reads in milliseconds
     * @throws TbTcpException on any connection failure
     */
    public void connect(int connectTimeoutMs, int readTimeoutMs) throws TbTcpException {
        if (socket != null && !socket.isClosed() && socket.isConnected()) {
            return;
        }
        long t0 = System.currentTimeMillis();
        String target = host + ":" + port;
        try {
            socket = tls && sslSocketFactory != null ? sslSocketFactory.createSocket()
                    : SocketFactory.getDefault().createSocket();

            socket.setKeepAlive(keepAlive);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            bufferedIn = new BufferedInputStream(socket.getInputStream(), 8192);
            lastConnectMs = System.currentTimeMillis() - t0;
        } catch (ConnectException ce) {
            lastConnectMs = System.currentTimeMillis() - t0;
            safeClose();
            throw new TbTcpException("CONNECT", "ConnectException",
                    "Connection refused to " + target + ": " + ce.getMessage(), target, null, ce);
        } catch (SocketTimeoutException ste) {
            lastConnectMs = System.currentTimeMillis() - t0;
            safeClose();
            throw new TbTcpException("CONNECT", "SocketTimeoutException",
                    "Connect timed out to " + target + " after " + connectTimeoutMs + " ms", target, null, ste);
        } catch (IOException ioe) {
            lastConnectMs = System.currentTimeMillis() - t0;
            safeClose();
            throw new TbTcpException("CONNECT", ioe.getClass().getSimpleName(),
                    "Connect I/O error to " + target + ": " + ioe.getMessage(), target, null, ioe);
        }
    }

    /**
     * Checks if the client has an active connection.
     */
    public boolean isValid() {
        return socket != null && socket.isConnected() && !socket.isClosed()
                && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    /**
     * Closes the active connection silently.
     */
    public void close() {
        safeClose();
    }

    private void safeClose() {
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
     * Sends the payload using the default (static fallback) timeouts.
     * A {@link SocketTimeoutException} on read is classified as a {@code READ}-phase
     * failure and closes the socket — no automatic reconnect-and-retry is attempted
     * for write operations to prevent double-apply.
     */
    public byte[] sendRequest(byte[] payload) throws TbTcpException, IOException {
        int ct = currentConnectTimeoutMs > 0 ? currentConnectTimeoutMs : defaultConnectTimeoutMs;
        int rt = currentReadTimeoutMs > 0 ? currentReadTimeoutMs : defaultReadTimeoutMs;
        return sendRequest(payload, ct, rt, currentCmdId);
    }

    /**
     * Sends the payload using the supplied per-call timeout values.
     *
     * @param payload          raw bytes to send
     * @param connectTimeoutMs TCP handshake budget
     * @param readTimeoutMs    read budget
     * @param cmdId            optional command correlation ID for error enrichment
     * @throws TbTcpException on classified TCP errors (CONNECT / WRITE / READ)
     * @throws IOException    on unclassified I/O errors
     */
    public byte[] sendRequest(byte[] payload, int connectTimeoutMs, int readTimeoutMs, String cmdId)
            throws TbTcpException, IOException {
        try {
            return doSendOnce(payload, connectTimeoutMs, readTimeoutMs, cmdId);
        } catch (SocketException | EOFException e) {
            // Stale pooled connection — remote closed it; reconnect and retry once
            safeClose();
            try {
                return doSendOnce(payload, connectTimeoutMs, readTimeoutMs, cmdId);
            } catch (SocketException | EOFException retryEx) {
                String target = host + ":" + port;
                safeClose();
                throw new TbTcpException("READ", retryEx.getClass().getSimpleName(),
                        "Connection closed by " + target + " (EOF/Reset): " + retryEx.getMessage(), target, cmdId, retryEx);
            }
        }
    }

    private byte[] doSendOnce(byte[] payload, int connectTimeoutMs, int readTimeoutMs, String cmdId)
            throws TbTcpException, IOException {
        String target = host + ":" + port;
        if (!isValid()) {
            connect(connectTimeoutMs, readTimeoutMs);
        } else {
            // Update SO_TIMEOUT to the per-call value for this exchange
            try {
                socket.setSoTimeout(readTimeoutMs);
            } catch (IOException ioe) {
                safeClose();
                throw new TbTcpException("CONNECT", ioe.getClass().getSimpleName(),
                        "Failed to set SO_TIMEOUT on existing socket to " + target + ": " + ioe.getMessage(),
                        target, cmdId, ioe);
            }
        }

        long t1 = System.currentTimeMillis();
        try {
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
            lastWriteMs = System.currentTimeMillis() - t1;
        } catch (SocketException se) {
            lastWriteMs = System.currentTimeMillis() - t1;
            safeClose();
            throw se;
        } catch (IOException ioe) {
            lastWriteMs = System.currentTimeMillis() - t1;
            safeClose();
            throw new TbTcpException("WRITE", ioe.getClass().getSimpleName(),
                    "Write failed to " + target + ": " + ioe.getMessage(), target, cmdId, ioe);
        }

        long t2 = System.currentTimeMillis();
        try {
            byte[] response = doRead(readTimeoutMs, target, cmdId);
            lastReadMs = System.currentTimeMillis() - t2;
            if (response == null || response.length == 0) {
                safeClose();
                throw new TbTcpException("READ", "NoResponse",
                        "No response received from " + target, target, cmdId);
            }
            return response;
        } catch (TbTcpException te) {
            // Already classified — socket closed inside doRead; rethrow
            lastReadMs = System.currentTimeMillis() - t2;
            throw te;
        } catch (SocketException | EOFException se) {
            lastReadMs = System.currentTimeMillis() - t2;
            safeClose();
            throw se;
        } catch (IOException ioe) {
            lastReadMs = System.currentTimeMillis() - t2;
            safeClose();
            throw new TbTcpException("READ", ioe.getClass().getSimpleName(),
                    "Read I/O error from " + target + ": " + ioe.getMessage(), target, cmdId, ioe);
        }
    }

    // ── Core framed read ─────────────────────────────────────────────────

    private byte[] doRead(int readTimeoutMs, String target, String cmdId) throws TbTcpException, IOException {
        if ("DELIMITER".equalsIgnoreCase(framingType)) {
            return readDelimiterFramed(target, cmdId);
        } else if ("LENGTH_PREFIX".equalsIgnoreCase(framingType)) {
            return readLengthPrefixFramed(target, cmdId);
        } else {
            // NONE — smart timeout
            return readSmartTimeout(readTimeoutMs, target, cmdId);
        }
    }

    // ── NONE / Smart Timeout ────────────────────────────────────────────

    /**
     * The first read blocks up to the full readTimeoutMs (already set on socket).
     * Once data arrives, subsequent reads use a short fragmentTimeoutMs window.
     * A SocketTimeoutException on the first read is classified as READ phase.
     */
    private byte[] readSmartTimeout(int readTimeoutMs, String target, String cmdId) throws TbTcpException, IOException {
        InputStream in = bufferedIn;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];

        // Phase 1: first read at full readTimeoutMs (already set on socket)
        int len;
        try {
            len = in.read(tmp);
        } catch (SocketTimeoutException e) {
            safeClose();
            throw new TbTcpException("READ", "SocketTimeoutException",
                    "Read timed out from " + target + " after " + readTimeoutMs + " ms — no initial data", target, cmdId, e);
        }
        if (len == -1) {
            safeClose();
            throw new EOFException("Connection closed by " + target + " before response (EOF)");
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
                        break; // remote closed — response complete
                    }
                    buf.write(tmp, 0, len);
                } catch (SocketTimeoutException e) {
                    // Quiet period expired — response complete
                    break;
                }
            }
        } finally {
            // Restore per-call timeout for the next request on this pooled connection
            if (socket != null) {
                try {
                    socket.setSoTimeout(originalTimeout);
                } catch (IOException ignored) {}
            }
        }
        return buf.toByteArray();
    }

    // ── DELIMITER ───────────────────────────────────────────────────────

    /**
     * Reads byte-by-byte from the buffered stream until the delimiter
     * sequence is matched. Returns all bytes read EXCLUDING the delimiter.
     */
    private byte[] readDelimiterFramed(String target, String cmdId) throws TbTcpException, IOException {
        InputStream in = bufferedIn;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matchIndex = 0;

        while (true) {
            int b;
            try {
                b = in.read();
            } catch (SocketTimeoutException e) {
                safeClose();
                throw new TbTcpException("READ", "SocketTimeoutException",
                        "Read timed out waiting for delimiter from " + target, target, cmdId, e);
            }
            if (b == -1) {
                if (buf.size() == 0) {
                    safeClose();
                    throw new EOFException("Connection closed by " + target + " before receiving delimiter");
                }
                break; // Connection closed before delimiter; return what we have
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
                matchIndex = ((byte) b == delimiterBytes[0]) ? 1 : 0;
            }
        }
        return buf.toByteArray();
    }

    // ── LENGTH_PREFIX (Netty-style) ─────────────────────────────────────

    /**
     * Reads a length-prefixed frame using Netty-style configuration.
     */
    private byte[] readLengthPrefixFramed(String target, String cmdId) throws TbTcpException, IOException {
        InputStream in = bufferedIn;

        int headerSize = lengthFieldOffset + lengthFieldLength;
        byte[] header = new byte[headerSize];
        try {
            readFully(in, header, 0, headerSize);
        } catch (SocketTimeoutException e) {
            safeClose();
            throw new TbTcpException("READ", "SocketTimeoutException",
                    "Read timed out reading length-prefix header from " + target, target, cmdId, e);
        } catch (EOFException e) {
            safeClose();
            throw e;
        }

        long framePayloadLength = decodeLengthField(header, lengthFieldOffset, lengthFieldLength, lengthByteOrder);
        long totalRemaining = framePayloadLength + lengthAdjustment;

        if (totalRemaining < 0) {
            throw new IOException("Negative frame length after adjustment: " + totalRemaining);
        }

        long totalFrameSize = headerSize + totalRemaining;
        if (totalFrameSize > maxFrameLength) {
            throw new IOException("Frame length " + totalFrameSize + " exceeds maxFrameLength " + maxFrameLength);
        }

        byte[] fullFrame = new byte[(int) totalFrameSize];
        System.arraycopy(header, 0, fullFrame, 0, headerSize);
        if (totalRemaining > 0) {
            try {
                readFully(in, fullFrame, headerSize, (int) totalRemaining);
            } catch (SocketTimeoutException e) {
                safeClose();
                throw new TbTcpException("READ", "SocketTimeoutException",
                        "Read timed out reading frame body from " + target, target, cmdId, e);
            } catch (EOFException e) {
                safeClose();
                throw e;
            }
        }

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
        byte[] padded = new byte[8];
        if (order == ByteOrder.BIG_ENDIAN) {
            System.arraycopy(header, offset, padded, 8 - fieldLength, fieldLength);
        } else {
            System.arraycopy(header, offset, padded, 0, fieldLength);
        }
        ByteBuffer bb = ByteBuffer.wrap(padded).order(order);
        return bb.getLong() & 0x7FFF_FFFF_FFFF_FFFFL;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Reads exactly {@code len} bytes. Throws {@link EOFException} if stream
     * ends before all bytes are read, or propagates {@link SocketTimeoutException}.
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

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Sends a string payload using the static fallback timeouts and returns the
     * response as a JSON-wrapped string.
     */
    public String sendRequest(String payload, TbMsgDataType dataType, TbMsgDataType responseType)
            throws TbTcpException, IOException {
        int ct = currentConnectTimeoutMs > 0 ? currentConnectTimeoutMs : defaultConnectTimeoutMs;
        int rt = currentReadTimeoutMs > 0 ? currentReadTimeoutMs : defaultReadTimeoutMs;
        return sendRequest(payload, dataType, responseType, ct, rt, currentCmdId);
    }

    /**
     * Sends a string payload using per-call derived timeouts.
     *
     * @param cmdId optional command correlation ID included in any thrown {@link TbTcpException}
     */
    public String sendRequest(String payload, TbMsgDataType dataType, TbMsgDataType responseType,
            int connectTimeoutMs, int readTimeoutMs, String cmdId) throws TbTcpException, IOException {
        setInvocationContext(connectTimeoutMs, readTimeoutMs, cmdId);
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
        byte[] response = sendRequest(payloadBytes, connectTimeoutMs, readTimeoutMs, cmdId);
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

    /**
     * Returns per-phase timings from the last {@link #sendRequest} call.
     * Values are in milliseconds; {@code -1} means the phase was not reached.
     */
    public long[] getLastPhaseTimings() {
        return new long[]{lastConnectMs, lastWriteMs, lastReadMs};
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
