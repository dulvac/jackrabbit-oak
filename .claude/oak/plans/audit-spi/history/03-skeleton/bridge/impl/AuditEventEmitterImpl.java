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

import java.util.Locale;

import javax.jcr.Session;

import org.apache.jackrabbit.oak.audit.bridge.AuditEventEmitter;
import org.apache.jackrabbit.oak.audit.bridge.BridgeAuditEvent;
import org.apache.jackrabbit.oak.spi.security.audit.AuditConstants;
import org.apache.jackrabbit.oak.spi.security.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.security.audit.AuditEvents;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link AuditEventEmitter} implementation. Lives in
 * {@code oak-audit-bridge} (impl module, no exported package). AEM never
 * sees this class — only the {@link AuditEventEmitter} interface from
 * {@code oak-audit-bridge-api}.
 * <p>
 * Single-channel: commit-attached only. Fire-and-forget was eliminated in
 * the v1 design — see {@code 07-bridge-design.md} §2.
 * <p>
 * <strong>Service scope: {@link ServiceScope#BUNDLE}.</strong> One emitter
 * instance per requesting bundle, cached by the OSGi framework. Activates
 * via OSGi DS R7 constructor injection — {@link ComponentContext} is
 * passed at construction, allowing the requesting bundle's symbolic name
 * to be captured exactly once in a {@code final} field. The captured
 * value is used by the adapter to populate
 * {@code AuditEvent.getOriginBundle()} on every event emitted through
 * this instance, giving listeners a reliable Oak-attested vs.
 * bundle-attested discriminator.
 * <p>
 * <strong>Runtime check sequence in {@link #recordOnCommit}</strong>
 * (see {@code 07-bridge-design.md} §3 + §5):
 * <ol>
 *   <li>{@code session.isLive()} — throw IAE if not.</li>
 *   <li>{@code session instanceof JackrabbitSession} — throw IAE if not
 *       (non-Oak session). Resolved via the {@link JackrabbitSessionAccessor}
 *       seam for testability.</li>
 *   <li>{@code event.getClass().getName().equals(LEGITIMATE_EVENT_IMPL_CLASS_NAME)}
 *       — throw IAE if not (forged third-party impl of
 *       {@link BridgeAuditEvent}).</li>
 *   <li>{@code !AuditConstants.RESERVED_DOMAINS.contains(event.getDomain())}
 *       — throw IAE if reserved.</li>
 *   <li>Scan {@code event.getPayload().keySet()} against
 *       {@link AuditConstants#FORBIDDEN_PAYLOAD_KEYS} (case-insensitive
 *       equals-match); throw IAE on match.</li>
 * </ol>
 * All five gates WARN-log before throwing, using
 * {@code FrameworkUtil.getBundle(event.getClass()).getSymbolicName()} for
 * forensic attribution (best-effort identification of the offending
 * bundle; classloader-based lookup is appropriate for the rejection
 * forensic path even though normal-path attribution uses the
 * activate-time-captured field).
 */
@Component(service = AuditEventEmitter.class,
           scope = ServiceScope.BUNDLE,
           immediate = true)
public class AuditEventEmitterImpl implements AuditEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(AuditEventEmitterImpl.class);

    /**
     * Fully-qualified runtime type that legitimate {@link BridgeAuditEvent}
     * instances MUST be. Compared by FQN string because
     * {@code BridgeAuditEventImpl} is package-private in
     * {@code oak-audit-bridge-api} and the impl bundle (this class) can't
     * reference the type directly. The FQN compare achieves the same
     * defense — see {@code 07-bridge-design.md} §3.
     */
    private static final String LEGITIMATE_EVENT_IMPL_CLASS_NAME =
            "org.apache.jackrabbit.oak.audit.bridge.BridgeAuditEventImpl";

    /**
     * Testable seam for {@code Session} → {@code sessionId}. Production
     * binds {@link DefaultJackrabbitSessionAccessor}; unit tests bind a
     * Mockito stub.
     */
    private final JackrabbitSessionAccessor sessionAccessor;

    /**
     * Symbolic name of the bundle that requested this emitter instance.
     * Captured exactly once at activation via
     * {@link ComponentContext#getUsingBundle()} (or supplied directly by
     * the visible-for-testing constructor). Flows into every emitted
     * event's {@code getOriginBundle()} via {@link BridgeAuditEventAdapter}.
     * <p>
     * Sentinel {@code "(non-osgi)"} is used when
     * {@code getUsingBundle()} returns {@code null} — possible in some
     * Felix/Equinox test-harness scenarios where the DS engine
     * instantiates without a binding context.
     */
    private final String requestingBundleSymbolicName;

    /** OSGi DS R7 constructor injection. Production entry point. */
    @Activate
    public AuditEventEmitterImpl(@NotNull ComponentContext context) {
        this(new DefaultJackrabbitSessionAccessor(), resolveBundle(context));
    }

    /**
     * Visible-for-testing constructor. Tests inject a mock accessor and a
     * deterministic bundle symbolic name (rather than relying on the
     * OSGi runtime to provide one).
     */
    AuditEventEmitterImpl(@NotNull JackrabbitSessionAccessor sessionAccessor,
                          @NotNull String bundleSymbolicName) {
        this.sessionAccessor = sessionAccessor;
        this.requestingBundleSymbolicName = bundleSymbolicName;
    }

    @Deactivate
    private void deactivate() {
        log.info("AuditEventEmitter for bundle '{}' deactivated.",
                requestingBundleSymbolicName);
    }

    //--------------------------------------------------< AuditEventEmitter >---

    @Override
    public boolean isEnabledFor(@NotNull String domain) {
        return AuditEvents.isEnabledFor(domain);
    }

    @Override
    public void recordOnCommit(@NotNull Session jcrSession, @NotNull BridgeAuditEvent event) {
        // Gate 1: liveness. Closed session = audit gap if silent.
        if (!jcrSession.isLive()) {
            rejectAndLog(event, "Session is closed.");
        }
        // Gate 2: Oak-backed. Foreign sessions can't be mapped to the
        // AuditBuffer key.
        String sessionId = sessionAccessor.getSessionId(jcrSession);
        if (sessionId == null) {
            rejectAndLog(event,
                    "Session is not an Oak-backed JackrabbitSession: "
                            + jcrSession.getClass().getName());
        }
        // Gate 3: instance-type. Refuses third-party impls of the
        // BridgeAuditEvent interface (sage's constraint 4 — forged-impl
        // defense).
        if (!LEGITIMATE_EVENT_IMPL_CLASS_NAME.equals(event.getClass().getName())) {
            rejectAndLog(event,
                    "BridgeAuditEvent is not the canonical impl: "
                            + event.getClass().getName()
                            + " — construct via BridgeAuditEvent.of(...).");
        }
        // Gate 4: reserved domain — defense in depth against the factory's
        // construction-time check (bytecode-level bypass would skip the
        // factory). Reads from oak-security-spi.AuditConstants — the
        // canonical source. The oak-audit-bridge-api side uses a
        // hardcoded mirror (BridgeAuditDomains) for the factory's check;
        // a test-scope drift test in oak-audit-bridge-api asserts the
        // two stay in sync.
        if (AuditConstants.RESERVED_DOMAINS.contains(event.getDomain())) {
            rejectAndLog(event,
                    "Domain '" + event.getDomain() + "' is reserved.");
        }
        // Gate 5: forbidden credential-shaped payload keys. Canonical
        // set lives in oak-security-spi.AuditConstants; both this runtime
        // check AND sage's compile-time AuditEventCredentialFieldsTest
        // scan reference the same constant.
        rejectIfForbiddenKey(event);

        // All gates passed. Translate and dispatch.
        AuditEvent adapted = BridgeAuditEventAdapter.adapt(event, requestingBundleSymbolicName);
        // New Sink overload approved in 07-bridge-design.md §5 — keyed
        // by sessionId String, no Root involved.
        AuditEvents.record(sessionId, adapted);
    }

    //-----------------------------------------------------------< internal >---

    /**
     * Reads {@code context.getUsingBundle()} and extracts its symbolic
     * name. Returns the sentinel {@code "(non-osgi)"} when no using
     * bundle is bound — defensive against Felix/Equinox test-harness
     * scenarios where the DS engine instantiates without a binding
     * context.
     */
    @NotNull
    private static String resolveBundle(@NotNull ComponentContext context) {
        Bundle b = context.getUsingBundle();
        return (b != null) ? b.getSymbolicName() : "(non-osgi)";
    }

    private static void rejectIfForbiddenKey(@NotNull BridgeAuditEvent event) {
        for (String key : event.getPayload().keySet()) {
            if (AuditConstants.FORBIDDEN_PAYLOAD_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                rejectAndLog(event,
                        "Payload key '" + key
                                + "' is in the forbidden credential-key set.");
            }
        }
    }

    /**
     * Logs the rejection with forensic attribution and throws.
     * {@code FrameworkUtil.getBundle} can return {@code null} for
     * non-OSGi classloaders (unit tests with classpath isolation, for
     * example) — handled defensively. Used in the REJECTION path; the
     * normal-path attribution uses the activate-time-captured
     * {@link #requestingBundleSymbolicName}.
     */
    private static void rejectAndLog(@NotNull BridgeAuditEvent event,
                                     @NotNull String reason) {
        Bundle origin = FrameworkUtil.getBundle(event.getClass());
        String bundleName = (origin == null) ? "<no-bundle>" : origin.getSymbolicName();
        log.warn("Rejecting audit event from bundle '{}' (domain='{}', type='{}'): {}",
                bundleName, event.getDomain(), event.getType(), reason);
        throw new IllegalArgumentException(reason);
    }
}
