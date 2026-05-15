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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

/**
 * Bulk audit event recorded when multiple authorizables are added as
 * members of a group in a single API call. Recorded at the API call site
 * in the bulk overload of
 * {@code org.apache.jackrabbit.oak.security.user.UserManagerImpl#onGroupUpdate}
 * <em>after</em> the in-memory writes succeeded but <em>before</em>
 * {@code Root.commit()} returns.
 * <p>
 * The event carries both {@link #getMemberIds() the successfully-staged
 * member IDs} (non-empty) and {@link #getFailedIds() the IDs that failed
 * to stage} (possibly empty) — distinct keys, so listeners can audit
 * both outcomes without risk of mis-attribution.
 * <p>
 * <strong>Failure-layer scope:</strong> {@code failedIds} reflects only
 * partial-failure outcomes at the staging layer (i.e. entries that
 * {@code MembershipWriter.addMembers} could not stage — invalid IDs,
 * cyclic membership, etc.). It does <em>not</em> include downstream
 * validator rejections: a validator failure rolls back the entire
 * commit, the audit event is dropped via
 * {@code AuditBufferLifecycle.onCommitFailed}, and no audit entry is
 * emitted. Readers must not interpret an empty {@code failedIds} as
 * "all validators passed" — only as "all entries were stageable".
 * <p>
 * Member IDs may be either content IDs (UUIDs from {@code rep:members})
 * or authorizable IDs (user/group {@code rep:authorizableId} values) —
 * the distinction is exposed via {@link #isContentId()} (name kept in
 * sync with the {@code isContentId} parameter at the
 * {@code UserManagerImpl.onGroupUpdate} capture site). Listeners that
 * want paths resolve them by themselves (typically cheaper to do
 * outside the commit hot-path).
 * <p>
 * Payload-map values use {@code List<String>} (not {@code Set<String>})
 * for serializer-friendliness; the typed {@link #getMemberIds()} and
 * {@link #getFailedIds()} accessors return immutable sets for callers
 * that prefer the set semantics. The acting user id is not part of the
 * event payload — listeners read it from {@code commitInfo.getUserId()}.
 */
public final class MembersAddedBulkEvent extends SecurityAuditEvent {

    /**
     * Event type identifier for this class. Stable across releases.
     */
    public static final String TYPE = "user.members.added.bulk";

    /**
     * Payload key for the group path. Value type: {@code String}.
     */
    public static final String PAYLOAD_GROUP_PATH = "groupPath";

    /**
     * Payload key for the successfully-staged member IDs.
     * Value type: {@code List<String>} (insertion-ordered immutable copy
     * of the input set).
     */
    public static final String PAYLOAD_MEMBER_IDS = "memberIds";

    /**
     * Payload key for the {@code isContentId} flag — preserves the
     * source-side parameter name on
     * {@code UserManagerImpl.onGroupUpdate}. Value type: {@code Boolean}.
     */
    public static final String PAYLOAD_IS_CONTENT_ID = "isContentId";

    /**
     * Payload key for the IDs that failed to stage. Value type:
     * {@code List<String>} (insertion-ordered immutable copy of the
     * input set; may be empty).
     */
    public static final String PAYLOAD_FAILED_IDS = "failedIds";

    private final String groupPath;
    private final Set<String> memberIds;
    private final boolean isContentId;
    private final Set<String> failedIds;
    private final Map<String, Object> payload;

    private MembersAddedBulkEvent(@NotNull String groupPath,
                                  @NotNull Set<String> memberIds,
                                  boolean isContentId,
                                  @NotNull Set<String> failedIds) {
        super(TYPE);
        this.groupPath = groupPath;
        this.memberIds = Set.copyOf(memberIds);
        this.isContentId = isContentId;
        this.failedIds = Set.copyOf(failedIds);
        // Payload uses List.copyOf — preserves caller iteration order
        // and is friendlier to JSON / log serializers than Set.
        this.payload = Map.of(
                PAYLOAD_GROUP_PATH, groupPath,
                PAYLOAD_MEMBER_IDS, List.copyOf(memberIds),
                PAYLOAD_IS_CONTENT_ID, isContentId,
                PAYLOAD_FAILED_IDS, List.copyOf(failedIds));
    }

    /**
     * Factory method.
     *
     * @param groupPath  path of the group being modified, non-null.
     * @param memberIds  non-empty set of successfully-staged member
     *                   IDs. Defensively copied — caller may mutate
     *                   their copy afterwards.
     * @param isContentId {@code true} when {@code memberIds} are
     *                    content IDs (UUIDs from {@code rep:members});
     *                    {@code false} when they are authorizable IDs.
     * @param failedIds   set of IDs that failed to stage. May be empty.
     *                    Defensively copied.
     * @return non-null event instance.
     * @throws IllegalArgumentException if {@code memberIds} is empty.
     */
    @NotNull
    public static MembersAddedBulkEvent of(@NotNull String groupPath,
                                           @NotNull Set<String> memberIds,
                                           boolean isContentId,
                                           @NotNull Set<String> failedIds) {
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds must not be empty");
        }
        return new MembersAddedBulkEvent(groupPath, memberIds, isContentId, failedIds);
    }

    /**
     * @return path of the group being modified, non-null.
     */
    @NotNull
    public String getGroupPath() {
        return groupPath;
    }

    /**
     * @return non-empty, immutable set of successfully-staged member IDs.
     */
    @NotNull
    public Set<String> getMemberIds() {
        return memberIds;
    }

    /**
     * @return {@code true} when {@link #getMemberIds()} contains
     *         content IDs; {@code false} for authorizable IDs.
     */
    public boolean isContentId() {
        return isContentId;
    }

    /**
     * @return immutable set of IDs that failed to stage. May be empty
     *         (no failures) but never null.
     */
    @NotNull
    public Set<String> getFailedIds() {
        return failedIds;
    }

    @NotNull
    @Override
    public Map<String, Object> getPayload() {
        return payload;
    }
}
