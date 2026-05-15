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

import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.osgi.OsgiWhiteboard;
import org.apache.jackrabbit.oak.spi.audit.AuditBufferLifecycle;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
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

        Whiteboard whiteboard = new OsgiWhiteboard(bundleContext);
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
        // Order matters — see Risk 5 in 01-architecture.md.
        // 1. Close the feature toggle FIRST. AuditEvents.isEnabled()
        //    immediately returns false, so any new capture-site call
        //    that races with deactivation short-circuits before reaching
        //    the buffer (which we're about to dismantle).
        if (featureToggle != null) {
            featureToggle.close();
            featureToggle = null;
        }
        // 2. Stop discovery — listeners disappear from getServices().
        if (registry != null) {
            registry.stop();
            registry = null;
        }
        // 3. Route AuditEvents/AuditBufferLifecycle to NOOP. Now even
        //    callers that already passed the isEnabled() gate land on
        //    no-ops.
        AuditEvents.install(null);
        AuditBufferLifecycle.install(null);
        // 4. Drain the deactivator thread's ThreadLocal. Residual entries
        //    on other threads are bounded by worker-pool × in-flight
        //    sessions; acknowledged trade-off for v1 (no weak-reference
        //    machinery).
        if (buffer != null) {
            buffer.clearAll();
            buffer = null;
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
            // Chunk 7 wires fire-and-forget dispatch here.
            // For now, throw to make the gap obvious if exercised.
            throw new UnsupportedOperationException(
                    "AuditEvents.dispatch not yet wired; will land in Chunk 7");
        }
    }
}
