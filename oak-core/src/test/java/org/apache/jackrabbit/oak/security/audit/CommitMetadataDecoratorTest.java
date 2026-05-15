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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CommitMetadataDecoratorTest {

    private static AuditEvent original(@NotNull String domain, @NotNull String type, @NotNull Map<String, Object> payload) {
        return new AuditEvent() {
            @Override public @NotNull String getDomain() { return domain; }
            @Override public @NotNull String getType() { return type; }
            @Override public long getTimestamp() { return 12345L; }
            @Override public @NotNull Map<String, Object> getPayload() { return payload; }
        };
    }

    @Test
    public void decoratesPayloadWithCommitMetadata() {
        AuditEvent in = original("security", "member.added", Map.of("group", "/g", "member", "/m"));
        CommitInfo info = new CommitInfo("session-1", "alice", Map.of(), false);
        List<AuditEvent> out = CommitMetadataDecorator.decorate(List.of(in), info);
        assertEquals(1, out.size());
        AuditEvent decorated = out.get(0);
        Map<String, Object> p = decorated.getPayload();
        assertEquals("session-1", p.get("commit.sessionId"));
        assertEquals("alice", p.get("commit.userId"));
        assertTrue(p.containsKey("commit.timestamp"));
        // original payload entries preserved
        assertEquals("/g", p.get("group"));
        assertEquals("/m", p.get("member"));
        // original event passed in not mutated
        assertEquals(Map.of("group", "/g", "member", "/m"), in.getPayload());
        assertNotSame(in, decorated);
    }

    @Test
    public void emptyEventsReturnsEmpty() {
        CommitInfo info = new CommitInfo("session-1", "alice", Map.of(), false);
        assertSame(Collections.<AuditEvent>emptyList(), CommitMetadataDecorator.decorate(Collections.emptyList(), info));
    }

    @Test
    public void preservesDomainTypeTimestamp() {
        AuditEvent in = original("aem.content", "fragment.published", Map.of("path", "/p"));
        CommitInfo info = new CommitInfo("s", "u", Map.of(), false);
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        assertEquals("aem.content", decorated.getDomain());
        assertEquals("fragment.published", decorated.getType());
        assertEquals(12345L, decorated.getTimestamp());
    }

    @Test
    public void systemCommitUserIdIsOakUnknown() {
        AuditEvent in = original("security", "system.event", Map.of());
        // CommitInfo with null userId resolves to OAK_UNKNOWN
        CommitInfo info = CommitInfo.EMPTY;
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        // CommitInfo.OAK_UNKNOWN exposed via getUserId() for empty/system commits
        assertEquals(CommitInfo.OAK_UNKNOWN, decorated.getPayload().get("commit.userId"));
    }

    @Test
    public void preservesOrderOfEvents() {
        AuditEvent a = original("security", "a", Map.of());
        AuditEvent b = original("security", "b", Map.of());
        AuditEvent c = original("security", "c", Map.of());
        CommitInfo info = new CommitInfo("s", "u", Map.of(), false);
        List<AuditEvent> out = CommitMetadataDecorator.decorate(asList(a, b, c), info);
        assertEquals("a", out.get(0).getType());
        assertEquals("b", out.get(1).getType());
        assertEquals("c", out.get(2).getType());
    }
}
