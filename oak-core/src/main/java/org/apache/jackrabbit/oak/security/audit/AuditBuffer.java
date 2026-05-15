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

import org.apache.jackrabbit.oak.spi.audit.AuditBufferLifecycle;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-thread, per-session staging area for audit events. Events captured
 * via {@link org.apache.jackrabbit.oak.spi.audit.AuditEvents#record}
 * are appended to a session-scoped {@code ArrayList} held in a
 * {@link ThreadLocal}; the list is allocated lazily on first {@code record}
 * and is removed when {@link #drain(String)} is called (i.e. on commit
 * snapshot) or when a lifecycle event clears it.
 * <p>
 * The buffer also implements {@link AuditBufferLifecycle.Listener}; it is
 * installed via {@code AuditBufferLifecycle.install(this)} by
 * {@link AuditConfigurationImpl} on activation.
 * <p>
 * Threading contract: capture, drain and lifecycle calls all happen on
 * the session's caller thread. Cross-thread invocation is not supported
 * — sessions are not thread-safe in Oak.
 */
final class AuditBuffer implements AuditBufferLifecycle.Listener {

    /**
     * Thread-local map keyed by {@code sessionId}
     * ({@code ContentSession.toString()}). The inner list is created
     * lazily on the first {@link #record(String, AuditEvent)} for the
     * given session, kept alive across multiple captures, and removed
     * by {@link #drain(String)} / {@link #onCommitFailed(String)} /
     * {@link #onRefresh(String)}.
     * <p>
     * The outer map starts {@code null} (a single {@link ThreadLocal}
     * lookup yielding {@code null}) and is allocated on first capture
     * for the thread.
     */
    private final ThreadLocal<Map<String, List<AuditEvent>>> tl = new ThreadLocal<>();

    /**
     * Appends {@code event} to the session's per-thread buffer,
     * allocating the inner list lazily.
     *
     * @param sessionId session id, non-null.
     * @param event     event to record, non-null.
     */
    void record(@NotNull String sessionId, @NotNull AuditEvent event) {
        Map<String, List<AuditEvent>> bySession = tl.get();
        if (bySession == null) {
            bySession = new HashMap<>(4);
            tl.set(bySession);
        }
        List<AuditEvent> list = bySession.get(sessionId);
        if (list == null) {
            list = new ArrayList<>(4);
            bySession.put(sessionId, list);
        }
        list.add(event);
    }

    /**
     * Returns the staged events for {@code sessionId} <strong>without</strong>
     * removing them from the buffer. Used by
     * {@link SnapshotAuditBufferHook} so that, if a later hook in the
     * chain throws and the merge retries, the events are still present
     * on the next attempt. The {@link DispatchAuditEventsHook} (on
     * commit success) and {@code AuditBufferLifecycle.onCommitFailed} /
     * {@code .onRefresh} (on failure) are the authorities that clear
     * the buffer.
     * <p>
     * The returned list is the live backing list — callers must not
     * mutate it.
     *
     * @param sessionId session id, non-null.
     * @return the staged events, or {@code null} when nothing was
     *         staged for the session on the current thread.
     */
    @Nullable
    List<AuditEvent> peek(@NotNull String sessionId) {
        Map<String, List<AuditEvent>> bySession = tl.get();
        if (bySession == null) {
            return null;
        }
        return bySession.get(sessionId);
    }

    /**
     * Detaches and returns the staged events for {@code sessionId},
     * leaving the buffer empty for that session.
     *
     * @param sessionId session id, non-null.
     * @return the staged events, or {@code null} when nothing was
     *         staged for the session on the current thread.
     */
    @Nullable
    List<AuditEvent> drain(@NotNull String sessionId) {
        Map<String, List<AuditEvent>> bySession = tl.get();
        if (bySession == null) {
            return null;
        }
        List<AuditEvent> drained = bySession.remove(sessionId);
        if (bySession.isEmpty()) {
            tl.remove();
        }
        return drained;
    }

    /**
     * Drops all staged events for the <strong>current thread</strong>.
     * Called by {@link AuditConfigurationImpl#deactivate} so the
     * deactivator thread leaves no residue.
     * <p>
     * Note: this cannot reach across thread boundaries. ThreadLocal
     * entries on other threads remain until their owning thread next
     * calls {@link #record(String, AuditEvent)}, {@link #drain(String)},
     * or the {@code AuditBufferLifecycle} listener is invoked. The
     * resulting residual leak is bounded by
     * {@code worker-pool × in-flight sessions}; acknowledged for v1.
     */
    void clearAll() {
        tl.remove();
    }

    /**
     * Test-only accessor. Returns {@code true} when the {@link ThreadLocal}
     * backing map has been allocated on the current thread — i.e. at
     * least one {@link #record(String, AuditEvent)} call has happened
     * since the last full drain.
     *
     * @return whether the per-thread map has been allocated.
     */
    boolean isAllocatedOnCurrentThread() {
        return tl.get() != null;
    }

    //----------------------------------------< AuditBufferLifecycle.Listener >---
    @Override
    public void onCommitFailed(@NotNull String sessionId) {
        drain(sessionId);
    }

    @Override
    public void onRefresh(@NotNull String sessionId) {
        drain(sessionId);
    }
}
