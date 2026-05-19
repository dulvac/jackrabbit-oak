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

import java.util.Collections;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Structured audit event produced by an Oak module at an API call site
 * <strong>before</strong> the surrounding {@link org.apache.jackrabbit.oak.api.Root#commit()}
 * has succeeded. Events are buffered per-session and dispatched as a single
 * burst on commit success (see {@link AuditEvents}).
 * <p>
 * Implementations are expected to be immutable value types. The
 * {@link #getDomain()} value selects the listeners that receive this event.
 */
@ProviderType
public interface AuditEvent {

    /**
     * Returns the domain that owns this event. Listeners are domain-scoped:
     * an {@link AuditEventListener} only receives events whose
     * {@code getDomain()} matches its own {@link AuditEventListener#getDomain()}.
     *
     * @return non-null domain name (e.g. {@link SecurityAuditDomain#NAME}).
     */
    @NotNull
    String getDomain();

    /**
     * Returns the event type identifier within the domain. Type strings are
     * stable across releases for any given domain.
     *
     * @return non-null type identifier.
     */
    @NotNull
    String getType();

    /**
     * Returns the wall-clock timestamp (millis since epoch) at which the
     * event was recorded — i.e. at the API call site, not at dispatch.
     *
     * @return event timestamp in milliseconds since epoch.
     */
    long getTimestamp();

    /**
     * Returns the structured payload for this event. The default
     * implementation returns an empty map; concrete event types
     * (e.g. {@link MemberAddedEvent}) override this to expose typed
     * accessors and include their fields here.
     *
     * @return non-null, immutable payload map.
     */
    @NotNull
    default Map<String, Object> getPayload() {
        return Collections.emptyMap();
    }
}
