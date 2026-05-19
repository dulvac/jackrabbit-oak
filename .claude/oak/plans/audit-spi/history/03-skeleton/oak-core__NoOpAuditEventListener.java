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

import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.security.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.security.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.security.audit.SecurityAuditDomain;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-tree no-op listener for the {@link SecurityAuditDomain security}
 * domain. Emits a {@code TRACE} log line per delivered event; primarily
 * useful for development and tests, and as a reference example of
 * implementing {@link AuditEventListener}.
 * <p>
 * Not auto-registered. Consumers wire their own listener via OSGi /
 * the Whiteboard.
 */
final class NoOpAuditEventListener implements AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(NoOpAuditEventListener.class);

    @NotNull
    @Override
    public String getDomain() {
        return SecurityAuditDomain.NAME;
    }

    @Override
    public void onCommit(@NotNull NodeState after,
                         @NotNull CommitInfo commitInfo,
                         @NotNull List<AuditEvent> events) {
        if (!log.isTraceEnabled()) {
            return;
        }
        for (AuditEvent event : events) {
            log.trace("audit event sessionId={} userId={} type={} payload={}",
                    commitInfo.getSessionId(), commitInfo.getUserId(),
                    event.getType(), event.getPayload());
        }
    }
}
