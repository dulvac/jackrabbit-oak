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

import org.apache.jackrabbit.oak.spi.security.SecurityConfiguration;
import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Security configuration for Oak's audit pipeline. In v1, audit is treated
 * as a security concern: the audit pipeline is composed alongside the six
 * core {@link SecurityConfiguration} types (authentication, authorization,
 * user, privilege, principal, token) and contributes commit hooks through
 * the inherited {@link SecurityConfiguration#getCommitHooks(String)}.
 * <p>
 * {@code AuditConfiguration} is a typed handle on the audit pipeline,
 * usable via
 * {@code securityProvider.getConfiguration(AuditConfiguration.class)}. It
 * exposes pipeline-level state ({@link #isActive()}) so security-aware
 * components within Oak's stack can probe the pipeline without depending
 * on the implementation class.
 * <p>
 * Runtime listener registration is handled via the
 * {@link org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard}, not via
 * this interface. Capture sites (e.g.
 * {@code org.apache.jackrabbit.oak.security.user.UserManagerImpl})
 * record events through the static
 * {@link org.apache.jackrabbit.oak.spi.audit.AuditEvents} façade, which
 * short-circuits to a no-op when {@link #isActive()} would return
 * {@code false}.
 * <p>
 * <strong>Cardinality</strong> is intentionally <strong>unary
 * optional</strong>: {@code AuditBufferLifecycle} is a singleton install
 * and multiple implementations would duplicate the (snapshot, dispatch)
 * hook pair in {@code MutableRoot}'s composition. Multiplexing belongs at
 * the listener layer ({@code AuditEventListener}), not at the
 * configuration layer.
 * <p>
 * When no implementation is bound, {@code SecurityProvider} returns
 * {@link #NOOP} from {@code getConfiguration(AuditConfiguration.class)} —
 * never {@code null}. The NOOP keeps the
 * {@link #getCommitHooks(String)} chain consistent (returns an empty
 * list, contributes nothing), preserves a uniform {@code @NotNull} return
 * contract, and reports {@link #isActive()} as {@code false}.
 */
@ProviderType
public interface AuditConfiguration extends SecurityConfiguration {

    /**
     * Name of the audit security configuration. Stable across releases.
     */
    String NAME = "org.apache.jackrabbit.oak.audit";

    /**
     * Returns {@code true} when the audit pipeline is currently active —
     * i.e., the feature toggle is enabled AND at least one
     * {@code AuditEventListener} is registered on the Whiteboard. The two
     * predicates AND together so a deployed-but-unused pipeline still
     * reports {@code false}, matching the no-allocation semantics
     * documented at the
     * {@link org.apache.jackrabbit.oak.spi.audit.AuditEvents#isEnabled()}
     * façade.
     * <p>
     * Equivalent in semantics to {@code AuditEvents.isEnabled()}, but
     * reachable via the typed
     * {@link org.apache.jackrabbit.oak.spi.security.SecurityProvider#getConfiguration(Class)}
     * lookup. Components within Oak's security stack that already hold a
     * {@code SecurityProvider} reference can probe via this method
     * without touching the static {@code AuditEvents} façade.
     * <p>
     * <strong>Drift-prevention invariant.</strong> This method's
     * predicate MUST remain equivalent to
     * {@code AuditEvents.isEnabled()} — both report "feature toggle ON
     * AND at least one listener registered". The two paths exist for
     * different consumer ergonomics, NOT for divergent semantics. If a
     * future implementation needs to diverge these two predicates (e.g.
     * to introduce a "paused" state visible to one path but not the
     * other), the divergence MUST be documented explicitly in both this
     * Javadoc and the {@code AuditEvents.isEnabled()} Javadoc — silent
     * drift between the static façade and the typed handle is a contract
     * violation.
     *
     * @return {@code true} when the toggle is enabled and at least one
     *         listener is registered; {@code false} otherwise.
     */
    boolean isActive();

    /**
     * NOOP default. Contributes no commit hooks, exposes no parameters,
     * and reports {@link #isActive()} as {@code false}. Used by
     * {@code SecurityProvider} implementations as a placeholder when no
     * audit configuration is bound.
     */
    AuditConfiguration NOOP = new Noop();

    /**
     * NOOP implementation of {@link AuditConfiguration}. Package-private
     * by design — consumers refer to the {@link #NOOP} constant.
     */
    final class Noop extends SecurityConfiguration.Default implements AuditConfiguration {

        @NotNull
        @Override
        public String getName() {
            return AuditConfiguration.NAME;
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }
}
