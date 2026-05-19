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
import org.apache.jackrabbit.oak.spi.toggle.FeatureToggle;
import org.apache.jackrabbit.oak.spi.whiteboard.DefaultWhiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.Tracker;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavioural coverage for
 * {@link AuditConfigurationImpl#isActive() AuditConfigurationImpl.isActive()}.
 * <p>
 * The {@code isActive()} body itself is a one-line delegation to
 * {@link org.apache.jackrabbit.oak.spi.audit.AuditEvents#isEnabled()
 * AuditEvents.isEnabled()}, so branch coverage of the body is trivial.
 * The tests below exercise the end-to-end <em>wiring</em>: how the impl's
 * lifecycle ({@link AuditConfigurationImpl#initialize(Whiteboard) initialize},
 * feature-toggle flip, listener registration,
 * {@link AuditConfigurationImpl#dispose() dispose}) affects the predicate
 * that {@code isActive()} ultimately reports.
 * <p>
 * The 4 reachable states tested:
 * <ol>
 *   <li>Not initialised — {@code AuditEvents.sink} is the default NOOP →
 *       {@code isActive()} returns {@code false}.</li>
 *   <li>Initialised, toggle OFF (default) — {@code BufferSink.isEnabled()}
 *       short-circuits on the toggle → {@code false}.</li>
 *   <li>Initialised, toggle ON, no listener registered — {@code BufferSink}
 *       short-circuits on {@code registry.hasAnyListener()} → {@code false}.</li>
 *   <li>Initialised, toggle ON, listener registered — both AND clauses
 *       satisfied → {@code true}.</li>
 *   <li>After {@code dispose()} — {@code AuditEvents.install(null)} resets
 *       the sink back to NOOP → {@code false}.</li>
 * </ol>
 * The internal AND-chain branch coverage of
 * {@code BufferSink.isEnabled()} is the responsibility of
 * {@code BufferSink}'s own tests; here we verify only that
 * {@code isActive()} faithfully reports the pipeline state established by
 * the impl's lifecycle.
 */
public class AuditConfigurationImplTest {

    private Whiteboard whiteboard;
    private AuditConfigurationImpl config;

    @Before
    public void setUp() {
        whiteboard = new DefaultWhiteboard();
        config = new AuditConfigurationImpl();
    }

    @After
    public void tearDown() {
        // Always dispose to reset the static AuditEvents.sink to NOOP — keeps
        // tests isolated from each other even though they share the static
        // façade. Safe to call even if initialize() was never invoked.
        config.dispose();
    }

    @Test
    public void isActiveReturnsFalseWhenNotInitialized() {
        // No initialize() call — the default NOOP sink reports isEnabled() == false.
        assertFalse("uninitialised pipeline must report inactive", config.isActive());
    }

    @Test
    public void isActiveReturnsFalseWhenToggleOff() {
        config.initialize(whiteboard);
        // Toggle defaults to disabled (FT_AUDIT is OFF by default per AGENTS.md).
        // BufferSink.isEnabled() short-circuits on toggle.isEnabled().
        assertFalse("toggle OFF must report inactive", config.isActive());
    }

    @Test
    public void isActiveReturnsFalseWhenToggleOnButNoListener() {
        config.initialize(whiteboard);
        setToggle(true);
        // Toggle ON but no AuditEventListener registered yet —
        // BufferSink.isEnabled() short-circuits on registry.hasAnyListener().
        assertFalse("toggle ON without listener must report inactive", config.isActive());
    }

    @Test
    public void isActiveReturnsTrueWhenToggleOnAndListenerRegistered() {
        config.initialize(whiteboard);
        setToggle(true);
        registerTestListener();
        // Both AND clauses satisfied — pipeline is active.
        assertTrue("toggle ON + listener registered must report active", config.isActive());
    }

    @Test
    public void isActiveReturnsFalseAfterDispose() {
        // First bring the pipeline up so it's known-active...
        config.initialize(whiteboard);
        setToggle(true);
        registerTestListener();
        assertTrue("precondition: pipeline must be active before dispose", config.isActive());

        // ...then dispose, which resets the static sink to NOOP.
        config.dispose();
        assertFalse("disposed pipeline must report inactive", config.isActive());
    }

    //----------------------------------------------------------< fixtures >---

    /**
     * Flips the FT_AUDIT feature toggle by locating the {@link FeatureToggle}
     * service that {@link AuditConfigurationImpl#initialize(Whiteboard)
     * initialize} registered on the whiteboard.
     */
    private void setToggle(boolean enabled) {
        Tracker<FeatureToggle> tracker = whiteboard.track(FeatureToggle.class);
        try {
            for (FeatureToggle ft : tracker.getServices()) {
                if (AuditConfigurationImpl.FEATURE_TOGGLE_NAME.equals(ft.getName())) {
                    ft.setEnabled(enabled);
                }
            }
        } finally {
            tracker.stop();
        }
    }

    private void registerTestListener() {
        AuditEventListener listener = new AuditEventListener() {
            @NotNull
            @Override
            public String getDomain() {
                return "test.isActive.coverage";
            }

            @Override
            public void onEvents(@NotNull List<AuditEvent> events) {
                // not exercised — isActive() only needs the listener to be
                // registered, not invoked.
            }
        };
        whiteboard.register(AuditEventListener.class, listener, Map.of());
    }
}
