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

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public class SecurityAuditEventsTest {

    private static final String GROUP = "/rep:security/groups/g1";
    private static final String MEMBER = "/rep:security/users/u1";

    //-------------------------------------------------< memberAdded / Removed >---

    @Test
    public void memberAddedReturnsExpectedDomainTypeAndPayload() {
        AuditEvent e = SecurityAuditEvents.memberAdded(GROUP, MEMBER);

        assertNotNull(e);
        assertEquals(SecurityAuditDomain.NAME, e.getDomain());
        assertEquals(SecurityAuditTypes.USER_MEMBER_ADDED, e.getType());
        assertEquals(
                java.util.Map.of(
                        SecurityAuditTypes.PAYLOAD_GROUP_PATH, GROUP,
                        SecurityAuditTypes.PAYLOAD_MEMBER_PATH, MEMBER),
                e.getPayload());
    }

    @Test
    public void memberRemovedReturnsExpectedDomainTypeAndPayload() {
        AuditEvent e = SecurityAuditEvents.memberRemoved(GROUP, MEMBER);

        assertEquals(SecurityAuditDomain.NAME, e.getDomain());
        assertEquals(SecurityAuditTypes.USER_MEMBER_REMOVED, e.getType());
        assertEquals(
                java.util.Map.of(
                        SecurityAuditTypes.PAYLOAD_GROUP_PATH, GROUP,
                        SecurityAuditTypes.PAYLOAD_MEMBER_PATH, MEMBER),
                e.getPayload());
    }

    //---------------------------------------< membersAddedBulk / RemovedBulk >---

    @Test
    public void membersAddedBulkCarriesMemberIdsAndFailedIds() {
        Set<String> members = Set.of("m1", "m2", "m3");
        Set<String> failed = Set.of("bad-id");
        AuditEvent e = SecurityAuditEvents.membersAddedBulk(GROUP, members, false, failed);

        assertEquals(SecurityAuditDomain.NAME, e.getDomain());
        assertEquals(SecurityAuditTypes.USER_MEMBERS_ADDED_BULK, e.getType());
        assertEquals(GROUP, e.getPayload().get(SecurityAuditTypes.PAYLOAD_GROUP_PATH));
        assertEquals(Boolean.FALSE, e.getPayload().get(SecurityAuditTypes.PAYLOAD_IS_CONTENT_ID));

        // Payload uses List<String> (insertion-order, serializer-friendly).
        // Set semantics preserved via Set.copyOf below.
        @SuppressWarnings("unchecked")
        List<String> payloadMembers = (List<String>) e.getPayload().get(SecurityAuditTypes.PAYLOAD_MEMBER_IDS);
        @SuppressWarnings("unchecked")
        List<String> payloadFailed = (List<String>) e.getPayload().get(SecurityAuditTypes.PAYLOAD_FAILED_IDS);
        assertEquals(members, Set.copyOf(payloadMembers));
        assertEquals(failed, Set.copyOf(payloadFailed));
    }

    @Test
    public void membersAddedBulkRespectsIsContentIdFlag() {
        AuditEvent e = SecurityAuditEvents.membersAddedBulk(GROUP, Set.of("uuid"), true, Set.of());
        assertEquals(Boolean.TRUE, e.getPayload().get(SecurityAuditTypes.PAYLOAD_IS_CONTENT_ID));
    }

    @Test
    public void membersAddedBulkSupportsEmptyFailedIds() {
        AuditEvent e = SecurityAuditEvents.membersAddedBulk(GROUP, Set.of("m1"), false, Set.of());
        @SuppressWarnings("unchecked")
        List<String> failed = (List<String>) e.getPayload().get(SecurityAuditTypes.PAYLOAD_FAILED_IDS);
        assertEquals(List.of(), failed);
    }

    @Test
    public void membersAddedBulkRejectsEmptyMemberIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SecurityAuditEvents.membersAddedBulk(GROUP, Set.of(), false, Set.of()));
        assertEquals("memberIds must not be empty", ex.getMessage());
    }

    @Test
    public void membersAddedBulkDecouplesCallerSet() {
        // Mutate the source after the factory call — payload must not reflect it.
        Set<String> mutable = new HashSet<>();
        mutable.add("m1");
        AuditEvent e = SecurityAuditEvents.membersAddedBulk(GROUP, mutable, false, Set.of());
        mutable.add("m2");

        @SuppressWarnings("unchecked")
        List<String> payloadMembers = (List<String>) e.getPayload().get(SecurityAuditTypes.PAYLOAD_MEMBER_IDS);
        assertEquals(List.of("m1"), payloadMembers);
    }

    @Test
    public void membersRemovedBulkCarriesMemberIdsAndFailedIds() {
        Set<String> members = Set.of("m1");
        Set<String> failed = Set.of();
        AuditEvent e = SecurityAuditEvents.membersRemovedBulk(GROUP, members, true, failed);

        assertEquals(SecurityAuditDomain.NAME, e.getDomain());
        assertEquals(SecurityAuditTypes.USER_MEMBERS_REMOVED_BULK, e.getType());
        assertEquals(GROUP, e.getPayload().get(SecurityAuditTypes.PAYLOAD_GROUP_PATH));
        assertEquals(Boolean.TRUE, e.getPayload().get(SecurityAuditTypes.PAYLOAD_IS_CONTENT_ID));

        @SuppressWarnings("unchecked")
        List<String> payloadMembers = (List<String>) e.getPayload().get(SecurityAuditTypes.PAYLOAD_MEMBER_IDS);
        @SuppressWarnings("unchecked")
        List<String> payloadFailed = (List<String>) e.getPayload().get(SecurityAuditTypes.PAYLOAD_FAILED_IDS);
        assertEquals(members, Set.copyOf(payloadMembers));
        assertEquals(List.of(), payloadFailed);
    }

    @Test
    public void membersRemovedBulkRejectsEmptyMemberIds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SecurityAuditEvents.membersRemovedBulk(GROUP, Set.of(), false, Set.of()));
        assertEquals("memberIds must not be empty", ex.getMessage());
    }

    @Test
    public void membersRemovedBulkDecouplesCallerSet() {
        Set<String> mutable = new HashSet<>();
        mutable.add("m1");
        AuditEvent e = SecurityAuditEvents.membersRemovedBulk(GROUP, mutable, false, Set.of());
        mutable.add("m2");

        @SuppressWarnings("unchecked")
        List<String> payloadMembers = (List<String>) e.getPayload().get(SecurityAuditTypes.PAYLOAD_MEMBER_IDS);
        assertEquals(List.of("m1"), payloadMembers);
    }

    //-------------------------------------------------< private constructor >---

    @Test
    public void privateConstructorIsReachableForCoverage() throws Exception {
        Constructor<SecurityAuditEvents> ctor = SecurityAuditEvents.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }
}
