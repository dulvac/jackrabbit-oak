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

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.jackrabbit.oak.spi.security.audit.SecurityAuditDomain.NAME;

/**
 * Reference listener used in tests and as a fallback when no production
 * listener is registered. Logs at TRACE level only; payloads are not
 * logged at higher levels by default.
 */
public final class NoOpAuditEventListener implements AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(NoOpAuditEventListener.class);

    @NotNull
    @Override
    public String getDomain() {
        return NAME;
    }

    @Override
    public void onEvents(@NotNull List<AuditEvent> events) {
        if (log.isTraceEnabled()) {
            for (AuditEvent event : events) {
                log.trace("noop audit listener: domain={} type={} payload-keys={}",
                        event.getDomain(), event.getType(), event.getPayload().keySet());
            }
        }
    }
}
