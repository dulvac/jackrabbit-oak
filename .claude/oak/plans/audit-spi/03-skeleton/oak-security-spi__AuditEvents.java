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

import org.apache.jackrabbit.oak.api.Root;
import org.jetbrains.annotations.NotNull;

/**
 * Static façade used by Oak modules to record audit events at API call
 * sites. The façade is wired to an {@link Sink} by the audit module on
 * activation; when no module is deployed (or the feature toggle is off)
 * it short-circuits with zero allocation.
 * <p>
 * Typical use:
 * <pre>{@code
 * if (AuditEvents.isEnabled()) {
 *     AuditEvents.record(root, MemberAddedEvent.of(...));
 * }
 * }</pre>
 * The {@link #isEnabled()} guard is intentionally exposed so callers can
 * skip allocating the event payload entirely on the disabled path.
 */
public final class AuditEvents {

    /**
     * Sink contract implemented by the audit module. Installation happens
     * via {@link #install(Sink)} on activation of the audit module; on
     * deactivation the sink is reset to a NOOP.
     */
    public interface Sink {

        /**
         * Returns {@code true} when at least one listener is registered
         * for the given domain and the feature toggle is enabled. Used
         * by domain-specific callers to avoid allocating event objects.
         *
         * @param domain the domain to check, non-null.
         * @return whether capture is active for the domain.
         */
        boolean isEnabledFor(@NotNull String domain);

        /**
         * Returns {@code true} when the feature toggle is enabled and at
         * least one listener is registered (for any domain). This is the
         * cheapest possible gate; a single volatile read.
         *
         * @return whether capture is active.
         */
        boolean isEnabled();

        /**
         * Records the given event against the session backing the
         * supplied {@link Root}. Events are buffered per-session and
         * dispatched on commit success.
         *
         * @param root  the root associated with the current session,
         *              non-null.
         * @param event the event to record, non-null.
         */
        void record(@NotNull Root root, @NotNull AuditEvent event);
    }

    private static final Sink NOOP = new Sink() {
        @Override
        public boolean isEnabledFor(@NotNull String domain) {
            return false;
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void record(@NotNull Root root, @NotNull AuditEvent event) {
            // ignored — no audit module deployed.
        }
    };

    private static volatile Sink sink = NOOP;

    private AuditEvents() {
        // utility class
    }

    /**
     * Installs the active sink. Called by the audit module on activation.
     * Passing {@code null} resets the façade to the NOOP sink — this is
     * also the supported way to reset the façade between unit tests.
     *
     * @param newSink the sink to install, or {@code null} for NOOP.
     */
    public static void install(Sink newSink) {
        sink = (newSink != null) ? newSink : NOOP;
    }

    /**
     * @return {@code true} when capture is active (feature toggle on and
     *         at least one listener registered). Single volatile read on
     *         the disabled path.
     */
    public static boolean isEnabled() {
        return sink.isEnabled();
    }

    /**
     * @param domain the domain to check, non-null.
     * @return {@code true} when capture is active for the given domain.
     */
    public static boolean isEnabledFor(@NotNull String domain) {
        return sink.isEnabledFor(domain);
    }

    /**
     * Records the given event. Callers are expected to gate the call
     * with {@link #isEnabled()} (or {@link #isEnabledFor(String)}) so
     * that no event object is allocated when audit is off.
     *
     * @param root  the root associated with the current session, non-null.
     * @param event the event to record, non-null.
     */
    public static void record(@NotNull Root root, @NotNull AuditEvent event) {
        sink.record(root, event);
    }
}
