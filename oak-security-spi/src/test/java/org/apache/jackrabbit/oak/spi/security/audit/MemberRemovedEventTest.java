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

import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MemberRemovedEventTest {

    @Test
    public void factoryCapturesGroupAndMemberPaths() {
        MemberRemovedEvent e = MemberRemovedEvent.of("/g", "/m");
        assertEquals("/g", e.getGroupPath());
        assertEquals("/m", e.getMemberPath());
    }

    @Test
    public void typeIsStable() {
        assertEquals("user.member.removed", MemberRemovedEvent.TYPE);
        MemberRemovedEvent e = MemberRemovedEvent.of("/g", "/m");
        assertEquals(MemberRemovedEvent.TYPE, e.getType());
    }

    @Test
    public void domainIsSecurity() {
        MemberRemovedEvent e = MemberRemovedEvent.of("/g", "/m");
        assertEquals(SecurityAuditDomain.NAME, e.getDomain());
    }

    @Test
    public void payloadHasTwoKeys() {
        MemberRemovedEvent e = MemberRemovedEvent.of("/g", "/m");
        Map<String, Object> payload = e.getPayload();
        assertEquals(2, payload.size());
        assertEquals("/g", payload.get(MemberRemovedEvent.PAYLOAD_GROUP_PATH));
        assertEquals("/m", payload.get(MemberRemovedEvent.PAYLOAD_MEMBER_PATH));
    }
}
