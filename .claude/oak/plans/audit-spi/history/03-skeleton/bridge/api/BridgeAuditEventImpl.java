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

import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * Package-private immutable implementation of {@link BridgeAuditEvent}.
 * Not exported from {@code oak-audit-bridge-api} — callers obtain
 * instances via the factory methods on {@link BridgeAuditEvent} only.
 * <p>
 * Enforces the non-null-value, immutable-payload contract via
 * {@link Map#copyOf(Map)}, which throws {@link NullPointerException} on
 * null keys or values and produces an unmodifiable map.
 */
final class BridgeAuditEventImpl implements BridgeAuditEvent {

    private final String domain;
    private final String type;
    private final long timestamp;
    private final Map<String, Object> payload;

    BridgeAuditEventImpl(@NotNull String domain,
                         @NotNull String type,
                         @NotNull Map<String, Object> payload) {
        this.domain = requireNonBlank(domain, "domain");
        this.type = requireNonBlank(type, "type");
        this.timestamp = System.currentTimeMillis();
        // Map.copyOf rejects null keys and null values, and returns an
        // unmodifiable copy independent of the caller's map.
        this.payload = Map.copyOf(payload);
    }

    @NotNull
    @Override
    public String getDomain() {
        return domain;
    }

    @NotNull
    @Override
    public String getType() {
        return type;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @NotNull
    @Override
    public Map<String, Object> getPayload() {
        return payload;
    }

    @NotNull
    private static String requireNonBlank(@NotNull String s, @NotNull String name) {
        if (s.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return s;
    }
}
