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
package org.apache.jackrabbit.oak.spi.security.user;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.security.audit.SecurityAuditDomain;

/**
 * Stable type-string constants and payload keys for user-management audit
 * events. All events declared here share the
 * {@link SecurityAuditDomain#NAME oak.security} domain.
 * <p>
 * Listener bundles discriminate user-management events by combining
 * {@code event.getDomain().equals(SecurityAuditDomain.NAME)} with
 * {@code event.getType().equals(UserAuditTypes.USER_MEMBER_ADDED)}
 * (or another constant declared here).
 * <p>
 * Each {@code USER_*} constant is paired with Javadoc describing which
 * {@code PAYLOAD_*} keys its events carry. Future sub-domains under
 * {@code oak.security} (ACL, principal, token) declare their own
 * type-string classes alongside their respective configuration packages.
 * <p>
 * <strong>Asymmetric exposure.</strong> This class is the read-side
 * vocabulary; the producer-side factories ({@code UserAuditEvents} in
 * {@code oak-core}) are package-private by design. The partition is
 * defense-in-depth — it raises the bar for casual forging of
 * Oak-user-management events but does not prevent it (an external bundle
 * can still call {@link AuditEvent#of(String, String, java.util.Map)}
 * directly with this domain + a type from this class). Listeners that
 * need to distinguish Oak-attested events from fire-and-forget emissions
 * MUST check the three reserved {@code commit.*} keys in the payload —
 * a reliable signal for events delivered through Oak dispatch; see the
 * trust contract on {@link AuditEvent#getPayload()}.
 */
public final class UserAuditTypes {

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

    private UserAuditTypes() {
        // constants
    }
}
