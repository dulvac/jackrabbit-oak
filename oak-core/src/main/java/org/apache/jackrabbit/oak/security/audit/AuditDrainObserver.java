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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.Observer;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Observer} that drains the {@link AuditBuffer} on commit success
 * and dispatches captured events to all registered {@link AuditEventListener}s.
 * <p>
 * The observer fires synchronously on the same thread as the surrounding
 * {@code MutableRoot.commit()} — by the contract of
 * {@link org.apache.jackrabbit.oak.spi.commit.Observable#addObserver}
 * the call is made from the commit dispatch path, before
 * {@code NodeStore.merge(...)} returns. This preserves the
 * {@code ThreadLocal} semantics that the {@link AuditBuffer} relies on.
 * <p>
 * <strong>External changes are ignored.</strong> When
 * {@link CommitInfo#isExternal()} returns {@code true} (cluster sync from
 * a peer node, or the initial replay invocation at {@code addObserver()}
 * time with {@link CommitInfo#EMPTY_EXTERNAL}), the observer returns
 * immediately. External commits did not originate any local
 * {@code AuditEvents.record(...)} calls, so there is nothing in the
 * per-session buffer to drain. Explicit short-circuit; cleaner than
 * relying on the buffer to return empty.
 * <p>
 * <strong>Two-layer exception isolation:</strong>
 * <ul>
 *   <li><strong>Outer barrier</strong> wraps the ENTIRE method body. The
 *   Observer chain has no per-observer isolation
 *   ({@code CompositeObserver.java:46-53} — bare {@code for} loop with no
 *   try/catch). Any throw out of this method propagates through
 *   {@code DocumentNodeStore.java:1140-1144} (in a {@code finally} after
 *   {@code setRoot}) or {@code LockBasedScheduler.java:303} (after
 *   {@code head.set}), surfacing as a {@code RuntimeException} to the
 *   merge caller despite a successful durable commit. Worse, on
 *   DocumentNodeStore the inner catch at {@code DocumentNodeStore:1130-1139}
 *   suppresses in-memory commit-apply failures, so an audit-induced throw
 *   would mask a different kind of failure entirely. The outer Throwable
 *   barrier guarantees audit never masquerades as a commit failure.</li>
 *   <li><strong>Inner barrier</strong> per listener (in {@code dispatchOne}).
 *   A misconfigured consumer bundle whose listener throws
 *   {@link LinkageError}, {@link OutOfMemoryError}, or other {@link Throwable}
 *   subtypes does not stop other listeners.</li>
 * </ul>
 *
 * <p><strong>DO NOT wrap this Observer in {@code BackgroundObserver}.</strong>
 * The async wrapper drops the {@code CommitInfo.sessionId} on queue overflow
 * (it replaces the latest queued entry with
 * {@code new ContentChange(root, CommitInfo.EMPTY_EXTERNAL)} —
 * {@code BackgroundObserver.java:283-286}). The audit drain keys exclusively
 * on {@code info.getSessionId()} to look up the per-thread buffer; losing
 * session id on overflow → silent audit-event loss for high-rate writers.
 * Plus: the {@link AuditBuffer} is a {@code ThreadLocal} populated on the
 * commit thread, so it can ONLY be drained on that same thread. Synchronous
 * dispatch is mandatory.
 *
 * <p><strong>TODO (OAK-NNNNN): toggle-flicker leak.</strong> The
 * {@code featureToggle.isEnabled()} short-circuit in {@link #doContentChanged}
 * returns without draining the per-session buffer when the toggle is off
 * at observer-fire time. If the toggle is ON at capture, OFF when a later
 * successful commit on the same session fires the observer, then ON again
 * for a subsequent commit, the stale event survives the toggle-OFF drain
 * window and leaks through the next drain with the later commit's
 * {@code commit.*} metadata. Fix is to always call
 * {@code buffer.drain(sessionId)} (the destructive cleanup) and gate ONLY
 * the listener {@code dispatchOne} loop. Out of scope for the audit-spi
 * PR; tracking ticket to be filed post-merge.
 */
final class AuditDrainObserver implements Observer {

    private static final Logger log = LoggerFactory.getLogger(AuditDrainObserver.class);

    private final Feature featureToggle;
    private final AuditBuffer buffer;
    private final WhiteboardAuditEventListenerRegistry registry;

    AuditDrainObserver(@NotNull Feature featureToggle,
                       @NotNull AuditBuffer buffer,
                       @NotNull WhiteboardAuditEventListenerRegistry registry) {
        this.featureToggle = featureToggle;
        this.buffer = buffer;
        this.registry = registry;
    }

    @Override
    public void contentChanged(@NotNull NodeState root, @NotNull CommitInfo info) {
        // OUTER Throwable barrier. CompositeObserver
        // (oak-store-spi/.../spi/commit/CompositeObserver.java:46-53) does NOT
        // isolate per-observer exceptions: a Throwable from this method
        // cascades through the observer chain and would break peer observers
        // such as JCR's observation dispatcher. We swallow defensively so the
        // audit pipeline can never destabilise unrelated observer work. The
        // per-listener barrier inside dispatchOne catches listener-induced
        // failures; this outer catch protects against drain/decorator bugs.
        // Do NOT narrow this catch to RuntimeException — any Throwable
        // escaping here masquerades as a commit failure to the merge caller.
        try {
            doContentChanged(info);
        } catch (Throwable t) {
            log.warn("AuditDrainObserver: unexpected error during drain/dispatch (session {}); " +
                    "swallowing to preserve observer-chain isolation.",
                    info.getSessionId(), t);
        }
    }

    private void doContentChanged(@NotNull CommitInfo info) {
        // External commits never produce local audit events (capture sites
        // are local-only by construction). The bootstrap invocation at
        // addObserver-time with CommitInfo.EMPTY_EXTERNAL also lands here.
        if (info.isExternal()) {
            return;
        }
        if (!featureToggle.isEnabled()) {
            return;
        }
        String sessionId = info.getSessionId();
        // The buffer is per-thread; this drain runs on the same thread that
        // called Root.commit() (synchronous Observer contract via
        // ChangeDispatcher for local commits). The sessionId returned by
        // CommitInfo equals ContentSession.toString() — set at
        // MutableRoot.java:262 — so it matches the buffer's keying.
        List<AuditEvent> events = buffer.drain(sessionId);
        if (events == null || events.isEmpty()) {
            return;
        }
        List<AuditEventListener> listeners = registry.getListeners();
        if (listeners.isEmpty()) {
            return;
        }
        List<AuditEvent> decorated = CommitMetadataDecorator.decorate(events, info);
        Map<String, List<AuditEvent>> byDomain = groupByDomain(decorated);
        for (AuditEventListener listener : listeners) {
            List<AuditEvent> forListener = byDomain.get(listener.getDomain());
            if (forListener == null || forListener.isEmpty()) {
                continue;
            }
            dispatchOne(listener, forListener);
        }
    }

    private static @NotNull Map<String, List<AuditEvent>> groupByDomain(@NotNull List<AuditEvent> events) {
        Map<String, List<AuditEvent>> byDomain = new HashMap<>(4);
        for (AuditEvent event : events) {
            byDomain.computeIfAbsent(event.getDomain(), k -> new ArrayList<>(events.size())).add(event);
        }
        return byDomain;
    }

    private static void dispatchOne(@NotNull AuditEventListener listener,
                                    @NotNull List<AuditEvent> events) {
        try {
            listener.onEvents(events);
        } catch (Throwable t) {
            // Per-listener isolation: a misconfigured consumer bundle whose listener
            // throws e.g. LinkageError must not crash the commit-dispatch path for
            // unrelated work. JVM-level pathology (OutOfMemoryError) is caught here
            // too but re-triggers on the next allocation and surfaces through normal
            // channels. Do not narrow this catch to RuntimeException — listener
            // Throwables (any kind) must not escape into the dispatch loop.
            log.warn("AuditEventListener {} threw {} for {} event(s) in domain '{}'; isolating from other listeners.",
                    listener.getClass().getName(), t.getClass().getSimpleName(),
                    events.size(), listener.getDomain(), t);
        }
    }
}
