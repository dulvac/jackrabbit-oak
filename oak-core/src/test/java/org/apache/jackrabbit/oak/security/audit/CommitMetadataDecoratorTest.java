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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        AuditEvent in = original("oak.security", "member.added", Map.of("group", "/g", "member", "/m"));
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
        // Semantic check, not identity check — couples to behavior, not impl.
        assertTrue(CommitMetadataDecorator.decorate(Collections.emptyList(), info).isEmpty());
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
        AuditEvent in = original("oak.security", "system.event", Map.of());
        // CommitInfo with null userId resolves to OAK_UNKNOWN
        CommitInfo info = CommitInfo.EMPTY;
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        // CommitInfo.OAK_UNKNOWN exposed via getUserId() for empty/system commits
        assertEquals(CommitInfo.OAK_UNKNOWN, decorated.getPayload().get("commit.userId"));
    }

    @Test
    public void preservesOrderOfEvents() {
        AuditEvent a = original("oak.security", "a", Map.of());
        AuditEvent b = original("oak.security", "b", Map.of());
        AuditEvent c = original("oak.security", "c", Map.of());
        CommitInfo info = new CommitInfo("s", "u", Map.of(), false);
        List<AuditEvent> out = CommitMetadataDecorator.decorate(asList(a, b, c), info);
        assertEquals("a", out.get(0).getType());
        assertEquals("b", out.get(1).getType());
        assertEquals("c", out.get(2).getType());
    }

    //--------------------------------------------< overwrite invariant tests >---
    // Security-critical regression guards. The "commit.* keys are present
    // iff the event came from Oak's commit-attached pipeline" trust-model
    // property (see audit-design.md §0 trust contract / §3 commit.* signal)
    // rests on the decorator UNCONDITIONALLY overwriting any caller-supplied
    // commit.* key. A future refactor that swaps .put() for .putIfAbsent() /
    // contains-check would silently let bundles spoof commit identity in
    // audit logs. Each test pins one independent property a single-line
    // regression could break.

    @Test
    public void decoratorOverwritesCallerProvidedSessionId() {
        AuditEvent in = original("oak.security", "x",
                Map.of(CommitMetadataDecorator.KEY_SESSION_ID, "spoofed-session"));
        CommitInfo info = new CommitInfo("real-session", "alice", Map.of(), false);
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        assertEquals("real-session", decorated.getPayload().get(CommitMetadataDecorator.KEY_SESSION_ID));
        assertNotEquals("spoofed value must not survive decoration",
                "spoofed-session", decorated.getPayload().get(CommitMetadataDecorator.KEY_SESSION_ID));
    }

    @Test
    public void decoratorOverwritesCallerProvidedUserId() {
        AuditEvent in = original("oak.security", "x",
                Map.of(CommitMetadataDecorator.KEY_USER_ID, "admin"));
        CommitInfo info = new CommitInfo("s", "alice", Map.of(), false);
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        assertEquals("alice", decorated.getPayload().get(CommitMetadataDecorator.KEY_USER_ID));
        assertNotEquals("spoofed userId must not survive decoration",
                "admin", decorated.getPayload().get(CommitMetadataDecorator.KEY_USER_ID));
    }

    @Test
    public void decoratorOverwritesCallerProvidedTimestamp() {
        AuditEvent in = original("oak.security", "x",
                Map.of(CommitMetadataDecorator.KEY_TIMESTAMP, 99999999L));
        // CommitInfo's date is set internally to System.currentTimeMillis()
        // at construction; we read the actual value via getDate() to
        // compare against the decorated payload.
        CommitInfo info = new CommitInfo("s", "alice", Map.of(), false);
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        assertEquals(info.getDate(), decorated.getPayload().get(CommitMetadataDecorator.KEY_TIMESTAMP));
        assertNotEquals("spoofed timestamp must not survive decoration",
                99999999L, decorated.getPayload().get(CommitMetadataDecorator.KEY_TIMESTAMP));
    }

    /**
     * Type-variance check: caller submits {@code commit.timestamp} as a
     * {@code String}, decorator overwrites with the real {@code long}.
     * Guards against future "type-aware merge" pseudo-smartening that
     * would skip the overwrite when types differ.
     */
    @Test
    public void decoratorOverwritesAcrossValueTypes() {
        AuditEvent in = original("oak.security", "x",
                Map.of(CommitMetadataDecorator.KEY_TIMESTAMP, "definitely-a-string-not-a-long"));
        CommitInfo info = new CommitInfo("s", "u", Map.of(), false);
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        Object timestamp = decorated.getPayload().get(CommitMetadataDecorator.KEY_TIMESTAMP);
        // CommitInfo.getDate() returns long; merged.put(..., commitTimestamp)
        // auto-boxes to Long. The original String value is gone.
        assertTrue("timestamp must be Long, not the caller-provided String",
                timestamp instanceof Long);
    }

    /**
     * Symmetric to the overwrite tests: when the input payload omits the
     * commit.* keys entirely, the decorator ADDS them. A regression that
     * turned {@code .put()} into {@code if (containsKey) .put()} would
     * pass the overwrite-when-present tests but break add-when-absent.
     * Mixed coverage with {@link #decoratesPayloadWithCommitMetadata()}
     * is insufficient — that test conflates the add-when-absent half
     * with non-commit-key preservation.
     */
    @Test
    public void decoratorAddsCommitKeysWhenAbsent() {
        AuditEvent in = original("oak.security", "x", Map.of());
        CommitInfo info = new CommitInfo("s", "u", Map.of(), false);
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        assertTrue(decorated.getPayload().containsKey(CommitMetadataDecorator.KEY_SESSION_ID));
        assertTrue(decorated.getPayload().containsKey(CommitMetadataDecorator.KEY_USER_ID));
        assertTrue(decorated.getPayload().containsKey(CommitMetadataDecorator.KEY_TIMESTAMP));
        assertEquals("s", decorated.getPayload().get(CommitMetadataDecorator.KEY_SESSION_ID));
        assertEquals("u", decorated.getPayload().get(CommitMetadataDecorator.KEY_USER_ID));
    }

    @Test
    public void decoratedPayloadIsUnmodifiable() {
        AuditEvent in = original("oak.security", "x", Map.of("k", "v"));
        CommitInfo info = new CommitInfo("s", "u", Map.of(), false);
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        try {
            decorated.getPayload().put("newkey", "newvalue");
            fail("decorated payload must be unmodifiable — caller mutation must throw");
        } catch (UnsupportedOperationException expected) {
            // expected — Collections.unmodifiableMap wrapping
        }
    }
}
