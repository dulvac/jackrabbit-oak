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

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.commit.CommitContext;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.PostValidationHook;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PostValidationHook} that reads the snapshot parked by
 * {@link SnapshotAuditBufferHook}, decorates each event's payload with
 * commit metadata, groups events by domain, and dispatches them to all
 * matching {@link AuditEventListener}s via {@link AuditEventListener#onEvents}.
 * <p>
 * Listener invocations are wrapped in a try/catch: failures are logged
 * at {@code WARN} level and swallowed.
 */
final class DispatchAuditEventsHook implements PostValidationHook {

    private static final Logger log = LoggerFactory.getLogger(DispatchAuditEventsHook.class);

    private final Feature featureToggle;
    private final AuditBuffer buffer;
    private final WhiteboardAuditEventListenerRegistry registry;

    DispatchAuditEventsHook(@NotNull Feature featureToggle,
                            @NotNull AuditBuffer buffer,
                            @NotNull WhiteboardAuditEventListenerRegistry registry) {
        this.featureToggle = featureToggle;
        this.buffer = buffer;
        this.registry = registry;
    }

    @NotNull
    @Override
    public NodeState processCommit(NodeState before, NodeState after, CommitInfo info)
            throws CommitFailedException {
        if (!featureToggle.isEnabled()) {
            return after;
        }
        CommitContext ctx = (CommitContext) info.getInfo().get(CommitContext.NAME);
        if (ctx == null) {
            // No CommitContext on this commit (non-MutableRoot path, e.g. an internal
            // NodeStore.merge skipping ResetCommitAttributeHook). Snapshot couldn't
            // stash, so there's nothing to dispatch — but the buffer may still hold
            // events captured for this session. Drain to prevent ThreadLocal
            // accumulation across commits on the same session.
            buffer.drain(info.getSessionId());
            return after;
        }
        Object stashed = ctx.get(SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY);
        if (!(stashed instanceof List)) {
            return after;
        }
        // Safe because COMMIT_CONTEXT_KEY ("oak.audit.events") is
        // package-private to this module and only SnapshotAuditBufferHook
        // writes there — it always writes a List<AuditEvent>. Do not widen
        // the key's visibility without redesigning this cast.
        @SuppressWarnings("unchecked")
        List<AuditEvent> events = (List<AuditEvent>) stashed;
        try {
            if (events.isEmpty()) {
                return after;
            }
            List<AuditEventListener> listeners = registry.getListeners();
            if (listeners.isEmpty()) {
                return after;
            }
            List<AuditEvent> decorated = decorate(events, info);
            Map<String, List<AuditEvent>> byDomain = groupByDomain(decorated);
            for (AuditEventListener listener : listeners) {
                List<AuditEvent> forListener = byDomain.get(listener.getDomain());
                if (forListener == null || forListener.isEmpty()) {
                    continue;
                }
                dispatchOne(listener, forListener);
            }
            return after;
        } finally {
            buffer.drain(info.getSessionId());
            ctx.remove(SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY);
        }
    }

    private static @NotNull List<AuditEvent> decorate(@NotNull List<AuditEvent> events,
                                                      @NotNull CommitInfo info) {
        return CommitMetadataDecorator.decorate(events, info);
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
            // throws e.g. LinkageError must not fail the commit for unrelated work.
            // JVM-level pathology (OutOfMemoryError) is caught here too but
            // re-triggers on the next allocation and surfaces through normal channels.
            // Do not narrow this catch to RuntimeException without re-reading the
            // design discussion. See: audit-spi/01-architecture.md §6.
            log.warn("AuditEventListener {} threw {} for {} event(s) in domain '{}'; isolating from other listeners.",
                    listener.getClass().getName(), t.getClass().getSimpleName(),
                    events.size(), listener.getDomain(), t);
        }
    }
}
