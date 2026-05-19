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
import java.util.List;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class SecurityAuditTypesTest {

    @Test
    public void allTypeStringsAreNonBlank() {
        for (String value : typeStrings()) {
            assertFalse("type string must not be blank: " + value, value.isBlank());
        }
    }

    @Test
    public void allPayloadKeysAreNonBlank() {
        for (String value : payloadKeys()) {
            assertFalse("payload key must not be blank: " + value, value.isBlank());
        }
    }

    @Test
    public void typeStringsAreUnique() {
        List<String> values = typeStrings();
        assertEquals("type strings must be unique",
                values.size(), Set.copyOf(values).size());
    }

    @Test
    public void payloadKeysAreUnique() {
        List<String> values = payloadKeys();
        assertEquals("payload keys must be unique",
                values.size(), Set.copyOf(values).size());
    }

    @Test
    public void privateConstructorIsReachableForCoverage() throws Exception {
        // Constants-only class: private constructor guards against
        // accidental instantiation; reflection-invoked for line coverage.
        Constructor<SecurityAuditTypes> ctor = SecurityAuditTypes.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    /**
     * The four published type strings. Update this list when adding
     * new {@code USER_*} / {@code ACL_*} / etc. constants.
     */
    private static List<String> typeStrings() {
        return List.of(
                SecurityAuditTypes.USER_MEMBER_ADDED,
                SecurityAuditTypes.USER_MEMBER_REMOVED,
                SecurityAuditTypes.USER_MEMBERS_ADDED_BULK,
                SecurityAuditTypes.USER_MEMBERS_REMOVED_BULK);
    }

    /**
     * The five published payload keys.
     */
    private static List<String> payloadKeys() {
        return List.of(
                SecurityAuditTypes.PAYLOAD_GROUP_PATH,
                SecurityAuditTypes.PAYLOAD_MEMBER_PATH,
                SecurityAuditTypes.PAYLOAD_MEMBER_IDS,
                SecurityAuditTypes.PAYLOAD_IS_CONTENT_ID,
                SecurityAuditTypes.PAYLOAD_FAILED_IDS);
    }
}
