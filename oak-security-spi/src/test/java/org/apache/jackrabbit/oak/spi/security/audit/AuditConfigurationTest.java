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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AuditConfigurationTest {

    @Test
    public void noopReturnsConfigurationName() {
        assertEquals(AuditConfiguration.NAME, AuditConfiguration.NOOP.getName());
    }

    @Test
    public void noopContributesNoCommitHooks() {
        // Default impl inherited from SecurityConfiguration.Default returns
        // an empty list — verified here to lock in the NOOP contract.
        assertTrue(AuditConfiguration.NOOP.getCommitHooks("default").isEmpty());
    }

    @Test
    public void nameConstantIsStable() {
        assertEquals("org.apache.jackrabbit.oak.audit", AuditConfiguration.NAME);
    }

    @Test
    public void noopSingletonIsNotNull() {
        assertNotNull(AuditConfiguration.NOOP);
    }

    @Test
    public void noopIsActiveReturnsFalse() {
        // NOOP placeholder: the audit pipeline is by definition NOT active when
        // no implementation is bound. The Noop inner class explicitly overrides
        // isActive() to return false (rather than inheriting any default), so
        // any caller probing via securityProvider.getConfiguration(AuditConfiguration.class).isActive()
        // safely reports "audit not running" on a vanilla SecurityProvider.
        assertFalse(AuditConfiguration.NOOP.isActive());
    }
}
