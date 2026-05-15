/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.spi.security.audit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SecurityAuditEventTest {

    /** Minimal subclass for testing the abstract base. */
    private static final class TestEvent extends SecurityAuditEvent {
        TestEvent(String type) {
            super(type);
        }
    }

    @Test
    public void domainIsAlwaysSecurity() {
        TestEvent e = new TestEvent("anything");
        assertEquals(SecurityAuditDomain.NAME, e.getDomain());
    }

    @Test
    public void typeIsCapturedAtConstruction() {
        TestEvent e = new TestEvent("user.login");
        assertEquals("user.login", e.getType());
    }

    @Test
    public void timestampIsCapturedAtConstruction() {
        long before = System.currentTimeMillis();
        TestEvent e = new TestEvent("t");
        long after = System.currentTimeMillis();
        long ts = e.getTimestamp();
        assertTrue("timestamp " + ts + " not in [" + before + ", " + after + "]",
                ts >= before && ts <= after);
    }
}
