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

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.ContentRepository;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.audit.AuditBufferLifecycle;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventEmitter;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.audit.AuditEvents;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.security.ConfigurationBase;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.OpenSecurityProvider;
import org.apache.jackrabbit.oak.spi.security.SecurityConfiguration;
import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.apache.jackrabbit.oak.spi.toggle.FeatureToggle;
import org.apache.jackrabbit.oak.spi.whiteboard.DefaultWhiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.Registration;
import org.apache.jackrabbit.oak.spi.whiteboard.Tracker;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration test exercising both audit pipelines through a
 * single {@link AuditEventListener#onEvents} method. Uses {@link MemoryNodeStore}
 * for a real but in-process Oak instance.
 * <p>
 * The fixture mirrors what {@code AuditConfigurationImpl.activate()} does at
 * runtime, without an OSGi container:
 * <ol>
 *   <li>Builds a {@link DefaultWhiteboard}, registers a {@link Feature} toggle
 *       and flips it on.</li>
 *   <li>Constructs an {@link AuditBuffer} and installs it on
 *       {@link AuditBufferLifecycle}.</li>
 *   <li>Starts a {@link WhiteboardAuditEventListenerRegistry} against the
 *       whiteboard and registers the test listener.</li>
 *   <li>Installs a {@link AuditEvents.Sink} that mirrors the inner
 *       {@code BufferSink} of {@code AuditConfigurationImpl} — both branches
 *       (commit-attached {@code record} and fire-and-forget {@code dispatch}).
 *       The real {@code BufferSink} is a private nested class; replicating its
 *       behavior locally lets the IT exercise both pipelines without
 *       reflection.</li>
 *   <li>Wires the {@link SnapshotAuditBufferHook} and
 *       {@link DispatchAuditEventsHook} via a custom {@link SecurityProvider}
 *       so {@code MutableRoot} correctly classifies the post-validation hook
 *       (see {@code MutableRoot.getCommitHook}).</li>
 * </ol>
 */
public class AuditPipelineIT {

    private static final String DOMAIN = "test.domain";
    private static final String FEATURE_TOGGLE_NAME = "FT_AUDIT_IT";

    private Whiteboard whiteboard;
    private Feature featureToggle;
    private AuditBuffer buffer;
    private WhiteboardAuditEventListenerRegistry registry;
    private Registration listenerRegistration;
    private List<AuditEvent> received;
    private AuditEventListener listener;
    private ContentRepository repository;
    private AuditEventEmitter emitter;

    @Before
    public void setUp() {
        whiteboard = new DefaultWhiteboard();

        // Feature toggle — register on whiteboard, then flip ON via the
        // FeatureToggle service registered by Feature.newFeature.
        featureToggle = Feature.newFeature(FEATURE_TOGGLE_NAME, whiteboard);
        Tracker<FeatureToggle> toggleTracker = whiteboard.track(FeatureToggle.class);
        try {
            for (FeatureToggle ft : toggleTracker.getServices()) {
                if (FEATURE_TOGGLE_NAME.equals(ft.getName())) {
                    ft.setEnabled(true);
                }
            }
        } finally {
            toggleTracker.stop();
        }

        buffer = new AuditBuffer();
        AuditBufferLifecycle.install(buffer);

        registry = new WhiteboardAuditEventListenerRegistry();
        registry.start(whiteboard);

        // Listener captures events for verification.
        received = new CopyOnWriteArrayList<>();
        listener = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) { received.addAll(events); }
        };
        listenerRegistration = whiteboard.register(AuditEventListener.class, listener, Map.of());

        // Install a Sink mirroring the BufferSink behavior. See class Javadoc.
        AuditEvents.install(new TestBufferSink(featureToggle, registry, buffer));

        // Oak instance with the custom security provider that contributes
        // the two audit commit hooks via a SecurityConfiguration so they're
        // ordered correctly relative to validation.
        SecurityProvider sp = new AuditTestSecurityProvider(
                new AuditTestConfiguration(
                        new SnapshotAuditBufferHook(featureToggle, buffer),
                        new DispatchAuditEventsHook(featureToggle, buffer, registry)));

        repository = new Oak(new MemoryNodeStore())
                .with(sp)
                .with(whiteboard)
                .createContentRepository();

        emitter = new AuditEventEmitterImpl();
    }

    @After
    public void tearDown() throws Exception {
        // Order mirrors AuditConfigurationImpl.deactivate().
        if (featureToggle != null) {
            featureToggle.close();
        }
        if (registry != null) {
            registry.stop();
        }
        if (listenerRegistration != null) {
            listenerRegistration.unregister();
        }
        AuditEvents.install(null);
        AuditBufferLifecycle.install(null);
        if (buffer != null) {
            buffer.clearAll();
        }
        if (repository instanceof Closeable) {
            ((Closeable) repository).close();
        }
    }

    private static AuditEvent eventFor(@NotNull String domain,
                                       @NotNull String type,
                                       @NotNull Map<String, Object> payload) {
        return new AuditEvent() {
            @Override public @NotNull String getDomain() { return domain; }
            @Override public @NotNull String getType() { return type; }
            @Override public long getTimestamp() { return System.currentTimeMillis(); }
            @Override public @NotNull Map<String, Object> getPayload() { return payload; }
        };
    }

    @Test
    public void fireAndForgetEventCarriesNoCommitMetadata() {
        emitter.emit(eventFor(DOMAIN, "forget", Map.of("key", "v")));
        assertEquals(1, received.size());
        AuditEvent e = received.get(0);
        assertEquals("forget", e.getType());
        assertFalse("fire-and-forget event must not carry commit.sessionId",
                e.getPayload().containsKey("commit.sessionId"));
        assertFalse(e.getPayload().containsKey("commit.userId"));
        assertEquals("v", e.getPayload().get("key"));
    }

    @Test
    public void emitNoListenerForDomainIsNoOp() {
        // Listener registered for DOMAIN; emit on a different domain.
        emitter.emit(eventFor("other.domain", "x", Map.of()));
        assertTrue(received.isEmpty());
    }

    @Test
    public void commitAttachedEventCarriesCommitMetadata() throws Exception {
        ContentSession session = repository.login(null, null);
        try {
            Root root = session.getLatestRoot();
            AuditEvents.record(root, eventFor(DOMAIN, "commit.type", Map.of("note", "v")));
            // Mutate so commit isn't a no-op — without a real change, the
            // commit hook chain may short-circuit before reaching dispatch.
            root.getTree("/").setProperty("scratch", "value");
            root.commit();

            assertEquals(1, received.size());
            AuditEvent e = received.get(0);
            assertEquals("commit.type", e.getType());
            Map<String, Object> p = e.getPayload();
            assertTrue("commit-attached event must carry commit.sessionId",
                    p.containsKey("commit.sessionId"));
            assertTrue(p.containsKey("commit.userId"));
            assertTrue(p.containsKey("commit.timestamp"));
            // Original payload entry preserved.
            assertEquals("v", p.get("note"));
        } finally {
            session.close();
        }
    }

    /**
     * Mirrors {@code AuditConfigurationImpl.BufferSink} (private nested type)
     * so the IT exercises both audit paths without an OSGi container or
     * reflective access. If {@code BufferSink} is later promoted to
     * package-private, this can be replaced with a direct instantiation.
     */
    private static final class TestBufferSink implements AuditEvents.Sink {

        private final Feature toggle;
        private final WhiteboardAuditEventListenerRegistry registry;
        private final AuditBuffer buffer;

        TestBufferSink(@NotNull Feature toggle,
                       @NotNull WhiteboardAuditEventListenerRegistry registry,
                       @NotNull AuditBuffer buffer) {
            this.toggle = toggle;
            this.registry = registry;
            this.buffer = buffer;
        }

        @Override
        public boolean isEnabled() {
            return toggle.isEnabled() && registry.hasAnyListener();
        }

        @Override
        public boolean isEnabledFor(@NotNull String domain) {
            return toggle.isEnabled() && registry.hasListenerFor(domain);
        }

        @Override
        public void record(@NotNull Root root, @NotNull AuditEvent event) {
            if (!isEnabledFor(event.getDomain())) {
                return;
            }
            buffer.record(root.getContentSession().toString(), event);
        }

        @Override
        public void dispatch(@NotNull AuditEvent event) {
            if (!toggle.isEnabled()) {
                return;
            }
            List<AuditEventListener> listeners = registry.getListeners();
            if (listeners.isEmpty()) {
                return;
            }
            String domain = event.getDomain();
            List<AuditEvent> single = Collections.singletonList(event);
            for (AuditEventListener l : listeners) {
                if (!domain.equals(l.getDomain())) {
                    continue;
                }
                try {
                    l.onEvents(single);
                } catch (RuntimeException re) {
                    // Mirror BufferSink — swallow.
                }
            }
        }
    }

    /**
     * Wraps {@link OpenSecurityProvider} and adds an extra
     * {@link SecurityConfiguration} (the audit test config) so its commit
     * hooks are picked up by {@code MutableRoot.getCommitHook()} and
     * correctly classified (post-validation hook placed after validators).
     */
    private static final class AuditTestSecurityProvider extends OpenSecurityProvider {

        private final SecurityConfiguration auditConfig;

        AuditTestSecurityProvider(@NotNull SecurityConfiguration auditConfig) {
            this.auditConfig = auditConfig;
        }

        @Override
        public @NotNull Iterable<? extends SecurityConfiguration> getConfigurations() {
            List<SecurityConfiguration> all = new ArrayList<>();
            for (SecurityConfiguration sc : super.getConfigurations()) {
                all.add(sc);
            }
            all.add(auditConfig);
            return all;
        }
    }

    /**
     * Minimal {@link SecurityConfiguration} exposing pre-built commit hooks.
     */
    private static final class AuditTestConfiguration extends ConfigurationBase {

        private final List<CommitHook> hooks;

        AuditTestConfiguration(@NotNull CommitHook... hooks) {
            super();
            setParameters(ConfigurationParameters.EMPTY);
            this.hooks = Arrays.asList(hooks);
        }

        @Override
        public @NotNull String getName() {
            return "audit-test";
        }

        @Override
        public @NotNull List<? extends CommitHook> getCommitHooks(@NotNull String workspaceName) {
            return hooks;
        }
    }
}
