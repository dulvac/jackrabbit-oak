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

import javax.jcr.Session;

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Public bridge by which non-Oak bundles (typically AEM components,
 * Sling services, content-fragment workflows) record audit events without
 * taking a compile-time dependency on {@code oak-security-spi} or any Oak
 * SPI type.
 * <p>
 * Implementations are registered as OSGi services and injected via
 * {@code @Reference}. The implementation lives inside Oak in
 * {@code oak-audit-bridge}; this interface lives in
 * {@code oak-audit-bridge-api}, which AEM Maven-depends on. The split
 * keeps {@code oak-security-spi} off AEM's compile classpath — see
 * {@code .claude/oak/plans/audit-spi/07-bridge-design.md} §1.
 * <p>
 * <strong>Single channel: commit-attached only.</strong> v1 does not
 * expose a fire-and-forget emit path. The full reasoning is in
 * {@code 07-bridge-design.md} §2; the short version is: an externally
 * callable {@code emit(...)} without a commit boundary would let any
 * bundle forge security-relevant events with no producer-trust check
 * and no ability for downstream listeners to correlate the event with a
 * real repository operation. Non-commit signals (login, denied access)
 * are deferred to v1.1+ via Monitor SPI extensions — see §7.1.
 * <p>
 * <strong>Threading.</strong> {@link #recordOnCommit} MUST be called on
 * the same thread that will eventually call {@code Session.save()}. The
 * per-session audit buffer is thread-local; cross-thread emission
 * attaches the event to the wrong commit (or none at all). Documented
 * here as a v1 trade-off — no cheap runtime detection available.
 */
@ProviderType
public interface AuditEventEmitter {

    /**
     * Records an audit event against the underlying Oak session backing
     * the supplied JCR session. The event is buffered until the next
     * successful {@code Root.commit()} on that session, then dispatched
     * synchronously on the merge thread to all matching listeners.
     * Discarded on commit failure or {@code Root.refresh()} /
     * {@code Root.rebase()}.
     * <p>
     * The bridge enforces four runtime checks at the entry point, in
     * order. Any violation throws {@link IllegalArgumentException}
     * synchronously and emits a WARN log identifying the calling bundle
     * via
     * {@code FrameworkUtil.getBundle(event.getClass()).getSymbolicName()}
     * for forensic attribution:
     * <ol>
     *   <li><strong>Session liveness + Oak-backed check.</strong> The
     *       session MUST be live and an instance of
     *       {@code JackrabbitSession}. Silent no-op would hide audit
     *       gaps.</li>
     *   <li><strong>Instance-type check.</strong> The event MUST be
     *       {@code instanceof BridgeAuditEventImpl}. Third-party
     *       implementations of the {@link BridgeAuditEvent} interface
     *       are rejected — construction MUST be via the factory method
     *       on {@link BridgeAuditEvent}; bypassing that requires defining
     *       a parallel impl class, which this check refuses.</li>
     *   <li><strong>Reserved-domain check.</strong> The event's domain
     *       MUST NOT be in
     *       {@link BridgeAuditDomains#RESERVED_DOMAINS}. The current
     *       reserved set is {@code "security"}. Closes the three
     *       falsification scenarios (forged {@code MemberAddedEvent},
     *       {@code AccessControlPolicyRemovedEvent},
     *       {@code TokenCreatedEvent}) at the entry point.</li>
     *   <li><strong>Payload-key credential check.</strong> Payload keys
     *       MUST NOT match {@code (?i)password|secret|token|key$}.
     *       Caller intent (accidental or otherwise) is irrelevant — the
     *       bridge refuses to forward credential-shaped keys.</li>
     * </ol>
     * Audit infrastructure must fail loud on misuse.
     *
     * @param jcrSession the JCR session that will commit. Must be an
     *                   Oak-backed {@code JackrabbitSession} and must be
     *                   live.
     * @param event      the event to record, non-null. Must be
     *                   constructed via {@link BridgeAuditEvent#of}.
     * @throws IllegalArgumentException if the session is not Oak-backed,
     *                                  has been closed, or the event
     *                                  violates the constraints above.
     */
    void recordOnCommit(@NotNull Session jcrSession, @NotNull BridgeAuditEvent event);

    /**
     * Returns {@code true} when at least one listener is registered for
     * the given domain AND the audit feature toggle is enabled. Single
     * volatile read on the disabled path.
     * <p>
     * Callers MUST guard event construction with this predicate to avoid
     * paying for payload allocation on the disabled path:
     * <pre>{@code
     * if (emitter.isEnabledFor("aem.content")) {
     *     emitter.recordOnCommit(session,
     *         BridgeAuditEvent.of("aem.content", "FragmentPublished",
     *             Map.of("path", path)));
     * }
     * }</pre>
     *
     * @param domain the audit domain to check, non-null.
     * @return whether capture is active for the domain.
     */
    boolean isEnabledFor(@NotNull String domain);
}
