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

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit Tests for TbDlmsDownlinkNodeConfiguration
 * Tests: Configuration defaults, validation, ranges
 */
public class TbDlmsDownlinkNodeConfigurationTest {

    @Test
    public void testDefaultConfiguration() {
        TbDlmsDownlinkNodeConfiguration config = new TbDlmsDownlinkNodeConfiguration();
        TbDlmsDownlinkNodeConfiguration defaults = config.defaultConfiguration();

        assertNotNull("Defaults should not be null", defaults);
        assertEquals("Thread pool size default", 8, defaults.getThreadPoolSize());
        assertEquals("Max connections default", 10, defaults.getMaxConnections());
        assertEquals("Connection timeout default", 5000, defaults.getConnectionTimeoutMs());
        assertEquals("Response timeout default", 30, defaults.getResponseTimeoutSeconds());
        assertEquals("Total timeout default", 35, defaults.getTotalTimeoutSeconds());
    }

    @Test
    public void testConfigurationSettersGetters() {
        TbDlmsDownlinkNodeConfiguration config = new TbDlmsDownlinkNodeConfiguration();

        config.setThreadPoolSize(16);
        config.setMaxConnections(20);
        config.setConnectionTimeoutMs(3000);
        config.setResponseTimeoutSeconds(60);
        config.setTotalTimeoutSeconds(65);

        assertEquals("Thread pool size", 16, config.getThreadPoolSize());
        assertEquals("Max connections", 20, config.getMaxConnections());
        assertEquals("Connection timeout", 3000, config.getConnectionTimeoutMs());
        assertEquals("Response timeout", 60, config.getResponseTimeoutSeconds());
        assertEquals("Total timeout", 65, config.getTotalTimeoutSeconds());
    }

    @Test
    public void testMinimalConfiguration() {
        TbDlmsDownlinkNodeConfiguration config = new TbDlmsDownlinkNodeConfiguration();
        config.setThreadPoolSize(1);
        config.setMaxConnections(1);

        assertEquals("Should allow minimal size", 1, config.getThreadPoolSize());
        assertEquals("Should allow 1 connection", 1, config.getMaxConnections());
    }

    @Test
    public void testHighPerformanceConfiguration() {
        TbDlmsDownlinkNodeConfiguration config = new TbDlmsDownlinkNodeConfiguration();
        config.setThreadPoolSize(32);
        config.setMaxConnections(100);
        config.setConnectionTimeoutMs(2000);
        config.setResponseTimeoutSeconds(15);

        assertEquals("Should allow many threads", 32, config.getThreadPoolSize());
        assertEquals("Should allow many connections", 100, config.getMaxConnections());
    }

    @Test
    public void testTimeoutConsistency() {
        TbDlmsDownlinkNodeConfiguration config = new TbDlmsDownlinkNodeConfiguration();

        // Total timeout should be >= response timeout
        config.setResponseTimeoutSeconds(30);
        config.setTotalTimeoutSeconds(35);

        assertTrue("Total should be >= response",
            config.getTotalTimeoutSeconds() >= config.getResponseTimeoutSeconds());
    }

    @Test
    public void testConfigurationImplementsInterface() {
        TbDlmsDownlinkNodeConfiguration config = new TbDlmsDownlinkNodeConfiguration();

        // Should implement NodeConfiguration
        assertTrue("Should implement NodeConfiguration",
            config instanceof org.thingsboard.rule.engine.api.NodeConfiguration);
    }

    @Test
    public void testMultipleConfigurations() {
        TbDlmsDownlinkNodeConfiguration config1 = new TbDlmsDownlinkNodeConfiguration();
        TbDlmsDownlinkNodeConfiguration config2 = new TbDlmsDownlinkNodeConfiguration();

        config1.setThreadPoolSize(8);
        config2.setThreadPoolSize(16);

        assertEquals("Config 1 should be 8", 8, config1.getThreadPoolSize());
        assertEquals("Config 2 should be 16", 16, config2.getThreadPoolSize());
    }

    @Test
    public void testAllFieldsAccessible() {
        TbDlmsDownlinkNodeConfiguration config = new TbDlmsDownlinkNodeConfiguration();

        // Test all fields can be set and retrieved
        config.setThreadPoolSize(10);
        config.setMaxConnections(15);
        config.setConnectionTimeoutMs(4000);
        config.setResponseTimeoutSeconds(25);
        config.setTotalTimeoutSeconds(30);

        assertEquals(10, config.getThreadPoolSize());
        assertEquals(15, config.getMaxConnections());
        assertEquals(4000, config.getConnectionTimeoutMs());
        assertEquals(25, config.getResponseTimeoutSeconds());
        assertEquals(30, config.getTotalTimeoutSeconds());
    }
}
