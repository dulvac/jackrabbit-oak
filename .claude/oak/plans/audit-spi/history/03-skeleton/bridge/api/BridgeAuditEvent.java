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
package org.apache.jackrabbit.oak.audit.bridge;

import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Bridge-side counterpart of {@code oak-security-spi}'s {@code AuditEvent}.
 * Lives in {@code oak-audit-bridge-api}, which AEM compiles against, so
 * AEM never sees the {@code oak-security-spi} type.
 * <p>
 * Instances are produced via the static factory method
 * {@link #of(String, String, Map)}, which returns the package-private
 * immutable {@link BridgeAuditEventImpl}. The bridge runtime check
 * rejects events whose runtime type is not {@code BridgeAuditEventImpl},
 * making this the single legitimate construction path — see
 * {@code 07-bridge-design.md} §4 (sage's 4-constraint package).
 * <p>
 * <strong>Reserved domains.</strong> The factory rejects domains in
 * {@link BridgeAuditDomains#RESERVED_DOMAINS} — currently
 * {@code "security"}. AEM cannot forge events that look like Oak's own
 * security captures.
 * <p>
 * <strong>Payload constraints.</strong>
 * <ul>
 *   <li>Map values MUST NOT be {@code null}.
 *       {@link Map#copyOf(Map)} rejects null values.</li>
 *   <li>Payload keys MUST NOT match
 *       {@code (?i)password|secret|token|key$}. The bridge runtime
 *       check rejects credential-shaped keys at
 *       {@link AuditEventEmitter#recordOnCommit}.</li>
 *   <li>Sensitive material (passwords, tokens, raw credentials,
 *       authorization headers) MUST NOT be placed in the payload —
 *       the runtime check is a backstop, not a license to try.</li>
 * </ul>
 */
@ProviderType
public interface BridgeAuditEvent {

    /**
     * @return the domain that selects matching listeners, non-null and
     *         non-empty. Convention: dotted lowercase
     *         ({@code "aem.content"}, {@code "aem.replication"}).
     *         {@code "security"} is reserved — see
     *         {@link BridgeAuditDomains#RESERVED_DOMAINS}.
     */
    @NotNull
    String getDomain();

    /**
     * @return the event type identifier within the domain, non-null and
     *         non-empty. Stable across releases. Convention: hyphen or
     *         dot separated ({@code "FragmentPublished"},
     *         {@code "replication.activated"}).
     */
    @NotNull
    String getType();

    /**
     * @return wall-clock millis since epoch at the time of event
     *         construction.
     */
    long getTimestamp();

    /**
     * @return immutable payload map. Values are non-null. Order matches
     *         insertion order if the supplied map was ordered
     *         (typically {@link java.util.LinkedHashMap}); otherwise
     *         unspecified.
     */
    @NotNull
    Map<String, Object> getPayload();

    // ---- factory --------------------------------------------------------

    /**
     * Factory: event in a non-reserved domain.
     *
     * @param domain  audit domain, non-null, non-empty. MUST NOT be in
     *                {@link BridgeAuditDomains#RESERVED_DOMAINS}.
     * @param type    event type, non-null, non-empty.
     * @param payload event payload, non-null. Values must be non-null.
     *                See class-level Javadoc for key-shape constraints.
     * @return a new immutable event with the current timestamp.
     * @throws IllegalArgumentException if {@code domain} is reserved.
     */
    @NotNull
    static BridgeAuditEvent of(@NotNull String domain,
                               @NotNull String type,
                               @NotNull Map<String, Object> payload) {
        if (BridgeAuditDomains.RESERVED_DOMAINS.contains(domain)) {
            throw new IllegalArgumentException(
                    "Domain '" + domain + "' is reserved by Oak. "
                            + "External callers cannot emit events in this domain.");
        }
        return new BridgeAuditEventImpl(domain, type, payload);
    }
}
