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
package org.apache.jackrabbit.oak.security.audit;

import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.whiteboard.DefaultWhiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.Registration;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class WhiteboardAuditEventListenerRegistryTest {

    private static final class StubListener implements AuditEventListener {
        private final String domain;
        private final int rank;
        StubListener(String domain, int rank) {
            this.domain = domain;
            this.rank = rank;
        }
        @Override public @NotNull String getDomain() { return domain; }
        @Override public int getRank() { return rank; }
        @Override public void onEvents(@NotNull List<AuditEvent> events) { /* not exercised here */ }
    }

    @Test
    public void emptyByDefault() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry();
        reg.start(wb);
        try {
            assertEquals(0, reg.getListeners().size());
            assertFalse(reg.hasAnyListener());
            assertFalse(reg.hasListenerFor("security"));
        } finally {
            reg.stop();
        }
    }

    @Test
    public void registeredListenerIsListed() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry();
        reg.start(wb);
        try {
            wb.register(AuditEventListener.class, new StubListener("security", 0), Map.of());
            assertEquals(1, reg.getListeners().size());
            assertTrue(reg.hasAnyListener());
            assertTrue(reg.hasListenerFor("security"));
        } finally {
            reg.stop();
        }
    }

    @Test
    public void listenersSortedByRankDescending() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry();
        reg.start(wb);
        try {
            wb.register(AuditEventListener.class, new StubListener("d", 1), Map.of());
            wb.register(AuditEventListener.class, new StubListener("d", 10), Map.of());
            wb.register(AuditEventListener.class, new StubListener("d", 5), Map.of());
            List<AuditEventListener> sorted = reg.getListeners();
            assertEquals(3, sorted.size());
            assertEquals(10, sorted.get(0).getRank());
            assertEquals(5, sorted.get(1).getRank());
            assertEquals(1, sorted.get(2).getRank());
        } finally {
            reg.stop();
        }
    }

    /**
     * Pins the live-lookup contract — {@code hasListenerFor} must reflect
     * the current Whiteboard state, not a snapshot taken at start time.
     * Regression guard for the stale-cache bug fixed in commit
     * {@code 3a60d60309}.
     */
    @Test
    public void hasListenerForReflectsLiveRegistrations() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry();
        reg.start(wb);
        try {
            // First call when no listener for "security" exists.
            assertFalse(reg.hasListenerFor("security"));
            // Register and re-check — must observe the new registration.
            wb.register(AuditEventListener.class, new StubListener("security", 0), Map.of());
            assertTrue(reg.hasListenerFor("security"));
            // Different domain must still return false.
            assertFalse(reg.hasListenerFor("aem.content"));
        } finally {
            reg.stop();
        }
    }

    /**
     * Listener-order stability when ranks tie. The Javadoc on
     * {@link WhiteboardAuditEventListenerRegistry} promises
     * "ties preserve Whiteboard insertion order because List.sort is stable."
     * Without this test, a refactor that swaps the stable sort for an
     * unstable one (e.g. quicksort-variants tend to be) would silently
     * break observable listener order without failing existing tests.
     */
    @Test
    public void stableOrderAmongEqualRanks() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry();
        reg.start(wb);
        try {
            StubListener first = new StubListener("d", 5);
            StubListener second = new StubListener("d", 5);
            StubListener third = new StubListener("d", 5);
            wb.register(AuditEventListener.class, first, Map.of());
            wb.register(AuditEventListener.class, second, Map.of());
            wb.register(AuditEventListener.class, third, Map.of());
            List<AuditEventListener> sorted = reg.getListeners();
            assertEquals(3, sorted.size());
            // Insertion order preserved among equal ranks.
            assertSame(first, sorted.get(0));
            assertSame(second, sorted.get(1));
            assertSame(third, sorted.get(2));
        } finally {
            reg.stop();
        }
    }

    /**
     * Unregistering a listener via the {@link Registration#unregister()}
     * handle must remove it from {@link WhiteboardAuditEventListenerRegistry#getListeners()}
     * and from {@link WhiteboardAuditEventListenerRegistry#hasListenerFor(String)}.
     */
    @Test
    public void unregisterRemovesListener() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry();
        reg.start(wb);
        try {
            Registration r = wb.register(AuditEventListener.class,
                    new StubListener("security", 0), Map.of());
            assertEquals(1, reg.getListeners().size());
            assertTrue(reg.hasListenerFor("security"));

            r.unregister();

            assertEquals(0, reg.getListeners().size());
            assertFalse(reg.hasListenerFor("security"));
            assertFalse(reg.hasAnyListener());
        } finally {
            reg.stop();
        }
    }
}
