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

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.commit.CommitContext;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.jetbrains.annotations.NotNull;

/**
 * Regular commit hook that <strong>copies the reference to</strong> the
 * {@link AuditBuffer}'s staged events into the {@link CommitContext}
 * under {@link #COMMIT_CONTEXT_KEY}. The {@code ThreadLocal} buffer is
 * <strong>not</strong> cleared here — that responsibility belongs to
 * {@link DispatchAuditEventsHook} on commit success, and to
 * {@code AuditBufferLifecycle.onCommitFailed} / {@code .onRefresh}
 * on failure.
 * <p>
 * Rationale: if a hook later in the chain (e.g. another configuration's
 * validator) throws between the snapshot and the dispatch hooks, the
 * NodeStore may retry the same merge. On retry,
 * {@link org.apache.jackrabbit.oak.spi.commit.ResetCommitAttributeHook}
 * clears {@code CommitContext} as the first step. If snapshot were
 * destructive, the events would be gone on the retry as well. By
 * leaving them in the {@code ThreadLocal}, retry re-parks them
 * transparently.
 * <p>
 * The same session is single-threaded by contract, so no other
 * capture-site write can run between Snapshot and Dispatch on the
 * same thread; the parked reference is therefore stable.
 * <p>
 * Short-circuits with zero work when the feature toggle is off.
 */
final class SnapshotAuditBufferHook implements CommitHook {

    /**
     * Key used to stash the events in the {@link CommitContext}.
     * Private to the audit module — consumers read events via
     * {@link org.apache.jackrabbit.oak.spi.audit.AuditEventListener#onEvents}.
     */
    static final String COMMIT_CONTEXT_KEY = "oak.audit.events";

    private final Feature featureToggle;
    private final AuditBuffer buffer;

    SnapshotAuditBufferHook(@NotNull Feature featureToggle, @NotNull AuditBuffer buffer) {
        this.featureToggle = featureToggle;
        this.buffer = buffer;
    }

    @NotNull
    @Override
    public NodeState processCommit(NodeState before, NodeState after, CommitInfo info)
            throws CommitFailedException {
        if (!featureToggle.isEnabled()) {
            return after;
        }
        List<AuditEvent> staged = buffer.peek(info.getSessionId());
        if (staged == null || staged.isEmpty()) {
            return after;
        }
        CommitContext ctx = (CommitContext) info.getInfo().get(CommitContext.NAME);
        if (ctx != null) {
            ctx.set(COMMIT_CONTEXT_KEY, staged);
        }
        return after;
    }
}
