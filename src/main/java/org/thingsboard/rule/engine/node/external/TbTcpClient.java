package org.thingsboard.rule.engine.node.external;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Simple TCP client for ThingsBoard Rule Engine nodes.
 * Inspired by TbHttpClient, but for TCP/TLS connections.
 */
public class TbTcpClient {
    public enum PayloadType { STRING, JSON, BINARY }

    private final String host;
    private final int port;
    private final boolean tls;
    private final SSLSocketFactory sslSocketFactory;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public TbTcpClient(String host, int port, boolean tls, SSLSocketFactory sslSocketFactory, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.sslSocketFactory = sslSocketFactory;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Sends the payload and returns the response as bytes.
     */
    public byte[] sendAndReceive(byte[] payload) throws IOException {
        Socket socket = null;
        try {
            socket = tls && sslSocketFactory != null ?
                    sslSocketFactory.createSocket() :
                    SocketFactory.getDefault().createSocket();
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            int len = in.read(buffer);
            if (len == -1) {
                throw new IOException("No response received from TCP server");
            }
            byte[] response = new byte[len];
            System.arraycopy(buffer, 0, response, 0, len);
            return response;
        } catch (SocketTimeoutException e) {
            throw new IOException("TCP read timed out", e);
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Sends a string payload and returns the response as a string.
     */
    public String sendAndReceive(String payload, PayloadType payloadType) throws IOException {
        byte[] payloadBytes;
        if (payloadType == PayloadType.BINARY) {
            payloadBytes = java.util.Base64.getDecoder().decode(payload);
        } else {
            payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        }
        byte[] response = sendAndReceive(payloadBytes);
        return decodeResponse(response, payloadType);
    }

    /**
     * Decodes the response bytes according to the payload type.
     */
    public String decodeResponse(byte[] response, PayloadType payloadType) {
        if (payloadType == PayloadType.BINARY) {
            return java.util.Base64.getEncoder().encodeToString(response);
        } else {
            return new String(response, StandardCharsets.UTF_8);
        }
    }
}
