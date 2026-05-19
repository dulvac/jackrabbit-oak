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

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Factory helpers for security-domain audit events. Convenience layer over
 * {@link AuditEvent#of(String, String, Map) AuditEvent.of} that
 * centralises type-string and payload-key references for capture sites
 * in Oak's security modules.
 * <p>
 * <strong>Internal organisation, not SPI.</strong> Consumers (listeners,
 * downstream tooling) should NOT depend on the method shapes exposed
 * here — they receive plain {@link AuditEvent} instances and discriminate
 * via {@link AuditEvent#getDomain()} + {@link AuditEvent#getType()}.
 * <p>
 * <strong>Convention for other audit-event consumers.</strong> ACL,
 * principal, token, and other future audit consumers in Oak's security
 * stack MAY follow this pattern, providing their own per-domain helper
 * class that returns the bare {@link AuditEvent} interface. The
 * convention is informal in v1; when a second domain implements such a
 * helper it should be codified in the {@code oak-audit-spi} package
 * Javadoc.
 * <p>
 * Each factory method assembles an immutable payload Map using
 * {@link Map#of} and {@link List#copyOf} / {@link Set#copyOf} for
 * collection values — this preserves the per-value defensive-copy
 * discipline that the deleted typed event classes enforced internally,
 * and concentrates that discipline in one file.
 */
public final class SecurityAuditEvents {

    /**
     * Builds an event for a single authorizable added to a group.
     *
     * @param groupPath  non-null path of the group being modified.
     * @param memberPath non-null path of the authorizable added.
     * @return non-null {@link AuditEvent} with type
     *         {@link SecurityAuditTypes#USER_MEMBER_ADDED}.
     */
    @NotNull
    public static AuditEvent memberAdded(@NotNull String groupPath,
                                         @NotNull String memberPath) {
        return AuditEvent.of(
                SecurityAuditDomain.NAME,
                SecurityAuditTypes.USER_MEMBER_ADDED,
                Map.of(
                        SecurityAuditTypes.PAYLOAD_GROUP_PATH, groupPath,
                        SecurityAuditTypes.PAYLOAD_MEMBER_PATH, memberPath));
    }

    /**
     * Builds an event for a single authorizable removed from a group.
     *
     * @param groupPath  non-null path of the group being modified.
     * @param memberPath non-null path of the authorizable removed.
     * @return non-null {@link AuditEvent} with type
     *         {@link SecurityAuditTypes#USER_MEMBER_REMOVED}.
     */
    @NotNull
    public static AuditEvent memberRemoved(@NotNull String groupPath,
                                           @NotNull String memberPath) {
        return AuditEvent.of(
                SecurityAuditDomain.NAME,
                SecurityAuditTypes.USER_MEMBER_REMOVED,
                Map.of(
                        SecurityAuditTypes.PAYLOAD_GROUP_PATH, groupPath,
                        SecurityAuditTypes.PAYLOAD_MEMBER_PATH, memberPath));
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
     *         {@link SecurityAuditTypes#USER_MEMBERS_ADDED_BULK}.
     * @throws IllegalArgumentException if {@code memberIds} is empty.
     *         Capture sites MUST pre-check {@code memberIds.isEmpty()}
     *         before calling this method; an empty bulk event carries no
     *         semantic meaning, and
     *         {@code UserManagerImpl.recordBulkMembershipAuditEvent}
     *         already enforces this gate at its capture site.
     */
    @NotNull
    public static AuditEvent membersAddedBulk(@NotNull String groupPath,
                                              @NotNull Set<String> memberIds,
                                              boolean isContentId,
                                              @NotNull Set<String> failedIds) {
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds must not be empty");
        }
        return AuditEvent.of(
                SecurityAuditDomain.NAME,
                SecurityAuditTypes.USER_MEMBERS_ADDED_BULK,
                Map.of(
                        SecurityAuditTypes.PAYLOAD_GROUP_PATH, groupPath,
                        SecurityAuditTypes.PAYLOAD_MEMBER_IDS, List.copyOf(memberIds),
                        SecurityAuditTypes.PAYLOAD_IS_CONTENT_ID, isContentId,
                        SecurityAuditTypes.PAYLOAD_FAILED_IDS, List.copyOf(failedIds)));
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
     *         {@link SecurityAuditTypes#USER_MEMBERS_REMOVED_BULK}.
     * @throws IllegalArgumentException if {@code memberIds} is empty.
     *         Capture sites MUST pre-check {@code memberIds.isEmpty()}
     *         before calling this method (see {@link #membersAddedBulk}).
     */
    @NotNull
    public static AuditEvent membersRemovedBulk(@NotNull String groupPath,
                                                @NotNull Set<String> memberIds,
                                                boolean isContentId,
                                                @NotNull Set<String> failedIds) {
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds must not be empty");
        }
        return AuditEvent.of(
                SecurityAuditDomain.NAME,
                SecurityAuditTypes.USER_MEMBERS_REMOVED_BULK,
                Map.of(
                        SecurityAuditTypes.PAYLOAD_GROUP_PATH, groupPath,
                        SecurityAuditTypes.PAYLOAD_MEMBER_IDS, List.copyOf(memberIds),
                        SecurityAuditTypes.PAYLOAD_IS_CONTENT_ID, isContentId,
                        SecurityAuditTypes.PAYLOAD_FAILED_IDS, List.copyOf(failedIds)));
    }

    private SecurityAuditEvents() {
        // utility
    }
}
