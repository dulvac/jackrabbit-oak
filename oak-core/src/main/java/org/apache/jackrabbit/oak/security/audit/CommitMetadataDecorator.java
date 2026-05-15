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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Decorates audit-event payloads with commit metadata (sessionId, userId,
 * timestamp) at drain time. Used exclusively by the commit-attached
 * dispatch path. Fire-and-forget events bypass this decoration.
 * <p>
 * The decorator returns NEW {@link AuditEvent} instances that wrap the
 * originals; the input events are not mutated. The wrapper's payload map
 * is unmodifiable.
 */
final class CommitMetadataDecorator {

    static final String KEY_SESSION_ID = "commit.sessionId";
    static final String KEY_USER_ID = "commit.userId";
    static final String KEY_TIMESTAMP = "commit.timestamp";

    private CommitMetadataDecorator() {
        // utility class
    }

    static @NotNull List<AuditEvent> decorate(@NotNull List<AuditEvent> events,
                                              @NotNull CommitInfo info) {
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        String sessionId = info.getSessionId();
        String userId = info.getUserId();
        long timestamp = info.getDate();
        List<AuditEvent> out = new ArrayList<>(events.size());
        for (AuditEvent e : events) {
            out.add(new DecoratedAuditEvent(e, sessionId, userId, timestamp));
        }
        return out;
    }

    private static final class DecoratedAuditEvent implements AuditEvent {

        private final AuditEvent delegate;
        private final Map<String, Object> payload;

        DecoratedAuditEvent(@NotNull AuditEvent delegate,
                            @NotNull String sessionId,
                            @NotNull String userId,
                            long commitTimestamp) {
            this.delegate = delegate;
            Map<String, Object> merged = new HashMap<>(delegate.getPayload());
            merged.put(KEY_SESSION_ID, sessionId);
            merged.put(KEY_USER_ID, userId);
            merged.put(KEY_TIMESTAMP, commitTimestamp);
            this.payload = Collections.unmodifiableMap(merged);
        }

        @Override public @NotNull String getDomain() { return delegate.getDomain(); }
        @Override public @NotNull String getType() { return delegate.getType(); }
        @Override public long getTimestamp() { return delegate.getTimestamp(); }
        @Override public @NotNull Map<String, Object> getPayload() { return payload; }
    }
}
