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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import org.thingsboard.server.common.msg.TbMsgDataType;

/**
 * Simple TCP client for ThingsBoard Rule Engine nodes.
 * Inspired by TbHttpClient, but for TCP/TLS connections.
 */
public class TbTcpClient {
    // All encoding/decoding now uses TbMsgDataType directly

    private final String host;
    private final int port;
    private final boolean tls;
    private final SSLSocketFactory sslSocketFactory;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public TbTcpClient(String host, int port, boolean tls, SSLSocketFactory sslSocketFactory, int connectTimeoutMs,
            int readTimeoutMs) {
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
    public byte[] sendRequest(byte[] payload) throws IOException {
        Socket socket = null;
        try {
            socket = tls && sslSocketFactory != null ? sslSocketFactory.createSocket()
                    : SocketFactory.getDefault().createSocket();
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
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

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
        return "{\"response\":\"" + decodeResponse(response, responseType) + "\"}";
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
// ...existing code...
