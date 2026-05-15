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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jcr.RepositoryException;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.oak.AbstractSecurityTest;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEvents;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Branch-coverage tests for the audit-event capture sites in
 * {@link UserManagerImpl#onGroupUpdate}.
 * <p>
 * End-to-end audit dispatch (commit-attached drain + listener invocation)
 * is exercised by {@code AuditWiringIT}/{@code AuditPipelineIT} which build
 * a SecurityProvider with audit hooks wired into the commit chain. Those
 * tests are integration-tests and their coverage data lands in a separate
 * jacoco-it.exec — they don't satisfy the unit-test coverage gate that
 * applies to {@code org.apache.jackrabbit.oak.security.user}.
 * <p>
 * This test installs a stub {@link AuditEvents.Sink} so the capture sites
 * exercise their on-path branches (toggle-on, isRemove true/false, single
 * vs bulk, RepositoryException catch) directly. No commit hooks needed.
 */
public class UserManagerImplAuditTest extends AbstractSecurityTest {

    private final AtomicInteger recordedEvents = new AtomicInteger();

    @Before
    public void installStubSink() {
        recordedEvents.set(0);
        AuditEvents.install(new AuditEvents.Sink() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public boolean isEnabledFor(@NotNull String domain) {
                return true;
            }

            @Override
            public void record(@NotNull Root r, @NotNull AuditEvent event) {
                recordedEvents.incrementAndGet();
            }

            @Override
            public void dispatch(@NotNull AuditEvent event) {
                // not used by the capture sites under test
            }
        });
    }

    @After
    public void resetSink() {
        AuditEvents.install(null);
    }

    @Test
    public void singleMemberAddRecordsEvent() throws Exception {
        UserManagerImpl userMgr = (UserManagerImpl) getUserManager(root);
        User user = getTestUser();
        Group group = userMgr.createGroup("auditTestGroup1");
        try {
            userMgr.onGroupUpdate(group, false, user);
            assertEquals(1, recordedEvents.get());
        } finally {
            group.remove();
            root.commit();
        }
    }

    @Test
    public void singleMemberRemoveRecordsEvent() throws Exception {
        UserManagerImpl userMgr = (UserManagerImpl) getUserManager(root);
        User user = getTestUser();
        Group group = userMgr.createGroup("auditTestGroup2");
        try {
            userMgr.onGroupUpdate(group, true, user);
            assertEquals(1, recordedEvents.get());
        } finally {
            group.remove();
            root.commit();
        }
    }

    @Test
    public void bulkMemberAddRecordsEvent() throws Exception {
        UserManagerImpl userMgr = (UserManagerImpl) getUserManager(root);
        Group group = userMgr.createGroup("auditTestGroup3");
        try {
            userMgr.onGroupUpdate(group, false, false,
                    new HashSet<>(Collections.singleton("memberId")),
                    Collections.emptySet());
            assertEquals(1, recordedEvents.get());
        } finally {
            group.remove();
            root.commit();
        }
    }

    @Test
    public void bulkMemberRemoveRecordsEvent() throws Exception {
        UserManagerImpl userMgr = (UserManagerImpl) getUserManager(root);
        Group group = userMgr.createGroup("auditTestGroup4");
        try {
            userMgr.onGroupUpdate(group, true, false,
                    new HashSet<>(Collections.singleton("memberId")),
                    Collections.emptySet());
            assertEquals(1, recordedEvents.get());
        } finally {
            group.remove();
            root.commit();
        }
    }

    @Test
    public void auditDisabledShortCircuitsCapture() throws Exception {
        // Sink reports disabled — capture sites must short-circuit before record().
        AuditEvents.install(new AuditEvents.Sink() {
            @Override public boolean isEnabled() { return false; }
            @Override public boolean isEnabledFor(@NotNull String domain) { return false; }
            @Override public void record(@NotNull Root r, @NotNull AuditEvent event) {
                recordedEvents.incrementAndGet();
            }
            @Override public void dispatch(@NotNull AuditEvent event) { /* unused */ }
        });
        UserManagerImpl userMgr = (UserManagerImpl) getUserManager(root);
        User user = getTestUser();
        Group group = userMgr.createGroup("auditTestGroup5");
        try {
            userMgr.onGroupUpdate(group, false, user);
            userMgr.onGroupUpdate(group, false, false,
                    new HashSet<>(Collections.singleton("memberId")),
                    Collections.emptySet());
            assertEquals("toggle-off must short-circuit before record()", 0, recordedEvents.get());
        } finally {
            group.remove();
            root.commit();
        }
    }

    @Test
    public void singleMemberPathResolutionFailureSwallowsEvent() throws Exception {
        // Force RepositoryException from member.getPath() to exercise the catch
        // branch in recordSingleMembershipAuditEvent. record() must never be called.
        UserManagerImpl userMgr = (UserManagerImpl) getUserManager(root);
        Group group = userMgr.createGroup("auditTestGroup6");
        Authorizable failing = Mockito.mock(Authorizable.class);
        Mockito.when(failing.getPath()).thenThrow(new RepositoryException("boom"));
        try {
            userMgr.onGroupUpdate(group, false, failing);
            assertEquals("RepositoryException must not produce an audit event",
                    0, recordedEvents.get());
        } finally {
            group.remove();
            root.commit();
        }
    }

    @Test
    public void bulkEmptyMemberIdsShortCircuitsCapture() throws Exception {
        UserManagerImpl userMgr = (UserManagerImpl) getUserManager(root);
        Group group = userMgr.createGroup("auditTestGroup7");
        try {
            // memberIds empty (e.g. all member adds failed upstream) — early-return.
            userMgr.onGroupUpdate(group, false, false,
                    Collections.emptySet(),
                    new HashSet<>(Collections.singleton("failed-id")));
            assertEquals("empty memberIds must not produce a bulk audit event",
                    0, recordedEvents.get());
        } finally {
            group.remove();
            root.commit();
        }
    }

    @Test
    public void bulkPathResolutionFailureSwallowsEvent() throws Exception {
        // Force RepositoryException from group.getPath() to exercise the bulk
        // catch branch. The mocked Group also fails the GroupAction iteration,
        // which propagates — but the audit-record path's catch is exercised first.
        UserManagerImpl userMgr = (UserManagerImpl) getUserManager(root);
        Group failingGroup = Mockito.mock(Group.class);
        Mockito.when(failingGroup.getPath()).thenThrow(new RepositoryException("boom"));
        try {
            userMgr.onGroupUpdate(failingGroup, false, false,
                    new HashSet<>(Collections.singleton("memberId")),
                    Collections.emptySet());
        } catch (RepositoryException expected) {
            // GroupAction.onMemberAdded may also propagate after audit's catch handled.
        }
        assertTrue("audit must have swallowed before any record() call",
                recordedEvents.get() == 0);
    }
}
