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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jcr.Credentials;
import javax.jcr.SimpleCredentials;
import javax.security.auth.login.Configuration;

import org.apache.jackrabbit.oak.InitialContentHelper;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.ContentRepository;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.security.internal.SecurityProviderBuilder;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventEmitter;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.audit.AuditEvents;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.DefaultValidator;
import org.apache.jackrabbit.oak.spi.commit.Validator;
import org.apache.jackrabbit.oak.spi.commit.ValidatorProvider;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.spi.security.authentication.ConfigurationUtil;
import org.apache.jackrabbit.oak.spi.state.NodeState;
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end integration test exercising both audit pipelines through a real
 * {@link SecurityProviderBuilder}-wired {@link AuditConfigurationImpl}. Uses
 * {@link MemoryNodeStore} for a real but in-process Oak instance.
 * <p>
 * The fixture deliberately uses the production wiring path:
 * <ol>
 *   <li>{@link SecurityProviderBuilder#withAuditConfiguration(org.apache.jackrabbit.oak.spi.security.audit.AuditConfiguration)}
 *       + {@link SecurityProviderBuilder#withWhiteboard(Whiteboard)} drive
 *       {@link AuditConfigurationImpl#initialize(Whiteboard)} on
 *       {@link SecurityProviderBuilder#build()}.</li>
 *   <li>The {@code AuditConfigurationImpl} registers itself as a
 *       {@link org.apache.jackrabbit.oak.spi.security.SecurityConfiguration}
 *       that contributes the audit commit hooks — so Oak's commit chain
 *       picks them up automatically with correct hook ordering.</li>
 *   <li>Teardown calls {@link AuditConfigurationImpl#dispose()} — the same
 *       code path that OSGi {@code @Deactivate} uses.</li>
 * </ol>
 * No test mirror of {@code BufferSink} or hook wiring exists in this class;
 * a bug in either pipeline will surface here.
 */
public class AuditPipelineIT {

    private static final String DOMAIN = "test.domain";
    private static final String OTHER_DOMAIN = "other.domain";
    private static final String FEATURE_TOGGLE_NAME = AuditConfigurationImpl.FEATURE_TOGGLE_NAME;

    private Whiteboard whiteboard;
    private AuditConfigurationImpl auditConfig;
    private Registration listenerRegistration;
    private List<AuditEvent> received;
    private ContentRepository repository;
    private AuditEventEmitter emitter;
    private SecurityProvider securityProvider;

    @Before
    public void setUp() {
        whiteboard = new DefaultWhiteboard();
        received = new CopyOnWriteArrayList<>();

        auditConfig = new AuditConfigurationImpl();
        securityProvider = SecurityProviderBuilder.newBuilder()
                .withWhiteboard(whiteboard)
                .withAuditConfiguration(auditConfig)
                .build();

        // JAAS — wire the default authentication configuration from the
        // SecurityProvider's params so repository.login(adminCreds) succeeds.
        Configuration.setConfiguration(
                ConfigurationUtil.getDefaultConfiguration(ConfigurationParameters.EMPTY));

        // Flip the feature toggle ON via the FeatureToggle service the
        // AuditConfigurationImpl.initialize() call registered on the
        // whiteboard.
        setToggle(true);

        // Register a domain-scoped listener that captures events for
        // verification. Single listener tests use DOMAIN; multi-listener
        // tests register additional listeners inline.
        AuditEventListener listener = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                received.addAll(events);
            }
        };
        listenerRegistration = whiteboard.register(AuditEventListener.class, listener, Map.of());

        repository = new Oak(new MemoryNodeStore(InitialContentHelper.INITIAL_CONTENT))
                .with(securityProvider)
                .with(whiteboard)
                .createContentRepository();

        emitter = new AuditEventEmitterImpl();
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (listenerRegistration != null) {
                listenerRegistration.unregister();
            }
            if (auditConfig != null) {
                auditConfig.dispose();
            }
            if (repository instanceof Closeable) {
                ((Closeable) repository).close();
            }
        } finally {
            Configuration.setConfiguration(null);
        }
    }

    private static Credentials adminCredentials() {
        return new SimpleCredentials("admin", "admin".toCharArray());
    }

    private void setToggle(boolean enabled) {
        Tracker<FeatureToggle> toggleTracker = whiteboard.track(FeatureToggle.class);
        try {
            for (FeatureToggle ft : toggleTracker.getServices()) {
                if (FEATURE_TOGGLE_NAME.equals(ft.getName())) {
                    ft.setEnabled(enabled);
                }
            }
        } finally {
            toggleTracker.stop();
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

    private ContentSession login() throws Exception {
        return repository.login(adminCredentials(), null);
    }

    //--------------------------------------------------------< original 3 >---

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
        emitter.emit(eventFor(OTHER_DOMAIN, "x", Map.of()));
        assertTrue(received.isEmpty());
    }

    @Test
    public void commitAttachedEventCarriesCommitMetadata() throws Exception {
        try (ContentSession session = login()) {
            Root root = session.getLatestRoot();
            AuditEvents.record(
                    root, eventFor(DOMAIN, "commit.type", Map.of("note", "v")));
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
            assertEquals("v", p.get("note"));
        }
    }

    //------------------------------------------------< new — discard tests >---

    /**
     * After a failed commit, the events staged in the per-session buffer
     * must be discarded. The strongest assertion is end-to-end: do a
     * SUBSEQUENT successful commit on the same session and verify only the
     * fresh event arrives. A naive "buffer empty after failure" assertion
     * would pass under a regression that drained the wrong session's slot.
     */
    @Test
    public void commitFailureDiscardsStagedEvents() throws Exception {
        // Build a separate Oak instance with an injected throwing validator
        // — the main fixture's repository can't carry the validator without
        // breaking the success-path tests. The audit pipeline state on the
        // whiteboard is shared, which is what we want to exercise.
        ContentRepository repo2 = new Oak(new MemoryNodeStore(InitialContentHelper.INITIAL_CONTENT))
                .with(securityProvider)
                .with(whiteboard)
                .with(new ThrowingValidatorProvider("trigger-failure"))
                .createContentRepository();
        try (ContentSession session = repo2.login(adminCredentials(), null)) {
            // Stage E1, then force commit failure via the trigger property.
            Root r1 = session.getLatestRoot();
            AuditEvents.record(
                    r1, eventFor(DOMAIN, "discarded",
                            Map.of("trace.id", "E1-from-failed-commit")));
            r1.getTree("/").setProperty("trigger-failure", "boom");
            try {
                r1.commit();
                fail("Expected CommitFailedException from injected validator");
            } catch (CommitFailedException expected) {
                // expected
            }

            // Subsequent successful commit on the SAME session.
            Root r2 = session.getLatestRoot();
            AuditEvents.record(
                    r2, eventFor(DOMAIN, "delivered",
                            Map.of("trace.id", "E2-from-successful-commit")));
            r2.getTree("/").setProperty("scratch", "value");
            r2.commit();

            assertEquals("only E2 must be delivered", 1, received.size());
            AuditEvent d = received.get(0);
            assertEquals("event type is E2's", "delivered", d.getType());
            assertEquals("payload is E2's, not E1's or merged",
                    "E2-from-successful-commit", d.getPayload().get("trace.id"));
            assertEquals("commit.sessionId decorates with current session",
                    session.toString(), d.getPayload().get("commit.sessionId"));
        } finally {
            if (repo2 instanceof Closeable) {
                ((Closeable) repo2).close();
            }
        }
    }

    /**
     * After {@code root.refresh()} the staged events for the session must
     * be discarded — mirrors {@link #commitFailureDiscardsStagedEvents()}.
     */
    @Test
    public void refreshDiscardsStagedEvents() throws Exception {
        try (ContentSession session = login()) {
            Root r1 = session.getLatestRoot();
            AuditEvents.record(
                    r1, eventFor(DOMAIN, "discarded",
                            Map.of("trace.id", "E1-discarded-by-refresh")));
            r1.refresh();

            // After refresh, dispatch a fresh event and commit.
            Root r2 = session.getLatestRoot();
            AuditEvents.record(
                    r2, eventFor(DOMAIN, "delivered",
                            Map.of("trace.id", "E2-after-refresh")));
            r2.getTree("/").setProperty("scratch", "v");
            r2.commit();

            assertEquals("only E2 must be delivered", 1, received.size());
            AuditEvent d = received.get(0);
            assertEquals("delivered", d.getType());
            assertEquals("E2-after-refresh", d.getPayload().get("trace.id"));
        }
    }

    /**
     * After {@code root.rebase()} the staged events for the session must
     * be discarded. Same SPI listener ({@code onRefresh}) drives both
     * refresh and rebase per the audit-spi v1 contract.
     */
    @Test
    public void rebaseDiscardsStagedEvents() throws Exception {
        try (ContentSession session = login()) {
            Root r1 = session.getLatestRoot();
            AuditEvents.record(
                    r1, eventFor(DOMAIN, "discarded",
                            Map.of("trace.id", "E1-discarded-by-rebase")));
            r1.rebase();

            Root r2 = session.getLatestRoot();
            AuditEvents.record(
                    r2, eventFor(DOMAIN, "delivered",
                            Map.of("trace.id", "E2-after-rebase")));
            r2.getTree("/").setProperty("scratch", "v");
            r2.commit();

            assertEquals("only E2 must be delivered", 1, received.size());
            assertEquals("E2-after-rebase",
                    received.get(0).getPayload().get("trace.id"));
        }
    }

    //----------------------------------------< toggle, grouping, isolation >---

    /**
     * With the feature toggle disabled, neither pipeline emits to listeners.
     * Pins the {@code if (!featureToggle.isEnabled()) return} early-returns
     * in both {@code SnapshotAuditBufferHook} and {@code DispatchAuditEventsHook},
     * as well as the toggle gate in {@code BufferSink}.
     */
    @Test
    public void toggleDisabledShortCircuitsEntirePipeline() throws Exception {
        setToggle(false);

        // Fire-and-forget path:
        emitter.emit(eventFor(DOMAIN, "forget", Map.of()));
        assertTrue("fire-and-forget must short-circuit with toggle disabled",
                received.isEmpty());

        // Commit-attached path:
        try (ContentSession session = login()) {
            Root root = session.getLatestRoot();
            AuditEvents.record(
                    root, eventFor(DOMAIN, "commit.type", Map.of()));
            root.getTree("/").setProperty("scratch", "v");
            root.commit();
            assertTrue("commit-attached must short-circuit with toggle disabled",
                    received.isEmpty());
        }
    }

    /**
     * Three events recorded on two domains: listener-A (DOMAIN) receives the
     * two for its domain in capture order; listener-B (OTHER_DOMAIN) receives
     * only its one. Pins {@code groupByDomain} fan-out.
     */
    @Test
    public void multipleEventsAcrossDomainsGroupedCorrectly() throws Exception {
        List<AuditEvent> otherReceived = new CopyOnWriteArrayList<>();
        AuditEventListener otherListener = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return OTHER_DOMAIN; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                otherReceived.addAll(events);
            }
        };
        Registration otherReg = whiteboard.register(AuditEventListener.class,
                otherListener, Map.of());
        try (ContentSession session = login()) {
            Root root = session.getLatestRoot();
            AuditEvents.record(
                    root, eventFor(DOMAIN, "a-1", Map.of()));
            AuditEvents.record(
                    root, eventFor(OTHER_DOMAIN, "b-1", Map.of()));
            AuditEvents.record(
                    root, eventFor(DOMAIN, "a-2", Map.of()));
            root.getTree("/").setProperty("scratch", "v");
            root.commit();

            assertEquals("DOMAIN listener receives 2 events in capture order",
                    2, received.size());
            assertEquals("a-1", received.get(0).getType());
            assertEquals("a-2", received.get(1).getType());

            assertEquals("OTHER_DOMAIN listener receives 1 event",
                    1, otherReceived.size());
            assertEquals("b-1", otherReceived.get(0).getType());
        } finally {
            otherReg.unregister();
        }
    }

    /**
     * After a successful commit, the per-thread {@link AuditBuffer}'s slot
     * for the session must be drained. Asserted behaviorally via a
     * second commit on the SAME session — if drain didn't run after the
     * first commit, the second snapshot would re-include E1 and we'd see
     * three deliveries total (E1 dispatched by commit#1, then E1+E2
     * re-dispatched by commit#2) instead of two.
     */
    @Test
    public void bufferDrainedAfterSuccessfulCommit() throws Exception {
        try (ContentSession session = login()) {
            // Commit #1: record E1, commit.
            Root r1 = session.getLatestRoot();
            AuditEvents.record(
                    r1, eventFor(DOMAIN, "e1", Map.of("trace.id", "E1")));
            r1.getTree("/").setProperty("scratch1", "v");
            r1.commit();

            // Commit #2 on the same session: record E2, commit.
            Root r2 = session.getLatestRoot();
            AuditEvents.record(
                    r2, eventFor(DOMAIN, "e2", Map.of("trace.id", "E2")));
            r2.getTree("/").setProperty("scratch2", "v");
            r2.commit();

            // Exactly two deliveries — E1 first, then E2. If drain were
            // broken after commit#1, we'd see [E1, E1, E2] = 3 events.
            assertEquals("buffer must be drained between commits", 2, received.size());
            assertEquals("first received is E1", "e1", received.get(0).getType());
            assertEquals("second received is E2", "e2", received.get(1).getType());
            // The marker is the regression-guard: a re-dispatch would
            // duplicate "E1" at position 1, not produce a fresh "E2".
            assertNotEquals("position 1 must not be a stale E1",
                    "E1", received.get(1).getPayload().get("trace.id"));
        }
    }

    /**
     * Listener that throws {@code RuntimeException} from {@code onEvents}
     * must not prevent other listeners on the same domain from receiving
     * the event. Pins per-listener isolation in
     * {@code DispatchAuditEventsHook.dispatchOne} (commit-attached).
     */
    @Test
    public void listenerRuntimeExceptionDoesNotPreventOtherListeners() throws Exception {
        List<AuditEvent> bReceived = new CopyOnWriteArrayList<>();
        AuditEventListener throwingA = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public int getRank() { return 10; } // dispatched first
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                throw new RuntimeException("synthetic-A");
            }
        };
        AuditEventListener okB = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public int getRank() { return 5; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                bReceived.addAll(events);
            }
        };
        Registration regA = whiteboard.register(AuditEventListener.class, throwingA, Map.of());
        Registration regB = whiteboard.register(AuditEventListener.class, okB, Map.of());
        try (ContentSession session = login()) {
            Root root = session.getLatestRoot();
            AuditEvents.record(
                    root, eventFor(DOMAIN, "x", Map.of()));
            root.getTree("/").setProperty("scratch", "v");
            root.commit();

            assertEquals("listener-B must receive despite listener-A throwing",
                    1, bReceived.size());
        } finally {
            regA.unregister();
            regB.unregister();
        }
    }

    /**
     * Listener that throws {@code NoClassDefFoundError} (an
     * {@link Error}, not an {@link Exception}) from {@code onEvents}
     * must not prevent other listeners from receiving the event. Pins
     * the catch-{@code Throwable} contract documented in
     * {@code audit-spi/01-architecture.md §6}.
     */
    @Test
    public void listenerNoClassDefFoundErrorIsIsolated() throws Exception {
        List<AuditEvent> bReceived = new CopyOnWriteArrayList<>();
        AuditEventListener throwingA = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public int getRank() { return 10; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                throw new NoClassDefFoundError("synthetic-A");
            }
        };
        AuditEventListener okB = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public int getRank() { return 5; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                bReceived.addAll(events);
            }
        };
        Registration regA = whiteboard.register(AuditEventListener.class, throwingA, Map.of());
        Registration regB = whiteboard.register(AuditEventListener.class, okB, Map.of());
        try (ContentSession session = login()) {
            Root root = session.getLatestRoot();
            AuditEvents.record(
                    root, eventFor(DOMAIN, "x", Map.of()));
            root.getTree("/").setProperty("scratch", "v");
            root.commit();

            assertEquals("listener-B must receive despite listener-A throwing NoClassDefFoundError",
                    1, bReceived.size());
        } finally {
            regA.unregister();
            regB.unregister();
        }
    }

    /**
     * Fire-and-forget variant of the runtime-exception isolation test.
     * Pins the same property in {@code BufferSink.dispatch}.
     */
    @Test
    public void fireAndForgetListenerRuntimeExceptionDoesNotPreventOthers() {
        List<AuditEvent> bReceived = new CopyOnWriteArrayList<>();
        AuditEventListener throwingA = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public int getRank() { return 10; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                throw new RuntimeException("synthetic-A");
            }
        };
        AuditEventListener okB = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public int getRank() { return 5; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                bReceived.addAll(events);
            }
        };
        Registration regA = whiteboard.register(AuditEventListener.class, throwingA, Map.of());
        Registration regB = whiteboard.register(AuditEventListener.class, okB, Map.of());
        try {
            emitter.emit(eventFor(DOMAIN, "x", Map.of()));
            assertEquals("fire-and-forget: listener-B must receive despite A's RuntimeException",
                    1, bReceived.size());
        } finally {
            regA.unregister();
            regB.unregister();
        }
    }

    /**
     * Fire-and-forget variant of the Error isolation test.
     */
    @Test
    public void fireAndForgetListenerNoClassDefFoundErrorIsIsolated() {
        List<AuditEvent> bReceived = new CopyOnWriteArrayList<>();
        AuditEventListener throwingA = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public int getRank() { return 10; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                throw new NoClassDefFoundError("synthetic-A");
            }
        };
        AuditEventListener okB = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN; }
            @Override public int getRank() { return 5; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                bReceived.addAll(events);
            }
        };
        Registration regA = whiteboard.register(AuditEventListener.class, throwingA, Map.of());
        Registration regB = whiteboard.register(AuditEventListener.class, okB, Map.of());
        try {
            emitter.emit(eventFor(DOMAIN, "x", Map.of()));
            assertEquals("fire-and-forget: listener-B must receive despite A's NoClassDefFoundError",
                    1, bReceived.size());
        } finally {
            regA.unregister();
            regB.unregister();
        }
    }

    //--------------------------------------------------< validator support >---

    /**
     * {@link ValidatorProvider} that injects a {@link Validator} which
     * fails the commit when it observes a specific marker property added
     * to the root. Used by {@link #commitFailureDiscardsStagedEvents()}
     * to force a deterministic commit failure.
     */
    private static final class ThrowingValidatorProvider extends ValidatorProvider {

        private final String triggerPropertyName;

        ThrowingValidatorProvider(@NotNull String triggerPropertyName) {
            this.triggerPropertyName = triggerPropertyName;
        }

        @NotNull
        @Override
        public Validator getRootValidator(NodeState before, NodeState after,
                                          CommitInfo info) {
            return new ThrowingValidator(triggerPropertyName);
        }
    }

    private static final class ThrowingValidator extends DefaultValidator {

        private final String triggerPropertyName;

        ThrowingValidator(@NotNull String triggerPropertyName) {
            this.triggerPropertyName = triggerPropertyName;
        }

        @Override
        public void propertyAdded(PropertyState after) throws CommitFailedException {
            if (triggerPropertyName.equals(after.getName())) {
                throw new CommitFailedException(CommitFailedException.CONSTRAINT, 1,
                        "Injected validator failure: " + triggerPropertyName);
            }
        }
    }
}
