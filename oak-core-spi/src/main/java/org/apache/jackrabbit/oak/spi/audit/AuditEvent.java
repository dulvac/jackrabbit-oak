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
 * <p>
 * Most callers do not implement this interface directly: use the static
 * factory {@link #of(String, String, Map)} (or the no-payload overload
 * {@link #of(String, String)}) to construct an immutable event with the
 * current wall-clock timestamp. The package-private {@code AuditEventImpl}
 * backs these factories.
 */
@ProviderType
public interface AuditEvent {

    /**
     * Returns the domain that owns this event. Listeners are domain-scoped:
     * an {@link AuditEventListener} only receives events whose
     * {@code getDomain()} matches its own {@link AuditEventListener#getDomain()}.
     *
     * @return non-null domain name (e.g. {@code "oak.security"}, {@code "aem.content"}).
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
     * For commit-attached events, Oak's drain path adds entries with the
     * keys {@code commit.sessionId}, {@code commit.userId}, and
     * {@code commit.timestamp} when the buffer is drained on commit
     * success. Fire-and-forget events do not carry these entries.
     * <p>
     * <strong>Trust contract.</strong> On the <em>commit-attached</em> path
     * Oak <em>unconditionally overrides</em> exactly three payload keys with
     * the values from {@code CommitInfo}: {@code commit.sessionId},
     * {@code commit.userId} and {@code commit.timestamp} (via
     * {@code CommitMetadataDecorator}); on that path those three cannot be
     * forged by the caller. Every other {@code commit.*} key is forwarded
     * verbatim from the caller-supplied payload — even on the commit path —
     * and is untrusted; anchor trust on the three specific keys, never on the
     * {@code commit.} prefix in general.
     * <p>
     * The <em>fire-and-forget</em> path
     * ({@link AuditEventEmitter#emit(AuditEvent)} / {@code AuditEvents.dispatch})
     * attests <strong>nothing</strong>: it forwards the caller payload
     * undecorated, so a caller MAY itself populate {@code commit.sessionId} /
     * {@code commit.userId} / {@code commit.timestamp} and Oak will neither
     * overwrite nor strip them. A listener therefore <strong>cannot</strong>
     * tell an Oak-attested commit-attached event from a fire-and-forget event
     * that merely carries those keys by inspecting the payload alone — under
     * Oak's open trust model the payload is never redacted or filtered at
     * dispatch. The presence of {@code commit.*} keys is therefore
     * <strong>not</strong> a trustworthy attestation signal: the listener SPI
     * delivers both the commit-attached and fire-and-forget paths through the
     * same {@code AuditEventListener.onEvents} with no path indicator, so trust
     * in commit identity must be established by constraining which bundles may
     * emit (a deployment-level control), not inferred from the payload.
     *
     * @return non-null, immutable payload map.
     */
    @NotNull
    default Map<String, Object> getPayload() {
        return Collections.emptyMap();
    }

    /**
     * Creates an immutable audit event with the supplied payload and the
     * current wall-clock timestamp. The payload Map is defensively copied
     * via {@link Map#copyOf}; the caller's Map reference is decoupled
     * from the event.
     *
     * @param domain  non-blank domain identifier.
     * @param type    non-blank event type identifier within {@code domain}.
     * @param payload immutable, non-null payload Map. Values are stored
     *                by reference — see the shallow-copy note below.
     * @return non-null event instance.
     * @throws IllegalArgumentException if {@code domain} or {@code type} is blank.
     *
     * @apiNote
     * <p><strong>Shallow-copy semantics.</strong> {@link Map#copyOf} decouples
     * the caller's Map reference but does NOT clone payload <em>values</em>.
     * Callers MUST pass immutable values (Strings, boxed primitives,
     * {@link java.util.List#copyOf(java.util.Collection) List.copyOf} /
     * {@link java.util.Set#copyOf(java.util.Collection) Set.copyOf} results).
     * Mutating a payload value after passing it to {@code of(...)} produces
     * undefined dispatch behavior on the commit-attached path, where capture
     * and dispatch are separated by the surrounding commit.
     *
     * <p><strong>Security warning.</strong> The {@code payload} map values
     * are forwarded verbatim to listeners. Callers MUST NOT pass:
     * <ul>
     *   <li>Any {@link javax.jcr.Credentials} subtype.</li>
     *   <li>The value of a {@code rep:password} or {@code rep:credentials}
     *       property.</li>
     *   <li>Any token-bearing object (e.g. {@code TokenInfo},
     *       {@code TokenCredentials}, raw token strings).</li>
     *   <li>Any node, property, or value that could transitively expose such
     *       data (e.g. a {@code Node} pointing at a {@code rep:User} subtree).</li>
     * </ul>
     * Pass user identifiers, paths, timestamps, and other non-sensitive
     * scalars only. <strong>Oak does not redact or filter the payload at
     * dispatch.</strong> See {@code oak-doc/src/site/markdown/security/audit-design.md}
     * (§0 trust contract) for the producer-side responsibility under the
     * open trust model.
     */
    @NotNull
    static AuditEvent of(@NotNull String domain,
                         @NotNull String type,
                         @NotNull Map<String, Object> payload) {
        if (domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        return new AuditEventImpl(domain, type, System.currentTimeMillis(), Map.copyOf(payload));
    }

    /**
     * Convenience overload for events with no payload.
     *
     * @param domain non-blank domain identifier.
     * @param type   non-blank event type identifier within {@code domain}.
     * @return non-null event instance with an empty payload.
     * @throws IllegalArgumentException if {@code domain} or {@code type} is blank.
     */
    @NotNull
    static AuditEvent of(@NotNull String domain, @NotNull String type) {
        return of(domain, type, Map.of());
    }
}
