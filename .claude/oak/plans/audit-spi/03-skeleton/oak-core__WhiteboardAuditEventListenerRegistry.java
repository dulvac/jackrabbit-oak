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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.spi.security.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.whiteboard.AbstractServiceTracker;
import org.jetbrains.annotations.NotNull;

/**
 * Whiteboard-backed registry of {@link AuditEventListener} services. The
 * registry caches the set of active listener domains to keep the capture
 * fast path allocation-free: a single volatile read decides whether the
 * capture site should build an event object.
 * <p>
 * The cached snapshot is refreshed eagerly on {@link #refreshDomains()}
 * and lazily on {@link #hasAnyListener()} / {@link #hasListenerFor(String)}
 * when the cached snapshot is missing (initial state).
 * <p>
 * Listener invocation order is determined by
 * {@link AuditEventListener#getRank()} (higher first). The registry
 * applies a stable sort on every call to {@link #getListeners()} —
 * relying on the underlying {@code Whiteboard} is not portable:
 * {@code DefaultWhiteboard} does not honor OSGi {@code service.ranking},
 * only {@code OsgiWhiteboard} does.
 */
final class WhiteboardAuditEventListenerRegistry
        extends AbstractServiceTracker<AuditEventListener> {

    /**
     * Stable comparator descending by {@link AuditEventListener#getRank()};
     * ties preserve {@code Whiteboard} insertion order because
     * {@link List#sort(Comparator)} is stable.
     */
    private static final Comparator<AuditEventListener> BY_RANK_DESC =
            Comparator.comparingInt(AuditEventListener::getRank).reversed();

    /**
     * Cached set of domains for which a listener is currently
     * registered. {@code null} until first lookup or
     * {@link #refreshDomains()} call. Read-only once published; volatile
     * publication ensures other threads see the latest snapshot.
     */
    private volatile Set<String> activeDomains;

    WhiteboardAuditEventListenerRegistry() {
        super(AuditEventListener.class);
    }

    /**
     * Returns the currently registered listeners, sorted by
     * {@link AuditEventListener#getRank()} descending (stable).
     *
     * @return non-null list of registered listeners (possibly empty).
     */
    @NotNull
    List<AuditEventListener> getListeners() {
        List<AuditEventListener> services = getServices();
        if (services.size() <= 1) {
            return services;
        }
        List<AuditEventListener> sorted = new ArrayList<>(services);
        sorted.sort(BY_RANK_DESC);
        return sorted;
    }

    /**
     * Cheap predicate: is at least one listener registered (any domain)?
     * Read on the capture hot path.
     *
     * @return {@code true} when at least one listener is currently
     *         registered.
     */
    boolean hasAnyListener() {
        Set<String> snapshot = activeDomains;
        if (snapshot == null) {
            snapshot = refreshDomains();
        }
        return !snapshot.isEmpty();
    }

    /**
     * Cheap predicate: is at least one listener registered for the
     * supplied {@code domain}? Read on the capture hot path.
     *
     * @param domain the domain to check, non-null.
     * @return {@code true} when at least one listener is registered for
     *         the domain.
     */
    boolean hasListenerFor(@NotNull String domain) {
        Set<String> snapshot = activeDomains;
        if (snapshot == null) {
            snapshot = refreshDomains();
        }
        return snapshot.contains(domain);
    }

    /**
     * Rebuilds the cached domain set from the current listener
     * snapshot. Called when listener arrival/departure invalidates the
     * cache, and lazily on first lookup.
     *
     * @return the refreshed (and now-cached) domain set.
     */
    @NotNull
    Set<String> refreshDomains() {
        List<AuditEventListener> listeners = getServices();
        if (listeners.isEmpty()) {
            Set<String> empty = Collections.emptySet();
            activeDomains = empty;
            return empty;
        }
        Set<String> domains = new HashSet<>(4);
        for (AuditEventListener listener : listeners) {
            domains.add(listener.getDomain());
        }
        Set<String> snapshot = Collections.unmodifiableSet(domains);
        activeDomains = snapshot;
        return snapshot;
    }
}
