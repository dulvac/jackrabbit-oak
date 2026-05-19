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
package com.adobe.aem.content.fragments.audit;

import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.oak.audit.bridge.AuditEventEmitter;
import org.apache.jackrabbit.oak.audit.bridge.BridgeAuditEvent;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Illustrative example of how an AEM content-fragment service uses the
 * Oak audit bridge. NOT shipped with Oak — lives in AEM's bundle. The
 * bundle's POM depends ONLY on {@code oak-audit-bridge-api}; it does
 * NOT depend on {@code oak-security-spi}, {@code oak-core}, or
 * {@code oak-api}.
 * <p>
 * The {@code oak-audit-bridge-api} module brings in only:
 * <ul>
 *   <li>{@code javax.jcr} (already a transitive AEM dep)</li>
 *   <li>OSGi annotations</li>
 *   <li>JetBrains annotations</li>
 * </ul>
 * <p>
 * <strong>Domains AEM may emit on:</strong> any domain NOT in
 * {@code BridgeAuditDomains.RESERVED_DOMAINS}. The currently reserved
 * set is {@code {"security"}} — Oak owns that taxonomy and emits
 * security-domain events from its own internal capture sites. AEM
 * SUBSCRIBES to security events via {@code AuditEventListener}
 * implementations (separate concern; not covered in this file).
 * <p>
 * <strong>Non-commit signals (login failure, anomaly detection):</strong>
 * Not handled through this bridge — v1 is commit-attached only. Use
 * Sling EventAdmin for those, or wait for the v1.1+ Monitor SPI
 * extensions (see {@code 07-bridge-design.md} §7.1).
 */
@Component(service = ContentFragmentAuditor.class, immediate = true)
public class ContentFragmentAuditor {

    @Reference
    private AuditEventEmitter audit;

    /**
     * Called by the content-fragment editor when a fragment is
     * published. The {@code ResourceResolver} backing the editor adapts
     * to a JCR {@link Session}; the audit emission happens BEFORE the
     * {@code resolver.commit()} (or {@code session.save()}) that
     * follows — so the event piggybacks on that commit and is delivered
     * atomically with it.
     * <p>
     * AEM-domain events use a dotted-lowercase domain that does NOT
     * collide with Oak's reserved set.
     */
    public void onFragmentPublished(ResourceResolver resolver, String fragmentPath)
            throws RepositoryException {
        if (audit.isEnabledFor("aem.content")) {
            Session session = resolver.adaptTo(Session.class);
            audit.recordOnCommit(session,
                    BridgeAuditEvent.of("aem.content", "FragmentPublished",
                            Map.of("path", fragmentPath,
                                   "variation", "master")));
        }
        // ... existing fragment-publish logic. resolver.commit() follows;
        // the event is delivered atomically with the commit.
    }

    /**
     * Replication activation example. Same pattern — commit-attached
     * AEM-domain event, dispatched on commit success.
     */
    public void onReplicationActivated(ResourceResolver resolver, String resourcePath,
                                       String agentId)
            throws RepositoryException {
        if (audit.isEnabledFor("aem.replication")) {
            Session session = resolver.adaptTo(Session.class);
            audit.recordOnCommit(session,
                    BridgeAuditEvent.of("aem.replication", "Activated",
                            Map.of("path", resourcePath,
                                   "agentId", agentId)));
        }
    }

    // INTENTIONALLY NOT SHOWN: a "FragmentValidationFailed" or
    // "LoginFailure" example. Those are non-commit signals. v1's
    // commit-attached-only emitter does not handle them; the
    // current escape hatch is Sling EventAdmin. See bridge-design
    // §2 and §7.1 for the rationale and v1.1+ roadmap.
}
