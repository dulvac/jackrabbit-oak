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
 * Marker interface for the audit {@link SecurityConfiguration}. Allows
 * the audit plug-in to be discovered by Oak's
 * {@code SecurityProviderRegistration} via a typed OSGi
 * {@code @Reference}, separate from the six core
 * {@link SecurityConfiguration} types (authentication, authorization,
 * user, privilege, principal, token).
 * <p>
 * The audit pipeline contributes commit hooks through the inherited
 * {@link SecurityConfiguration#getCommitHooks(String)}. Runtime listener
 * registration is handled via the
 * {@link org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard}, not via
 * this interface.
 * <p>
 * Cardinality is intentionally <strong>unary optional</strong>:
 * {@code AuditBufferLifecycle} is a singleton install and multiple
 * implementations would duplicate the (snapshot, dispatch) hook pair in
 * {@code MutableRoot}'s composition. Multiplexing belongs at the
 * listener layer ({@code AuditEventListener}), not at the configuration
 * layer.
 * <p>
 * When no implementation is bound, {@code SecurityProvider} returns
 * {@link #NOOP} from {@code getConfiguration(AuditConfiguration.class)}
 * — never {@code null}. Following the {@code BlobAccessProvider}
 * pattern at {@code UserConfigurationImpl#unbindBlobAccessProvider},
 * the NOOP keeps the {@link #getCommitHooks(String)} chain consistent
 * (returns an empty list, contributes nothing) and preserves a uniform
 * {@code @NotNull} return contract.
 */
@ProviderType
public interface AuditConfiguration extends SecurityConfiguration {

    /**
     * Name of the audit security configuration. Stable across releases.
     */
    String NAME = "org.apache.jackrabbit.oak.audit";

    /**
     * NOOP default. Contributes no commit hooks, exposes no parameters.
     * Used by {@code SecurityProvider} implementations as a placeholder
     * when no audit configuration is bound.
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
    }
}
