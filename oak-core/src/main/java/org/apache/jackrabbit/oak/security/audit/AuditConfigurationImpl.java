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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.osgi.OsgiWhiteboard;
import org.apache.jackrabbit.oak.spi.audit.AuditBufferLifecycle;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.audit.AuditEvents;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.security.ConfigurationBase;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.SecurityConfiguration;
import org.apache.jackrabbit.oak.spi.security.audit.AuditConfiguration;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link AuditConfiguration} implementation. Contributes the
 * audit pipeline to Oak:
 * <ul>
 *     <li>Registers a {@link Feature} toggle gating capture and dispatch.</li>
 *     <li>Installs a per-session buffer ({@link AuditBuffer}) into
 *     {@link AuditBufferLifecycle}.</li>
 *     <li>Installs the {@link AuditEvents} sink that routes capture-site
 *     calls into the buffer.</li>
 *     <li>Tracks {@code AuditEventListener} services on the Whiteboard
 *     via {@link WhiteboardAuditEventListenerRegistry}.</li>
 *     <li>Contributes two commit hooks via
 *     {@link #getCommitHooks(String)}:
 *     {@link SnapshotAuditBufferHook} (regular) and
 *     {@link DispatchAuditEventsHook} (post-validation).</li>
 * </ul>
 * Registered as both {@link AuditConfiguration} (for typed lookup) and
 * {@link SecurityConfiguration} (for hook contribution via
 * {@code SecurityProvider.getConfigurations()}).
 * <p>
 * When the feature toggle is disabled both hooks short-circuit and
 * capture is a no-op — see {@link AuditEvents#isEnabled()}.
 */
@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})
@Designate(ocd = AuditConfigurationImpl.Configuration.class)
public class AuditConfigurationImpl extends ConfigurationBase implements AuditConfiguration {

    /**
     * Feature toggle name. The fork ships this as {@code FT_AUDIT}.
     * When upstreaming, rename to {@code FT_AUDIT_OAK-<NNNNN>} per the
     * {@code FT_<DESCRIPTION>_OAK-<issue>} convention in {@code AGENTS.md}.
     */
    public static final String FEATURE_TOGGLE_NAME = "FT_AUDIT";

    @ObjectClassDefinition(name = "Apache Jackrabbit Oak AuditConfiguration",
            description = "Audit event pipeline. Capture and dispatch are " +
                    "gated by the '" + FEATURE_TOGGLE_NAME + "' feature toggle " +
                    "(disabled by default).")
    @interface Configuration {
        // Configuration is currently empty by design: capture/dispatch behavior
        // is controlled exclusively by the feature toggle. Listeners are
        // contributed via OSGi services / the Whiteboard.
    }

    private static final Logger log = LoggerFactory.getLogger(AuditConfigurationImpl.class);

    private Feature featureToggle;
    private AuditBuffer buffer;
    private WhiteboardAuditEventListenerRegistry registry;

    public AuditConfigurationImpl() {
        super();
    }

    @SuppressWarnings("UnusedDeclaration")
    @Activate
    private void activate(@NotNull Configuration configuration,
                          @NotNull BundleContext bundleContext,
                          @NotNull Map<String, Object> properties) {
        setParameters(ConfigurationParameters.of(properties));
        initialize(new OsgiWhiteboard(bundleContext));
    }

    /**
     * Non-OSGi entry point for wiring up the audit pipeline. Called by
     * {@link #activate} in OSGi deployments after the {@code BundleContext}
     * has been unwrapped into an {@code OsgiWhiteboard}, and by
     * {@code SecurityProviderBuilder} in embedded / test deployments
     * directly. The same code path runs in both worlds.
     * <p>
     * <strong>Must be called exactly once per instance.</strong> Calling
     * it more than once orphans the previous {@code Feature} toggle and
     * registry tracker, and silently overwrites the static
     * {@link AuditEvents} / {@link AuditBufferLifecycle} sinks — v1 does
     * not enforce single-call semantics, it's a contract. To rewire,
     * call {@link #dispose()} first.
     *
     * @param whiteboard the whiteboard to register the {@code Feature}
     *                   toggle and {@code AuditEventListener} tracker on;
     *                   non-null.
     */
    public void initialize(@NotNull Whiteboard whiteboard) {
        featureToggle = Feature.newFeature(FEATURE_TOGGLE_NAME, whiteboard);

        registry = new WhiteboardAuditEventListenerRegistry();
        registry.start(whiteboard);

        buffer = new AuditBuffer();
        AuditBufferLifecycle.install(buffer);

        AuditEvents.install(new BufferSink(featureToggle, registry, buffer));

        log.info("Audit pipeline activated. Toggle '{}' = {}.",
                FEATURE_TOGGLE_NAME, featureToggle.isEnabled());
    }

    @Deactivate
    private void deactivate() {
        dispose();
    }

    /**
     * Non-OSGi tear-down entry point, paired with
     * {@link #initialize(Whiteboard)}. Called by {@link #deactivate} in
     * OSGi deployments and directly by tests / embedded callers. Safe to
     * call when no pipeline was previously initialized — each step
     * guards against unset state.
     * <p>
     * Each cleanup step is wrapped in its own try/catch so an exception
     * at one step does not skip the rest: an OSGi deactivate that leaves
     * static façades pointing at half-torn-down state is worse than a
     * noisy log.
     */
    public void dispose() {
        // Order matters — see Risk 5 in 01-architecture.md.

        // 1. Close the feature toggle FIRST. AuditEvents.isEnabled()
        //    immediately returns false, so any new capture-site call
        //    that races with deactivation short-circuits before reaching
        //    the buffer (which we're about to dismantle).
        if (featureToggle != null) {
            try {
                featureToggle.close();
            } catch (RuntimeException e) {
                log.warn("Audit deactivate: featureToggle.close() failed; continuing.", e);
            } finally {
                featureToggle = null;
            }
        }
        // 2. Stop discovery — listeners disappear from getServices().
        if (registry != null) {
            try {
                registry.stop();
            } catch (RuntimeException e) {
                log.warn("Audit deactivate: registry.stop() failed; continuing.", e);
            } finally {
                registry = null;
            }
        }
        // 3. Route AuditEvents/AuditBufferLifecycle to NOOP. Now even
        //    callers that already passed the isEnabled() gate land on
        //    no-ops.
        try {
            AuditEvents.install(null);
        } catch (RuntimeException e) {
            log.warn("Audit deactivate: AuditEvents.install(null) failed; continuing.", e);
        }
        try {
            AuditBufferLifecycle.install(null);
        } catch (RuntimeException e) {
            log.warn("Audit deactivate: AuditBufferLifecycle.install(null) failed; continuing.", e);
        }
        // 4. Drain the deactivator thread's ThreadLocal. Residual entries
        //    on other threads are bounded by worker-pool × in-flight
        //    sessions; acknowledged trade-off for v1 (no weak-reference
        //    machinery).
        if (buffer != null) {
            try {
                buffer.clearAll();
            } catch (RuntimeException e) {
                log.warn("Audit deactivate: buffer.clearAll() failed; continuing.", e);
            } finally {
                buffer = null;
            }
        }
        log.info("Audit pipeline deactivated.");
    }

    //----------------------------------------------< SecurityConfiguration >---
    @NotNull
    @Override
    public String getName() {
        return NAME;
    }

    @NotNull
    @Override
    public List<? extends CommitHook> getCommitHooks(@NotNull String workspaceName) {
        if (featureToggle == null || buffer == null || registry == null) {
            return List.of();
        }
        return List.of(
                new SnapshotAuditBufferHook(featureToggle, buffer),
                new DispatchAuditEventsHook(featureToggle, buffer, registry));
    }

    //-----------------------------------------------------------< internal >---
    /**
     * Composite gate exposed to capture sites via {@link AuditEvents}.
     * Both predicates ({@link Feature#isEnabled()} and
     * {@link WhiteboardAuditEventListenerRegistry#hasAnyListener()}) are
     * single volatile reads; together they keep the disabled path free
     * of allocation.
     */
    private static final class BufferSink implements AuditEvents.Sink {

        private final Feature toggle;
        private final WhiteboardAuditEventListenerRegistry registry;
        private final AuditBuffer buffer;

        BufferSink(@NotNull Feature toggle,
                   @NotNull WhiteboardAuditEventListenerRegistry registry,
                   @NotNull AuditBuffer buffer) {
            this.toggle = toggle;
            this.registry = registry;
            this.buffer = buffer;
        }

        @Override
        public boolean isEnabled() {
            return toggle.isEnabled() && registry.hasAnyListener();
        }

        @Override
        public boolean isEnabledFor(@NotNull String domain) {
            return toggle.isEnabled() && registry.hasListenerFor(domain);
        }

        @Override
        public void record(@NotNull Root root, @NotNull AuditEvent event) {
            if (!isEnabledFor(event.getDomain())) {
                return;
            }
            buffer.record(root.getContentSession().toString(), event);
        }

        @Override
        public void dispatch(@NotNull AuditEvent event) {
            if (!toggle.isEnabled()) {
                return;
            }
            List<AuditEventListener> listeners = registry.getListeners();
            if (listeners.isEmpty()) {
                return;
            }
            String domain = event.getDomain();
            List<AuditEvent> single = Collections.singletonList(event);
            for (AuditEventListener listener : listeners) {
                if (!domain.equals(listener.getDomain())) {
                    continue;
                }
                try {
                    listener.onEvents(single);
                } catch (RuntimeException re) {
                    log.warn("AuditEventListener {} failed on fire-and-forget dispatch in domain '{}'; swallowing.",
                            listener.getClass().getName(), domain, re);
                }
            }
        }
    }
}
