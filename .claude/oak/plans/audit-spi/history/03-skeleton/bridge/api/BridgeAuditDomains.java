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

import java.util.Set;

/**
 * Bridge-side mirror of {@code oak-security-spi}'s
 * {@code AuditConstants.RESERVED_DOMAINS}. Used by the factory
 * {@link BridgeAuditEvent#of(String, String, java.util.Map)} for
 * construction-time domain rejection.
 * <p>
 * <strong>Why a mirror, not a direct reference?</strong> AEM bundles
 * compile against {@code oak-audit-bridge-api} only — they MUST NOT
 * have {@code oak-security-spi} on their compile classpath (per
 * Ada's two-module split rationale in {@code 07-bridge-design.md} §1).
 * If {@code BridgeAuditEvent.of(...)} referenced
 * {@code AuditConstants.RESERVED_DOMAINS} directly,
 * {@code oak-audit-bridge-api} would acquire a compile-time dependency
 * on {@code oak-security-spi}, transitively exposing security-SPI
 * types to AEM source — the exact thing the module split was designed
 * to prevent.
 * <p>
 * <strong>Drift defense.</strong> A test-scope JUnit test in
 * {@code oak-audit-bridge-api/src/test/} asserts
 * {@code RESERVED_DOMAINS.equals(AuditConstants.RESERVED_DOMAINS)} —
 * build-time divergence catch. The test-scope dependency on
 * {@code oak-security-spi} does NOT propagate to AEM's compile
 * classpath. Standard Oak pattern for cross-module value agreement.
 * <p>
 * <strong>Canonical source-of-truth.</strong>
 * {@code org.apache.jackrabbit.oak.spi.security.audit.AuditConstants.RESERVED_DOMAINS}
 * in {@code oak-security-spi}. The bridge IMPL's runtime check at
 * {@code AuditEventEmitterImpl.recordOnCommit(...)} (gate 4) reads
 * from the canonical source; this mirror is solely for the
 * AEM-facing factory.
 */
public final class BridgeAuditDomains {

    /**
     * Mirror of {@code AuditConstants.RESERVED_DOMAINS}. Currently
     * contains {@code "security"} — matches
     * {@code SecurityAuditDomain.NAME} in {@code oak-security-spi}.
     * <p>
     * External callers using
     * {@link BridgeAuditEvent#of(String, String, java.util.Map)} cannot
     * emit events in these domains; the factory throws
     * {@link IllegalArgumentException}.
     * <p>
     * When Oak adds new internal-only domains in v1.1+, both this
     * mirror AND {@code AuditConstants.RESERVED_DOMAINS} MUST be
     * updated in lockstep. The drift test catches forgotten updates
     * at build time.
     */
    public static final Set<String> RESERVED_DOMAINS = Set.of(
            "security"  // matches SecurityAuditDomain.NAME in oak-security-spi
    );

    private BridgeAuditDomains() {
        // utility class
    }
}
