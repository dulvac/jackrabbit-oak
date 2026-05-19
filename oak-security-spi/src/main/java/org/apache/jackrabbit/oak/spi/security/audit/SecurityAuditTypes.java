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

/**
 * Stable type-string constants for events in the
 * {@link SecurityAuditDomain security} domain, plus their payload keys.
 * <p>
 * Listeners discriminate among security events by combining
 * {@code event.getDomain().equals(SecurityAuditDomain.NAME)} with
 * {@code event.getType().equals(SecurityAuditTypes.USER_MEMBER_ADDED)}
 * (or another constant defined here). Capture sites in Oak's security
 * modules use the matching factory in {@link SecurityAuditEvents} which
 * references these constants internally.
 * <p>
 * Each {@code USER_*} / {@code ACL_*} / future {@code TOKEN_*} constant
 * is paired with Javadoc describing which {@code PAYLOAD_*} keys its
 * events carry.
 * <p>
 * <strong>Why a class rather than an interface.</strong> Per Effective
 * Java Item 22, constants belong in a {@code public final class} with a
 * private constructor — never in an interface. The older
 * {@code *Constants} interfaces still present in {@code oak-security-spi}
 * (e.g. {@code PrivilegeConstants}) predate that guidance and remain for
 * backward compatibility; this class follows the modern idiom.
 * Consequently, {@link SecurityAuditEvents} references these constants by
 * qualified name ({@code SecurityAuditTypes.USER_MEMBER_ADDED}); there is
 * no {@code implements SecurityAuditTypes} clause and none is expected.
 */
public final class SecurityAuditTypes {

    // ── Type strings ──────────────────────────────────────────────────

    /**
     * Recorded when a single authorizable is added as a member of a group.
     * Payload keys: {@link #PAYLOAD_GROUP_PATH}, {@link #PAYLOAD_MEMBER_PATH}.
     */
    public static final String USER_MEMBER_ADDED = "user.member.added";

    /**
     * Recorded when a single authorizable is removed from a group.
     * Payload keys: {@link #PAYLOAD_GROUP_PATH}, {@link #PAYLOAD_MEMBER_PATH}.
     */
    public static final String USER_MEMBER_REMOVED = "user.member.removed";

    /**
     * Recorded when multiple authorizables are added to a group in a single
     * API call. Payload keys: {@link #PAYLOAD_GROUP_PATH},
     * {@link #PAYLOAD_MEMBER_IDS}, {@link #PAYLOAD_IS_CONTENT_ID},
     * {@link #PAYLOAD_FAILED_IDS}.
     */
    public static final String USER_MEMBERS_ADDED_BULK = "user.members.added.bulk";

    /**
     * Recorded when multiple authorizables are removed from a group in a
     * single API call. Payload keys: same as
     * {@link #USER_MEMBERS_ADDED_BULK}.
     */
    public static final String USER_MEMBERS_REMOVED_BULK = "user.members.removed.bulk";

    // ── Payload keys ──────────────────────────────────────────────────

    /** Group path. Value type: {@code String}. */
    public static final String PAYLOAD_GROUP_PATH = "groupPath";

    /** Member path. Value type: {@code String}. */
    public static final String PAYLOAD_MEMBER_PATH = "memberPath";

    /** Successfully-staged member IDs. Value type: {@code List<String>}. */
    public static final String PAYLOAD_MEMBER_IDS = "memberIds";

    /**
     * {@code true} when {@link #PAYLOAD_MEMBER_IDS} carries content IDs
     * (UUIDs from {@code rep:members}); {@code false} when they are
     * authorizable IDs. Value type: {@code Boolean}.
     */
    public static final String PAYLOAD_IS_CONTENT_ID = "isContentId";

    /**
     * IDs that failed to stage. Value type: {@code List<String>}; may be
     * empty but never null.
     */
    public static final String PAYLOAD_FAILED_IDS = "failedIds";

    private SecurityAuditTypes() {
        // constants
    }
}
