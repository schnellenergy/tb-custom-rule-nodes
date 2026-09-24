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

/**
 * Structured TCP exception that carries phase, classification and correlation
 * identifiers so callers can route to the right failure chain and populate
 * device attributes without parsing exception message strings.
 *
 * <p>Phase values:
 * <ul>
 *   <li>{@code CONNECT} — TCP handshake / dial failed (e.g. Connection refused)</li>
 *   <li>{@code WRITE}   — sending the request payload failed</li>
 *   <li>{@code READ}    — read timeout or I/O error while reading the response</li>
 *   <li>{@code POOL}    — connection-pool level rejection (e.g. BUSY)</li>
 * </ul>
 *
 * Created as part of fixes for SmartLights_2.0 issues #4433, #4434, #4435.
 */
public class TbTcpException extends Exception {

    /** One of: CONNECT, WRITE, READ, POOL */
    private final String phase;

    /** Simple class name of the root cause (e.g. "ConnectException", "SocketTimeoutException"). */
    private final String errorClass;

    /** Human-readable detail message, may include target endpoint. */
    private final String errorDetail;

    /** Target endpoint in {@code "host:port"} form. */
    private final String target;

    /**
     * Command correlation identifier extracted from message metadata.
     * May be {@code null} when not supplied by the caller.
     */
    private final String cmdId;

    public TbTcpException(String phase, String errorClass, String errorDetail, String target, String cmdId) {
        super("[" + phase + "] " + errorDetail + " (target=" + target + (cmdId != null ? ", cmdId=" + cmdId : "") + ")");
        this.phase = phase;
        this.errorClass = errorClass;
        this.errorDetail = errorDetail;
        this.target = target;
        this.cmdId = cmdId;
    }

    public TbTcpException(String phase, String errorClass, String errorDetail, String target, String cmdId, Throwable cause) {
        super("[" + phase + "] " + errorDetail + " (target=" + target + (cmdId != null ? ", cmdId=" + cmdId : "") + ")", cause);
        this.phase = phase;
        this.errorClass = errorClass;
        this.errorDetail = errorDetail;
        this.target = target;
        this.cmdId = cmdId;
    }

    public String getPhase() {
        return phase;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public String getTarget() {
        return target;
    }

    public String getCmdId() {
        return cmdId;
    }
}
