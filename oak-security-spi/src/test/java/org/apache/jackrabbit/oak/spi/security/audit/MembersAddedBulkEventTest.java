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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MembersAddedBulkEventTest {

    @Test
    public void factoryCapturesAllFields() {
        Set<String> members = Set.of("m1", "m2");
        Set<String> failed = Set.of("f1");
        MembersAddedBulkEvent e = MembersAddedBulkEvent.of("/g", members, true, failed);
        assertEquals("/g", e.getGroupPath());
        assertEquals(members, e.getMemberIds());
        assertEquals(failed, e.getFailedIds());
        assertTrue(e.isContentId());
    }

    @Test
    public void typeIsStable() {
        assertEquals("user.members.added.bulk", MembersAddedBulkEvent.TYPE);
        MembersAddedBulkEvent e = MembersAddedBulkEvent.of("/g", Set.of("x"), false, Collections.emptySet());
        assertEquals(MembersAddedBulkEvent.TYPE, e.getType());
    }

    @Test
    public void domainIsSecurity() {
        MembersAddedBulkEvent e = MembersAddedBulkEvent.of("/g", Set.of("x"), false, Collections.emptySet());
        assertEquals(SecurityAuditDomain.NAME, e.getDomain());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void payloadHasFourKeysWithListValues() {
        MembersAddedBulkEvent e = MembersAddedBulkEvent.of("/g", Set.of("m1", "m2"), false, Set.of("f1"));
        Map<String, Object> payload = e.getPayload();
        assertEquals(4, payload.size());
        assertEquals("/g", payload.get(MembersAddedBulkEvent.PAYLOAD_GROUP_PATH));
        assertEquals(Boolean.FALSE, payload.get(MembersAddedBulkEvent.PAYLOAD_IS_CONTENT_ID));
        List<String> memberIds = (List<String>) payload.get(MembersAddedBulkEvent.PAYLOAD_MEMBER_IDS);
        List<String> failedIds = (List<String>) payload.get(MembersAddedBulkEvent.PAYLOAD_FAILED_IDS);
        assertEquals(2, memberIds.size());
        assertEquals(1, failedIds.size());
        assertTrue(memberIds.containsAll(Set.of("m1", "m2")));
        assertTrue(failedIds.contains("f1"));
    }

    @Test
    public void emptyMemberIdsThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> MembersAddedBulkEvent.of("/g", Collections.emptySet(), true, Collections.emptySet()));
    }

    @Test
    public void emptyFailedIdsIsAllowed() {
        MembersAddedBulkEvent e = MembersAddedBulkEvent.of("/g", Set.of("m1"), true, Collections.emptySet());
        assertTrue(e.getFailedIds().isEmpty());
    }

    @Test
    public void isContentIdFalseAlsoCovered() {
        MembersAddedBulkEvent e = MembersAddedBulkEvent.of("/g", Set.of("m1"), false, Collections.emptySet());
        assertFalse(e.isContentId());
    }
}
