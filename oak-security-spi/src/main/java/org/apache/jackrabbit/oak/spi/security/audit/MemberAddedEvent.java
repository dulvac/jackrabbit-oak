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

import org.jetbrains.annotations.NotNull;

/**
 * Audit event recorded when an authorizable is added as a member of a
 * group. Recorded at the API call site in
 * {@code org.apache.jackrabbit.oak.security.user.UserManagerImpl#onGroupUpdate}
 * <em>after</em> the in-memory write succeeded but <em>before</em>
 * {@code Root.commit()} returns.
 * <p>
 * The acting user id is not part of the event's own payload — listeners
 * read {@code commit.userId} from the decorated payload Map (added by
 * Oak's commit-attached drain hook); the value is always defined
 * ({@code CommitInfo.OAK_UNKNOWN} for system commits).
 */
public final class MemberAddedEvent extends SecurityAuditEvent {

    /**
     * Event type identifier for this class. Stable across releases.
     */
    public static final String TYPE = "user.member.added";

    /**
     * Payload key for the group path.
     */
    public static final String PAYLOAD_GROUP_PATH = "groupPath";

    /**
     * Payload key for the member path.
     */
    public static final String PAYLOAD_MEMBER_PATH = "memberPath";

    private final Map<String, Object> payload;

    private MemberAddedEvent(@NotNull String groupPath, @NotNull String memberPath) {
        super(TYPE);
        this.payload = Map.of(
                PAYLOAD_GROUP_PATH, groupPath,
                PAYLOAD_MEMBER_PATH, memberPath);
    }

    /**
     * Factory method capturing the two fields needed to describe a
     * member-added event.
     *
     * @param groupPath  path of the group being modified, non-null.
     * @param memberPath path of the authorizable added as a member,
     *                   non-null.
     * @return non-null event instance.
     */
    @NotNull
    public static MemberAddedEvent of(@NotNull String groupPath, @NotNull String memberPath) {
        return new MemberAddedEvent(groupPath, memberPath);
    }

    /**
     * @return path of the group being modified, non-null.
     */
    @NotNull
    public String getGroupPath() {
        return (String) payload.get(PAYLOAD_GROUP_PATH);
    }

    /**
     * @return path of the authorizable added as a member, non-null.
     */
    @NotNull
    public String getMemberPath() {
        return (String) payload.get(PAYLOAD_MEMBER_PATH);
    }

    @NotNull
    @Override
    public Map<String, Object> getPayload() {
        return payload;
    }
}
