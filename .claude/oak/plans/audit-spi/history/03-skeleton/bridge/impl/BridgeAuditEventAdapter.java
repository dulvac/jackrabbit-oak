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
package org.apache.jackrabbit.oak.audit.bridge.impl;

import java.util.Map;
import java.util.Optional;

import org.apache.jackrabbit.oak.audit.bridge.BridgeAuditEvent;
import org.apache.jackrabbit.oak.spi.security.audit.AuditEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Internal adapter that translates a public {@link BridgeAuditEvent}
 * (from {@code oak-audit-bridge-api}) into the oak-security-spi
 * {@link AuditEvent} contract. Package-private; the bridge is the only
 * caller. AEM never sees {@link AuditEvent}.
 * <p>
 * <strong>Origin-bundle tagging.</strong> Adapter overrides
 * {@link AuditEvent#getOriginBundle()} to return
 * {@code Optional.of(originBundleSymbolicName)}, where the value is the
 * symbolic name captured at {@link AuditEventEmitterImpl} activate time.
 * This is the bridge's contribution to the Trust Model on
 * {@link org.apache.jackrabbit.oak.spi.security.audit.AuditEventListener}
 * — listeners discriminate Oak-attested events (default impl returns
 * {@code Optional.empty()}) from bridge-attested events (this adapter
 * always returns {@code Optional.of(name)}). Per
 * {@code 07-bridge-design.md} v5-final §3 + §6.3.
 * <p>
 * <strong>Pure copy of domain/type/timestamp/payload.</strong> No
 * sanitization is performed here — the credential-key check (gate 5 in
 * {@code AuditEventEmitterImpl.recordOnCommit}) rejects the entire
 * event before the adapter is created. Adapter-level sanitization would
 * mean the caller gets a "partial" or mutated event silently, which
 * contradicts the "fail loud on misuse" stance of
 * {@code 07-bridge-design.md} §3.
 */
final class BridgeAuditEventAdapter implements AuditEvent {

    private final String domain;
    private final String type;
    private final long timestamp;
    private final Map<String, Object> payload;
    private final String originBundleSymbolicName;

    private BridgeAuditEventAdapter(@NotNull String domain,
                                    @NotNull String type,
                                    long timestamp,
                                    @NotNull Map<String, Object> payload,
                                    @NotNull String originBundleSymbolicName) {
        this.domain = domain;
        this.type = type;
        this.timestamp = timestamp;
        this.payload = payload;
        this.originBundleSymbolicName = originBundleSymbolicName;
    }

    /**
     * Adapts a public bridge event to {@link AuditEvent}, tagging it with
     * the calling bundle's symbolic name for the
     * {@link AuditEvent#getOriginBundle()} field.
     * <p>
     * The bridge runtime checks in {@link AuditEventEmitterImpl}
     * guarantee the incoming event has passed all five gates before
     * reaching this method.
     *
     * @param event                    the public bridge event, non-null.
     * @param originBundleSymbolicName the symbolic name of the bundle
     *                                 that requested the emitter,
     *                                 non-null. Must NOT be blank — the
     *                                 emitter guarantees a non-null,
     *                                 non-blank value (either the real
     *                                 OSGi-resolved name or the
     *                                 sentinel {@code "(non-osgi)"}).
     * @return an {@link AuditEvent} carrying the same domain, type,
     *         timestamp, and payload (referencing the same immutable
     *         map; safe because {@code BridgeAuditEvent.getPayload()}
     *         returns an unmodifiable view), plus an origin-bundle
     *         override.
     * @throws IllegalArgumentException if {@code originBundleSymbolicName}
     *                                  is blank.
     */
    @NotNull
    static AuditEvent adapt(@NotNull BridgeAuditEvent event,
                            @NotNull String originBundleSymbolicName) {
        if (originBundleSymbolicName.isEmpty()) {
            throw new IllegalArgumentException(
                    "originBundleSymbolicName must not be blank");
        }
        return new BridgeAuditEventAdapter(event.getDomain(), event.getType(),
                event.getTimestamp(), event.getPayload(),
                originBundleSymbolicName);
    }

    @NotNull
    @Override
    public String getDomain() {
        return domain;
    }

    @NotNull
    @Override
    public String getType() {
        return type;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @NotNull
    @Override
    public Map<String, Object> getPayload() {
        return payload;
    }

    /**
     * Returns the symbolic name of the bundle that requested this
     * emitter — captured at activate time, identical for all events
     * emitted through the same emitter instance.
     * <p>
     * Differs from the default {@code AuditEvent.getOriginBundle()}
     * which returns {@link Optional#empty()} for Oak-internal events.
     * This bridge adapter ALWAYS returns {@code Optional.of(name)} —
     * the bridge has, by definition, an originating bundle.
     */
    @NotNull
    @Override
    public Optional<String> getOriginBundle() {
        return Optional.of(originBundleSymbolicName);
    }
}
