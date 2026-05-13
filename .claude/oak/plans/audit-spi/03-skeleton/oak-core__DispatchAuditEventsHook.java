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
import org.apache.jackrabbit.oak.spi.commit.CommitContext;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.PostValidationHook;
import org.apache.jackrabbit.oak.spi.security.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.security.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PostValidationHook} that reads the snapshot parked by
 * {@link SnapshotAuditBufferHook}, groups events by
 * {@link AuditEvent#getDomain()} and dispatches them to all
 * {@link AuditEventListener}s whose
 * {@link AuditEventListener#getDomain()} matches.
 * <p>
 * Listener invocations are wrapped in a try/catch: failures are logged
 * at {@code WARN} level and swallowed so that they never propagate back
 * to the committing session. Listener invocation order follows
 * {@link WhiteboardAuditEventListenerRegistry#getListeners()} —
 * {@link AuditEventListener#getRank()} descending, ties preserve
 * underlying {@code Whiteboard} order.
 * <p>
 * On entry the hook is the sole authority that clears the audit
 * buffer for the current session (in a {@code finally} block, regardless
 * of whether any listener was invoked). On commit failure, the buffer
 * is instead cleared by {@code AuditBufferLifecycle.onCommitFailed} from
 * {@code MutableRoot}.
 * <p>
 * The hook runs on the same thread that called {@code Root.commit()},
 * after the merge has succeeded and after all validators have run.
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
            return after;
        }
        Object stashed = ctx.get(SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY);
        if (!(stashed instanceof List)) {
            return after;
        }
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
            Map<String, List<AuditEvent>> byDomain = groupByDomain(events);
            for (AuditEventListener listener : listeners) {
                List<AuditEvent> forListener = byDomain.get(listener.getDomain());
                if (forListener == null || forListener.isEmpty()) {
                    continue;
                }
                dispatchOne(listener, after, info, forListener);
            }
            return after;
        } finally {
            // Commit succeeded — clear both stores. CommitContext is
            // already reset by ResetCommitAttributeHook on the next
            // merge attempt, but explicit removal keeps the API tidy.
            buffer.drain(info.getSessionId());
            ctx.remove(SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY);
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
                                    @NotNull NodeState after,
                                    @NotNull CommitInfo info,
                                    @NotNull List<AuditEvent> events) {
        try {
            listener.onCommit(after, info, events);
        } catch (RuntimeException re) {
            log.warn("AuditEventListener {} failed for {} event(s) in domain '{}'; swallowing.",
                    listener.getClass().getName(), events.size(), listener.getDomain(), re);
        }
    }
}
