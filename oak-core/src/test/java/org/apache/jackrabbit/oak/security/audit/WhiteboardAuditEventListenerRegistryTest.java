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
import java.util.Set;

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
     * Listener ordering with a mix of distinct and equal ranks:
     * <ul>
     *   <li>Strict descending order where ranks differ — the highest-rank
     *       listener comes first, the lowest-rank last.</li>
     *   <li>Equal-rank entries appear as a contiguous block; their internal
     *       order is determined by the underlying {@link Whiteboard} (and
     *       must be stable across repeat {@code getListeners()} calls per
     *       the registry Javadoc).</li>
     * </ul>
     * Note: {@link DefaultWhiteboard} stores services in an identity-hash
     * set, so it does NOT preserve registration order for equal-rank
     * entries — only OSGi's {@code OsgiWhiteboard} honors registration
     * order via {@code service.ranking}. This test therefore asserts set
     * equality (not list equality) on the equal-rank block, plus
     * determinism on repeat calls.
     */
    @Test
    public void stableOrderAmongEqualRanks() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry();
        reg.start(wb);
        try {
            StubListener high = new StubListener("d", 10);
            StubListener midA = new StubListener("d", 5);
            StubListener midB = new StubListener("d", 5);
            StubListener midC = new StubListener("d", 5);
            StubListener low = new StubListener("d", 1);
            wb.register(AuditEventListener.class, high, Map.of());
            wb.register(AuditEventListener.class, midA, Map.of());
            wb.register(AuditEventListener.class, midB, Map.of());
            wb.register(AuditEventListener.class, midC, Map.of());
            wb.register(AuditEventListener.class, low, Map.of());

            List<AuditEventListener> sorted = reg.getListeners();
            assertEquals(5, sorted.size());

            // Strict ordering where ranks differ.
            assertSame("highest rank must be first", high, sorted.get(0));
            assertSame("lowest rank must be last", low, sorted.get(4));

            // Equal-rank entries (rank 5) form a contiguous block in
            // positions 1..3 — set equality, not list equality, because
            // DefaultWhiteboard does not preserve registration order.
            Set<AuditEventListener> middle = Set.copyOf(sorted.subList(1, 4));
            assertEquals("middle three positions must hold all rank-5 entries",
                    Set.of(midA, midB, midC), middle);

            // Stable sort: repeat call must return identical order. An
            // unstable sort would reorder the equal-rank entries on the
            // second call even with the same input.
            assertEquals("stable sort — repeat call returns identical order",
                    sorted, reg.getListeners());
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
