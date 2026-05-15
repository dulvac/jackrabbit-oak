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
import java.util.Comparator;
import java.util.List;

import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.whiteboard.AbstractServiceTracker;
import org.jetbrains.annotations.NotNull;

/**
 * Whiteboard-backed registry of {@link AuditEventListener} services.
 * <p>
 * Every predicate ({@link #hasAnyListener()}, {@link #hasListenerFor(String)})
 * and every retrieval ({@link #getListeners()}) goes through a live
 * {@code getServices()} call to the underlying {@link
 * org.apache.jackrabbit.oak.spi.whiteboard.Tracker Tracker} — no cached
 * domain set is held inside this registry. The {@code Tracker} SPI exposes
 * no listener add/remove notification, so any cache here would either be
 * stale on listener arrival/departure or require polling. The capture-site
 * fast path is kept cheap by relying on the underlying Whiteboard's
 * own dispatch: {@code DefaultWhiteboard.lookup(...)} returns the singleton
 * {@link java.util.Collections#emptyList()} when no services of the type
 * are registered, so {@link #hasAnyListener()} is constant-time and
 * allocation-free in the no-listener regime.
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
        return !getServices().isEmpty();
    }

    /**
     * Cheap predicate: is at least one listener registered for the
     * supplied {@code domain}? Read on the capture hot path.
     * <p>
     * Linear scan of the live listener list. Capture sites typically face
     * a listener count in single digits, so iterating is competitive with
     * (and simpler than) a maintained domain set.
     *
     * @param domain the domain to check, non-null.
     * @return {@code true} when at least one listener is registered for
     *         the domain.
     */
    boolean hasListenerFor(@NotNull String domain) {
        for (AuditEventListener listener : getServices()) {
            if (domain.equals(listener.getDomain())) {
                return true;
            }
        }
        return false;
    }
}
