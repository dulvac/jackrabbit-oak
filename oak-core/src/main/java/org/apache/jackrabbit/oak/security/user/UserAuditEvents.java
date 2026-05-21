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
package org.apache.jackrabbit.oak.security.user;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.security.audit.SecurityAuditDomain;
import org.apache.jackrabbit.oak.spi.security.user.UserAuditTypes;
import org.jetbrains.annotations.NotNull;

/**
 * Factory helpers for user-management audit events. Package-private
 * producer-side sugar over {@link AuditEvent#of(String, String, Map)};
 * keeps capture sites in {@link UserManagerImpl} readable.
 * <p>
 * Listener bundles consume {@link AuditEvent} instances and discriminate
 * via {@link AuditEvent#getDomain()} + {@link AuditEvent#getType()} — they
 * compile against {@link UserAuditTypes} for the type-string vocabulary,
 * not against this class.
 * <p>
 * Defensive copies of caller-supplied collections are centralised here so
 * capture sites can pass mutable inputs without leaking them into the
 * resulting {@link AuditEvent}.
 */
final class UserAuditEvents {

    /**
     * Builds an event for a single authorizable added to a group.
     *
     * @param groupPath  non-null path of the group being modified.
     * @param memberPath non-null path of the authorizable added.
     * @return non-null {@link AuditEvent} with type
     *         {@link UserAuditTypes#USER_MEMBER_ADDED}.
     */
    @NotNull
    static AuditEvent memberAdded(@NotNull String groupPath,
                                  @NotNull String memberPath) {
        return AuditEvent.of(
                SecurityAuditDomain.NAME,
                UserAuditTypes.USER_MEMBER_ADDED,
                Map.of(
                        UserAuditTypes.PAYLOAD_GROUP_PATH, groupPath,
                        UserAuditTypes.PAYLOAD_MEMBER_PATH, memberPath));
    }

    /**
     * Builds an event for a single authorizable removed from a group.
     *
     * @param groupPath  non-null path of the group being modified.
     * @param memberPath non-null path of the authorizable removed.
     * @return non-null {@link AuditEvent} with type
     *         {@link UserAuditTypes#USER_MEMBER_REMOVED}.
     */
    @NotNull
    static AuditEvent memberRemoved(@NotNull String groupPath,
                                    @NotNull String memberPath) {
        return AuditEvent.of(
                SecurityAuditDomain.NAME,
                UserAuditTypes.USER_MEMBER_REMOVED,
                Map.of(
                        UserAuditTypes.PAYLOAD_GROUP_PATH, groupPath,
                        UserAuditTypes.PAYLOAD_MEMBER_PATH, memberPath));
    }

    /**
     * Builds an event for multiple authorizables added to a group in a
     * single API call. The {@code memberIds} and {@code failedIds} sets
     * are defensively copied into {@link List#copyOf immutable lists}
     * inside the event payload, so post-construction mutation of the
     * source sets does not leak into the event.
     *
     * @param groupPath   non-null path of the group being modified.
     * @param memberIds   non-empty set of successfully-staged member IDs.
     *                    Defensively copied.
     * @param isContentId {@code true} when {@code memberIds} are content
     *                    IDs (UUIDs from {@code rep:members});
     *                    {@code false} when they are authorizable IDs.
     * @param failedIds   set of IDs that failed to stage. May be empty;
     *                    defensively copied.
     * @return non-null {@link AuditEvent} with type
     *         {@link UserAuditTypes#USER_MEMBERS_ADDED_BULK}.
     * @throws IllegalArgumentException if {@code memberIds} is empty.
     *         Capture sites MUST pre-check {@code memberIds.isEmpty()}
     *         before calling this method; an empty bulk event carries no
     *         semantic meaning, and
     *         {@code UserManagerImpl.recordBulkMembershipAuditEvent}
     *         already enforces this gate at its capture site.
     */
    @NotNull
    static AuditEvent membersAddedBulk(@NotNull String groupPath,
                                       @NotNull Set<String> memberIds,
                                       boolean isContentId,
                                       @NotNull Set<String> failedIds) {
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds must not be empty");
        }
        return AuditEvent.of(
                SecurityAuditDomain.NAME,
                UserAuditTypes.USER_MEMBERS_ADDED_BULK,
                Map.of(
                        UserAuditTypes.PAYLOAD_GROUP_PATH, groupPath,
                        UserAuditTypes.PAYLOAD_MEMBER_IDS, List.copyOf(memberIds),
                        UserAuditTypes.PAYLOAD_IS_CONTENT_ID, isContentId,
                        UserAuditTypes.PAYLOAD_FAILED_IDS, List.copyOf(failedIds)));
    }

    /**
     * Builds an event for multiple authorizables removed from a group in
     * a single API call.
     *
     * @param groupPath   non-null path of the group being modified.
     * @param memberIds   non-empty set of successfully-staged member IDs.
     *                    Defensively copied.
     * @param isContentId {@code true} when {@code memberIds} are content
     *                    IDs (UUIDs from {@code rep:members});
     *                    {@code false} when they are authorizable IDs.
     * @param failedIds   set of IDs that failed to stage. May be empty;
     *                    defensively copied.
     * @return non-null {@link AuditEvent} with type
     *         {@link UserAuditTypes#USER_MEMBERS_REMOVED_BULK}.
     * @throws IllegalArgumentException if {@code memberIds} is empty.
     *         Capture sites MUST pre-check {@code memberIds.isEmpty()}
     *         before calling this method (see {@link #membersAddedBulk}).
     */
    @NotNull
    static AuditEvent membersRemovedBulk(@NotNull String groupPath,
                                         @NotNull Set<String> memberIds,
                                         boolean isContentId,
                                         @NotNull Set<String> failedIds) {
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds must not be empty");
        }
        return AuditEvent.of(
                SecurityAuditDomain.NAME,
                UserAuditTypes.USER_MEMBERS_REMOVED_BULK,
                Map.of(
                        UserAuditTypes.PAYLOAD_GROUP_PATH, groupPath,
                        UserAuditTypes.PAYLOAD_MEMBER_IDS, List.copyOf(memberIds),
                        UserAuditTypes.PAYLOAD_IS_CONTENT_ID, isContentId,
                        UserAuditTypes.PAYLOAD_FAILED_IDS, List.copyOf(failedIds)));
    }

    private UserAuditEvents() {
        // utility
    }
}
