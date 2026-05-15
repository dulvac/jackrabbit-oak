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
package org.apache.jackrabbit.oak.spi.audit;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ConsumerType;

/**
 * Receives a burst of audit events for the listener's domain. One method
 * for both commit-attached events (drained after a successful
 * {@code Root.commit()}) and fire-and-forget events (dispatched
 * immediately via {@link AuditEventEmitter#emit(AuditEvent)}).
 * <p>
 * Implementations must be non-blocking. Invocation is synchronous on the
 * dispatching thread; expensive work (I/O, fan-out, persistence) belongs
 * in an async wrapper provided by the consumer.
 * <p>
 * Exceptions and Errors thrown from {@link #onEvents} are caught, logged
 * at {@code WARN}, and swallowed by the dispatcher; they never propagate
 * back to the dispatching thread. The dispatcher swallows {@link Throwable}
 * broadly to ensure that one misconfigured listener (e.g., a
 * {@link LinkageError} from a missing transitive dependency) cannot
 * prevent other listeners from receiving events or abort the surrounding
 * commit. See §6 of {@code 01-architecture.md} for the rationale.
 * <p>
 * Listener invocation order is determined by {@link #getRank()} (higher
 * value first). The dispatcher applies a stable sort, so listeners with
 * equal rank are invoked in {@code Whiteboard} order.
 *
 * <h3>Trust model</h3>
 * Events delivered through this method may originate from either:
 * <ul>
 *   <li>Oak-internal capture sites tied to a successful
 *       {@code Root.commit()}. Such events carry {@code commit.sessionId},
 *       {@code commit.userId}, and {@code commit.timestamp} entries in
 *       their payload. {@code commit.userId} is {@code "oak:unknown"} for
 *       system commits and listeners <strong>MUST NOT</strong> attempt to
 *       resolve it to a real user identity.</li>
 *   <li>Any bundle calling {@link AuditEventEmitter#emit(AuditEvent)}.
 *       The accuracy of such events is the emitting bundle's responsibility;
 *       Oak does not verify them. They do not carry the {@code commit.*}
 *       payload entries.</li>
 * </ul>
 * Consumers that need to distinguish between the two sources should
 * inspect the payload for the {@code commit.sessionId} key.
 */
@ConsumerType
public interface AuditEventListener {

    /**
     * Returns the domain this listener is interested in. Must be stable
     * across the listener's lifetime; the registry caches active domains
     * to short-circuit dispatch when no listener is present.
     *
     * @return non-null domain name.
     */
    @NotNull
    String getDomain();

    /**
     * Returns the dispatch rank for this listener — higher value is
     * invoked first. The default implementation returns {@code 0}.
     *
     * @return rank value.
     */
    default int getRank() {
        return 0;
    }

    /**
     * Invoked when one or more events for this listener's domain are
     * dispatched. Events arrive in capture order (earliest first).
     *
     * @param events the non-empty list of events for this listener's
     *               domain. Each event's payload map values are never
     *               null; optional fields are absent from the map.
     */
    void onEvents(@NotNull List<AuditEvent> events);
}
