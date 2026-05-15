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

import java.util.Collections;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Structured audit event. Implementations are expected to be immutable
 * value types.
 * <p>
 * Events may originate from two pipelines:
 * <ul>
 *   <li>Oak-internal capture sites tied to a successful
 *       {@code Root.commit()}. The commit-attached drain step decorates
 *       payload with {@code commit.sessionId}, {@code commit.userId},
 *       and {@code commit.timestamp} entries before dispatch.</li>
 *   <li>Any bundle calling {@link AuditEventEmitter#emit(AuditEvent)}.
 *       Such events carry the payload provided by the caller; Oak does
 *       not add or verify any fields.</li>
 * </ul>
 * The {@link #getDomain()} value selects the listeners that receive this
 * event.
 */
@ProviderType
public interface AuditEvent {

    /**
     * Returns the domain that owns this event. Listeners are domain-scoped:
     * an {@link AuditEventListener} only receives events whose
     * {@code getDomain()} matches its own {@link AuditEventListener#getDomain()}.
     *
     * @return non-null domain name (e.g. {@code "security"}, {@code "aem.content"}).
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
     * Returns the wall-clock timestamp (millis since epoch) captured at
     * the API call site when the event was constructed — i.e. the
     * <em>capture</em> timestamp.
     * <p>
     * For commit-attached events this can differ from the
     * <em>commit</em> timestamp ({@code commit.timestamp} in
     * {@link #getPayload()}): the capture timestamp is taken when the
     * Oak API call ran; the commit timestamp is taken when the surrounding
     * {@code Root.commit()} actually merged. The two can diverge when the
     * surrounding operation takes a long time between capture and commit.
     * Listeners that want "when did the change become visible?" should
     * read {@code commit.timestamp}; listeners that want "when did the
     * API call ran?" should read this value.
     *
     * @return event capture timestamp in milliseconds since epoch.
     */
    long getTimestamp();

    /**
     * Returns the structured payload for this event. The default
     * implementation returns an empty map; concrete event types override
     * this to expose typed accessors and include their fields here.
     * <p>
     * For commit-attached events, the {@code DispatchAuditEventsHook} adds
     * entries with the keys {@code commit.sessionId}, {@code commit.userId},
     * and {@code commit.timestamp} at drain time. Fire-and-forget events
     * do not carry these entries.
     * <p>
     * <strong>Trust contract.</strong> Oak <em>unconditionally overrides</em>
     * any caller-supplied values for the {@code commit.sessionId},
     * {@code commit.userId}, and {@code commit.timestamp} keys with the
     * values from {@code CommitInfo} on the commit-attached path. Listeners
     * may therefore treat the <em>presence</em> of {@code commit.*} keys as
     * an Oak-attested commit (the caller cannot forge these values). The
     * absence of these keys signals a fire-and-forget emission whose
     * payload reflects the emitting bundle's claim only.
     *
     * @return non-null, immutable payload map.
     */
    @NotNull
    default Map<String, Object> getPayload() {
        return Collections.emptyMap();
    }
}
