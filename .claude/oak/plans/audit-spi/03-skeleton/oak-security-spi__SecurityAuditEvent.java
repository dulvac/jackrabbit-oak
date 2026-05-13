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

import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for all events in the {@link SecurityAuditDomain security}
 * domain. Concrete subclasses pin the {@link #getType()} value and expose
 * typed accessors over the payload map.
 * <p>
 * Instances are immutable. The timestamp is captured in the constructor
 * (i.e. at the API call site, not at dispatch).
 */
public abstract class SecurityAuditEvent implements AuditEvent {

    private final String type;
    private final long timestamp;

    /**
     * @param type the event type identifier, non-null. Stable across
     *             releases for the security domain.
     */
    protected SecurityAuditEvent(@NotNull String type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    @NotNull
    @Override
    public final String getDomain() {
        return SecurityAuditDomain.NAME;
    }

    @NotNull
    @Override
    public final String getType() {
        return type;
    }

    @Override
    public final long getTimestamp() {
        return timestamp;
    }
}
