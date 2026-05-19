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
package org.apache.jackrabbit.oak.spi.security.audit;

import java.util.List;

import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ConsumerType;

/**
 * Receives a burst of audit events for a single committed change. Listeners
 * are registered on the Whiteboard and are domain-scoped: only events whose
 * {@link AuditEvent#getDomain()} matches {@link #getDomain()} are delivered.
 * <p>
 * Dispatch happens <strong>synchronously on the thread that called
 * {@link org.apache.jackrabbit.oak.api.Root#commit(java.util.Map) Root.commit()}</strong>
 * after the merge has succeeded. Listeners must therefore be non-blocking:
 * any expensive work (I/O, fan-out, persistence) belongs in an async wrapper
 * provided by the consumer (analogous to
 * {@link org.apache.jackrabbit.oak.spi.commit.BackgroundObserver}).
 * <p>
 * Exceptions thrown from {@link #onCommit} are caught, logged and swallowed
 * by the dispatcher; they never propagate back to the committing session.
 * <p>
 * Listener invocation order is determined by {@link #getRank()} (higher
 * value first). The dispatcher applies a stable sort, so listeners with
 * equal rank are invoked in underlying {@code Whiteboard} order.
 * <p>
 * Note: the {@code DefaultWhiteboard} used outside OSGi does NOT honor
 * the OSGi {@code service.ranking} property — relying on that property
 * alone would yield non-deterministic ordering across deployments.
 * Implementations therefore declare ordering explicitly via
 * {@link #getRank()}. OSGi implementations should additionally set
 * {@code service.ranking} to the same value so external tools that read
 * the OSGi registry agree with the dispatch order.
 */
@ConsumerType
public interface AuditEventListener {

    /**
     * Returns the domain this listener is interested in. Must be stable
     * across the listener's lifetime; the registry caches active domains
     * to short-circuit capture when no listener is present.
     *
     * @return non-null domain name (e.g. {@link SecurityAuditDomain#NAME}).
     */
    @NotNull
    String getDomain();

    /**
     * Returns the dispatch rank for this listener — higher value is
     * invoked first. The default implementation returns {@code 0}.
     * <p>
     * The dispatcher applies a stable sort, so listeners with equal rank
     * preserve the {@code Whiteboard}'s underlying order. See class-level
     * Javadoc for the OSGi {@code service.ranking} interaction.
     *
     * @return rank value.
     */
    default int getRank() {
        return 0;
    }

    /**
     * Invoked once per successful commit, with all events recorded during
     * that commit that match this listener's domain.
     *
     * @param after the committed {@code NodeState} as returned by the
     *              underlying {@code NodeStore.merge(...)} call.
     * @param commitInfo the {@link CommitInfo} associated with the commit;
     *                   {@link CommitInfo#getSessionId()} and
     *                   {@link CommitInfo#getUserId()} are guaranteed
     *                   non-null. For system commits {@code getUserId()}
     *                   returns {@link CommitInfo#OAK_UNKNOWN}.
     * @param events the non-empty list of events for this listener's
     *               domain, in capture order (earliest first). For each
     *               event, payload map values are never null; optional
     *               fields are absent from the map.
     */
    void onCommit(@NotNull NodeState after,
                  @NotNull CommitInfo commitInfo,
                  @NotNull List<AuditEvent> events);
}
