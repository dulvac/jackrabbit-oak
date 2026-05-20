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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.commons.junit.LogCustomizer;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.mockito.Mockito;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.apache.jackrabbit.oak.spi.toggle.FeatureToggle;
import org.apache.jackrabbit.oak.spi.whiteboard.DefaultWhiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.Tracker;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link AuditDrainObserver}, the v3 {@code Observer} that
 * drains the per-thread {@link AuditBuffer} on commit success and dispatches
 * the captured events to registered {@link AuditEventListener}s.
 * <p>
 * The tests construct {@code AuditDrainObserver} directly with real
 * {@link Feature}, {@link AuditBuffer}, and
 * {@link WhiteboardAuditEventListenerRegistry} collaborators — no
 * {@code Oak} builder, no {@code Observable.addObserver(...)} chain. The
 * observer's contract is pure: given a {@link NodeState} and a
 * {@link CommitInfo}, drain the buffer and dispatch. Direct invocation of
 * {@code contentChanged(root, info)} exercises every branch reachable from
 * the production wiring while keeping the test fixture minimal.
 * <p>
 * <strong>The OUTER {@code catch (Throwable)} barrier in
 * {@link AuditDrainObserver#contentChanged} is unreachable by construction
 * in production OSGi</strong> — the pipeline's @{@code Activate} sequence
 * guarantees {@code featureToggle}, {@code buffer}, and {@code registry}
 * are all non-null, and {@code BufferSink} ensures only well-formed
 * {@link AuditEvent} instances enter the buffer. The barrier exists as
 * defense in depth (a misbehaving event from a buggy producer must not
 * masquerade as a commit failure to the merge thread —
 * see {@code design-v3-observer-drain.md} §9 invariant I8). To exercise
 * that barrier in a test, {@link #poisonedEventGetDomainThrowsCaughtByOuterBarrier}
 * stages an event whose {@code getDomain()} throws. If this WARN ever
 * fires in CI on a non-test path, treat it as a bug.
 */
public class AuditDrainObserverTest {

    private static final String SESSION_ID = "test-session-1";
    private static final String USER_ID = "alice";
    private static final NodeState ROOT = EmptyNodeState.EMPTY_NODE;
    private static final String DOMAIN_A = "test.domain.a";
    private static final String DOMAIN_B = "test.domain.b";

    private DefaultWhiteboard whiteboard;
    private Feature featureToggle;
    private AuditBuffer buffer;
    private WhiteboardAuditEventListenerRegistry registry;
    private AuditDrainObserver observer;

    @Before
    public void setUp() {
        whiteboard = new DefaultWhiteboard();
        featureToggle = Feature.newFeature(AuditConfigurationImpl.FEATURE_TOGGLE_NAME, whiteboard);
        buffer = new AuditBuffer();
        registry = new WhiteboardAuditEventListenerRegistry();
        registry.start(whiteboard);
        observer = new AuditDrainObserver(featureToggle, buffer, registry);
    }

    @After
    public void tearDown() {
        if (featureToggle != null) {
            featureToggle.close();
        }
        if (registry != null) {
            registry.stop();
        }
        if (buffer != null) {
            buffer.clearAll();
        }
    }

    //----------------------------------------------------< short-circuits >---

    /**
     * {@code isExternal()} short-circuit: external commits (cluster sync,
     * the synthetic {@code addObserver}-time bootstrap with
     * {@link CommitInfo#EMPTY_EXTERNAL}, segment external head movement)
     * never carry locally-captured events. The observer returns immediately;
     * the buffer is NOT drained — events stay for a future local commit.
     */
    @Test
    public void externalCommitShortCircuitsBeforeDrain() {
        setToggle(true);
        CapturingListener listener = registerCapturingListener(DOMAIN_A);
        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_A, "type-1"));

        observer.contentChanged(ROOT, externalCommit());

        assertTrue("external commit must not invoke listeners",
                listener.received.isEmpty());
        assertNotNull("buffer must retain events through an external commit",
                buffer.peek(SESSION_ID));
    }

    /**
     * Toggle-off short-circuit: with the feature toggle disabled the
     * observer returns before draining. Pins the second early-return
     * branch in {@code doContentChanged}.
     */
    @Test
    public void toggleDisabledShortCircuitsBeforeDrain() {
        // Toggle defaults to disabled — explicit setToggle(false) for clarity.
        setToggle(false);
        CapturingListener listener = registerCapturingListener(DOMAIN_A);
        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_A, "type-1"));

        observer.contentChanged(ROOT, localCommit());

        assertTrue("toggle-off must not invoke listeners",
                listener.received.isEmpty());
        assertNotNull("buffer must retain events when pipeline is toggled off",
                buffer.peek(SESSION_ID));
    }

    /**
     * Empty buffer short-circuit: when {@code buffer.drain(sessionId)}
     * returns {@code null} (no events for this session on this thread)
     * the observer returns without invoking listeners.
     */
    @Test
    public void emptyBufferIsNoOp() {
        setToggle(true);
        CapturingListener listener = registerCapturingListener(DOMAIN_A);
        // No buffer.record(...) — drain returns null.

        observer.contentChanged(ROOT, localCommit());

        assertTrue("empty buffer must not invoke listeners",
                listener.received.isEmpty());
    }

    /**
     * No-listeners short-circuit AFTER drain: when no
     * {@link AuditEventListener} is registered, the observer drains the
     * buffer (so the events are no longer staged) but does not invoke
     * any listener. Pins the {@code listeners.isEmpty()} branch.
     */
    @Test
    public void noListenersShortCircuitsAfterDrain() {
        setToggle(true);
        // No listener registered.
        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_A, "type-1"));

        observer.contentChanged(ROOT, localCommit());

        // The drain still happens — events are removed from the buffer to
        // prevent leaking into a subsequent commit. Otherwise a buffer-full
        // session could re-dispatch the same events the next time a listener
        // got registered.
        assertNull("drain must run even when no listeners are registered",
                buffer.peek(SESSION_ID));
    }

    //----------------------------------------------------------< grouping >---

    /**
     * {@code groupByDomain} correctness: events on multiple domains are
     * partitioned per listener-domain. Each listener receives ONLY events
     * for its own domain, in capture order.
     */
    @Test
    public void groupByDomainSendsEachListenerOnlyItsDomain() {
        setToggle(true);
        CapturingListener listenerA = registerCapturingListener(DOMAIN_A);
        CapturingListener listenerB = registerCapturingListener(DOMAIN_B);

        // Interleave A and B events to verify capture-order preservation.
        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_A, "a-1"));
        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_B, "b-1"));
        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_A, "a-2"));

        observer.contentChanged(ROOT, localCommit());

        assertEquals("listener-A must receive 2 events in capture order",
                2, listenerA.received.size());
        assertEquals("a-1", listenerA.received.get(0).getType());
        assertEquals("a-2", listenerA.received.get(1).getType());

        assertEquals("listener-B must receive 1 event",
                1, listenerB.received.size());
        assertEquals("b-1", listenerB.received.get(0).getType());
    }

    /**
     * Listener invocation order is determined by
     * {@link AuditEventListener#getRank()} (higher first). With two
     * listeners on the same domain, the higher-rank listener is dispatched
     * before the lower-rank one — pins the {@code BY_RANK_DESC} stable
     * sort in {@link WhiteboardAuditEventListenerRegistry#getListeners}.
     */
    @Test
    public void multipleListenersPerDomainDispatchedInRankOrder() {
        setToggle(true);
        List<String> timeline = new ArrayList<>();
        AuditEventListener high = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN_A; }
            @Override public int getRank() { return 100; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                timeline.add("high");
            }
        };
        AuditEventListener low = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN_A; }
            @Override public int getRank() { return 1; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                timeline.add("low");
            }
        };
        // Register in REVERSE order on purpose — to verify the sort, not insertion order.
        whiteboard.register(AuditEventListener.class, low, Map.of());
        whiteboard.register(AuditEventListener.class, high, Map.of());

        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_A, "type-1"));
        observer.contentChanged(ROOT, localCommit());

        assertEquals("both listeners must receive", List.of("high", "low"), timeline);
    }

    //------------------------------< per-listener (inner) Throwable isolation >---

    /**
     * Per-listener {@code dispatchOne} {@link RuntimeException} isolation:
     * a listener whose {@code onEvents} throws does not stop other
     * listeners on the same domain from receiving the event. Preserves
     * the v2 invariant byte-for-byte.
     */
    @Test
    public void listenerRuntimeExceptionDoesNotPreventOtherListeners() {
        verifyListenerErrorIsolated(new RuntimeException("synthetic"));
    }

    /**
     * Per-listener {@link LinkageError} isolation. A misconfigured consumer
     * bundle that emits a {@code LinkageError} from its listener must not
     * cascade.
     */
    @Test
    public void listenerLinkageErrorDoesNotPreventOtherListeners() {
        verifyListenerErrorIsolated(new LinkageError("synthetic"));
    }

    /**
     * Per-listener {@link NoClassDefFoundError} isolation. A consumer-bundle
     * classpath misconfiguration must not cascade either.
     */
    @Test
    public void listenerNoClassDefFoundErrorDoesNotPreventOtherListeners() {
        verifyListenerErrorIsolated(new NoClassDefFoundError("synthetic"));
    }

    /**
     * Shared shape for the per-listener isolation tests. Registers one
     * throwing listener (higher rank → dispatched first) and one capturing
     * listener on the same domain; asserts the capturing listener still
     * receives the event despite the throwing listener's failure.
     */
    private void verifyListenerErrorIsolated(@NotNull Throwable thrown) {
        setToggle(true);
        AuditEventListener throwingFirst = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return DOMAIN_A; }
            @Override public int getRank() { return 100; } // dispatched first
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                rethrow(thrown);
            }
        };
        whiteboard.register(AuditEventListener.class, throwingFirst, Map.of());
        CapturingListener okSecond = registerCapturingListener(DOMAIN_A);

        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_A, "type-1"));
        observer.contentChanged(ROOT, localCommit());

        assertEquals("second listener must receive despite first listener throwing "
                        + thrown.getClass().getSimpleName(),
                1, okSecond.received.size());
    }

    //------------------------< outer (whole-method) Throwable barrier >---

    /**
     * OUTER Throwable barrier: a poisoned {@link AuditEvent} whose
     * {@code getDomain()} throws would, without the barrier, propagate
     * through {@link AuditDrainObserver#contentChanged} into Oak's commit
     * dispatch — surfacing as a fake commit failure to the merge caller
     * despite a successful durable commit (see design §9 invariant I8).
     * <p>
     * The outer {@code try { doContentChanged(info); } catch (Throwable t)}
     * in {@code contentChanged} catches this, logs WARN with the session
     * id for diagnostics, and returns normally. The merge thread sees no
     * exception.
     */
    @Test
    public void poisonedEventGetDomainThrowsCaughtByOuterBarrier() {
        setToggle(true);
        CapturingListener listener = registerCapturingListener(DOMAIN_A);

        // Poison: an AuditEvent whose getDomain() throws. The Decorator
        // wraps it lazily so the throw lands in groupByDomain — the first
        // call site that invokes event.getDomain() to use it as a HashMap key.
        // The exception propagates out of doContentChanged into the outer
        // catch in contentChanged.
        AuditEvent poison = new AuditEvent() {
            @Override public @NotNull String getDomain() {
                throw new RuntimeException("synthetic-poisoned-event");
            }
            @Override public @NotNull String getType() { return "type-poison"; }
            @Override public long getTimestamp() { return 0L; }
        };
        buffer.record(SESSION_ID, poison);

        LogCustomizer log = LogCustomizer.forLogger(AuditDrainObserver.class)
                .enable(Level.WARN).create();
        log.starting();
        try {
            // The call MUST return normally — no exception to the merge thread.
            observer.contentChanged(ROOT, localCommit());

            // Outer barrier logged exactly one WARN with the session id.
            List<String> logs = log.getLogs();
            assertEquals("outer Throwable barrier must log exactly one WARN line",
                    1, logs.size());
            assertTrue("WARN must include the session id for diagnostics; was: " + logs.get(0),
                    logs.get(0).contains(SESSION_ID));

            // Listener never received the poisoned event — groupByDomain threw
            // before any dispatch could happen.
            assertTrue("listener must not have received the poisoned event",
                    listener.received.isEmpty());
        } finally {
            log.finished();
        }
    }

    /**
     * Stronger isolation variant of {@link #externalCommitShortCircuitsBeforeDrain}:
     * uses a mock {@link AuditBuffer} and asserts via Mockito
     * {@code verifyNoInteractions(...)} that the buffer is never touched on
     * an external commit. The behavioral variant proves the buffer remains
     * un-drained via state; this one proves the buffer is not even
     * <em>consulted</em> — the short-circuit fires before any buffer method
     * call. Belt-and-braces.
     */
    @Test
    public void externalCommitShortCircuitsWithoutTouchingBuffer() {
        setToggle(true);
        AuditBuffer mockBuffer = Mockito.mock(AuditBuffer.class);
        AuditDrainObserver observerWithMockBuffer =
                new AuditDrainObserver(featureToggle, mockBuffer, registry);

        observerWithMockBuffer.contentChanged(ROOT, externalCommit());

        Mockito.verifyNoInteractions(mockBuffer);
    }

    /**
     * Additional probe of the OUTER Throwable barrier — complements
     * {@link #poisonedEventGetDomainThrowsCaughtByOuterBarrier} by reaching
     * the barrier through a different code path: the {@code buffer.drain}
     * call itself throws (e.g., would model a regression where the buffer's
     * ThreadLocal state machine was corrupted). The poison-event variant
     * exercises the {@code groupByDomain} → {@code event.getDomain()} throw
     * site; this one exercises the {@code buffer.drain(sessionId)} throw
     * site. Both must be caught and logged WARN without propagation.
     */
    @Test
    public void bufferDrainThrowsCaughtByOuterBarrier() {
        setToggle(true);
        registerCapturingListener(DOMAIN_A);

        AuditBuffer throwingBuffer = Mockito.mock(AuditBuffer.class);
        Mockito.doThrow(new RuntimeException("synthetic-drain-failure"))
                .when(throwingBuffer).drain(SESSION_ID);
        AuditDrainObserver observerWithThrowingBuffer =
                new AuditDrainObserver(featureToggle, throwingBuffer, registry);

        LogCustomizer log = LogCustomizer.forLogger(AuditDrainObserver.class)
                .enable(Level.WARN).create();
        log.starting();
        try {
            // Must return normally — outer barrier swallows the drain failure.
            observerWithThrowingBuffer.contentChanged(ROOT, localCommit());

            List<String> logs = log.getLogs();
            assertEquals("outer Throwable barrier must log exactly one WARN line",
                    1, logs.size());
            assertTrue("WARN must include the session id for diagnostics; was: " + logs.get(0),
                    logs.get(0).contains(SESSION_ID));
        } finally {
            log.finished();
        }
    }

    //-------------------------------< happy path: decorator + dispatch >---

    /**
     * Happy path: with the toggle ON, a registered listener for the event's
     * domain, and a non-external commit, the observer drains the buffer,
     * decorates the events with commit metadata, and dispatches to the
     * listener. Pins {@link CommitMetadataDecorator#decorate} runs and
     * the listener receives the decorated payload.
     */
    @Test
    public void successfulCommitDrainsAndDecoratesAndDispatches() {
        setToggle(true);
        CapturingListener listener = registerCapturingListener(DOMAIN_A);

        buffer.record(SESSION_ID, AuditEvent.of(DOMAIN_A, "type-1", Map.of("k", "v")));
        observer.contentChanged(ROOT, localCommit());

        assertEquals("listener must receive exactly one event", 1, listener.received.size());
        AuditEvent received = listener.received.get(0);
        assertEquals("event type must round-trip", "type-1", received.getType());

        Map<String, Object> payload = received.getPayload();
        // Original payload is preserved.
        assertEquals("v", payload.get("k"));
        // Decorator adds commit metadata.
        assertEquals(SESSION_ID, payload.get(CommitMetadataDecorator.KEY_SESSION_ID));
        assertEquals(USER_ID, payload.get(CommitMetadataDecorator.KEY_USER_ID));
        assertTrue("commit.timestamp must be present",
                payload.containsKey(CommitMetadataDecorator.KEY_TIMESTAMP));

        // Buffer drained — events no longer staged for this session.
        assertNull("buffer must be drained on successful dispatch",
                buffer.peek(SESSION_ID));
    }

    //----------------------------------------------------------< fixtures >---

    /**
     * Local commit with the test session and user id. {@code external=false}
     * by construction — the four-arg ctor used here is the only way to set
     * it explicitly, and we deliberately use the two-arg one for the
     * common-case test commits.
     */
    private static CommitInfo localCommit() {
        return new CommitInfo(SESSION_ID, USER_ID);
    }

    /**
     * External commit — used by {@link #externalCommitShortCircuitsBeforeDrain}
     * to drive the {@code isExternal()} short-circuit. The session id is the
     * same as local commits so that the buffer would otherwise be drained
     * if the short-circuit failed to fire.
     */
    private static CommitInfo externalCommit() {
        return new CommitInfo(SESSION_ID, USER_ID, Map.of(), true);
    }

    /**
     * Flips the {@code FT_AUDIT} feature toggle. Locates the
     * {@link FeatureToggle} service that {@link Feature#newFeature} registered
     * on the test whiteboard.
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

    private CapturingListener registerCapturingListener(@NotNull String domain) {
        CapturingListener l = new CapturingListener(domain);
        whiteboard.register(AuditEventListener.class, l, Map.of());
        return l;
    }

    /**
     * Rethrows {@code t} unchecked — the {@link AuditEventListener#onEvents}
     * signature is unchecked, so we need to coerce the caller-supplied
     * Throwable through Java's checked-exception machinery. Uses the
     * unsafe-generic-cast trick.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(@NotNull Throwable t) throws T {
        throw (T) t;
    }

    private static final class CapturingListener implements AuditEventListener {

        private final String domain;
        final List<AuditEvent> received = new ArrayList<>();

        CapturingListener(@NotNull String domain) {
            this.domain = domain;
        }

        @Override
        public @NotNull String getDomain() {
            return domain;
        }

        @Override
        public void onEvents(@NotNull List<AuditEvent> events) {
            received.addAll(events);
        }
    }

}
