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
package org.apache.jackrabbit.oak.security.audit;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jcr.SimpleCredentials;
import javax.security.auth.login.Configuration;

import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.InitialContentHelper;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.ContentRepository;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.security.internal.SecurityProviderBuilder;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.spi.security.audit.MemberAddedEvent;
import org.apache.jackrabbit.oak.spi.security.audit.SecurityAuditDomain;
import org.apache.jackrabbit.oak.spi.security.authentication.ConfigurationUtil;
import org.apache.jackrabbit.oak.spi.security.user.UserConfiguration;
import org.apache.jackrabbit.oak.spi.toggle.FeatureToggle;
import org.apache.jackrabbit.oak.spi.whiteboard.DefaultWhiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.Tracker;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration test for the AUDIT-SPI production wiring path.
 * <p>
 * Unlike {@link AuditPipelineIT} which records events directly via
 * {@code AuditEvents.record}, this test exercises the path that real Oak
 * consumers traverse:
 * <ol>
 *   <li>JCR {@link UserManager#createGroup(String)} → {@link Group#addMember(org.apache.jackrabbit.api.security.user.Authorizable)}.</li>
 *   <li>{@code UserManagerImpl.recordSingleMembershipAuditEvent} →
 *       {@code AuditEvents.record(root, MemberAddedEvent.of(...))}.</li>
 *   <li>{@code SnapshotAuditBufferHook} → {@code DispatchAuditEventsHook} →
 *       the registered listener.</li>
 * </ol>
 * Asserts the entire chain: capture-site → buffer → snapshot hook →
 * decorator → dispatch hook → listener.
 */
public class AuditWiringIT {

    private Whiteboard whiteboard;
    private AuditConfigurationImpl auditConfig;
    private List<AuditEvent> received;
    private ContentRepository repository;
    private SecurityProvider securityProvider;

    @Before
    public void setUp() {
        whiteboard = new DefaultWhiteboard();
        received = new CopyOnWriteArrayList<>();

        auditConfig = new AuditConfigurationImpl();
        securityProvider = SecurityProviderBuilder.newBuilder()
                .withWhiteboard(whiteboard)
                .withAuditConfiguration(auditConfig)
                .build();

        Configuration.setConfiguration(
                ConfigurationUtil.getDefaultConfiguration(ConfigurationParameters.EMPTY));

        setToggle(true);

        // Listener for the security domain — that's where MemberAddedEvent lands.
        AuditEventListener securityListener = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return SecurityAuditDomain.NAME; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) {
                received.addAll(events);
            }
        };
        whiteboard.register(AuditEventListener.class, securityListener, Map.of());

        repository = new Oak(new MemoryNodeStore(InitialContentHelper.INITIAL_CONTENT))
                .with(securityProvider)
                .with(whiteboard)
                .createContentRepository();
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (auditConfig != null) {
                auditConfig.dispose();
            }
            if (repository instanceof Closeable) {
                ((Closeable) repository).close();
            }
        } finally {
            Configuration.setConfiguration(null);
        }
    }

    private void setToggle(boolean enabled) {
        Tracker<FeatureToggle> toggleTracker = whiteboard.track(FeatureToggle.class);
        try {
            for (FeatureToggle ft : toggleTracker.getServices()) {
                if (AuditConfigurationImpl.FEATURE_TOGGLE_NAME.equals(ft.getName())) {
                    ft.setEnabled(enabled);
                }
            }
        } finally {
            toggleTracker.stop();
        }
    }

    private ContentSession adminLogin() throws Exception {
        return repository.login(new SimpleCredentials("admin", "admin".toCharArray()), null);
    }

    private UserManager userManager(@NotNull Root root) {
        return securityProvider.getConfiguration(UserConfiguration.class)
                .getUserManager(root, NamePathMapper.DEFAULT);
    }

    /**
     * The capture-site in {@code UserManagerImpl.addMember} fires
     * {@link MemberAddedEvent} on successful group update; the event must
     * traverse the entire pipeline to the registered listener with the
     * commit metadata decorated.
     */
    @Test
    public void groupAddMemberFiresMemberAddedEventEndToEnd() throws Exception {
        try (ContentSession session = adminLogin()) {
            Root root = session.getLatestRoot();
            UserManager um = userManager(root);

            Group testGroup = um.createGroup("auditTestGroup");
            User testUser = um.createUser("auditTestUser", "pwd");
            root.commit();
            // The createGroup/createUser commits above do not emit member
            // events. Reset received and exercise addMember below.
            received.clear();

            // Re-fetch from a fresh root post-commit.
            root = session.getLatestRoot();
            um = userManager(root);
            testGroup = (Group) um.getAuthorizable("auditTestGroup");
            testUser = (User) um.getAuthorizable("auditTestUser");
            assertNotNull(testGroup);
            assertNotNull(testUser);

            assertTrue("addMember must succeed", testGroup.addMember(testUser));
            root.commit();

            // The MemberAddedEvent must have traversed the entire pipeline.
            assertEquals("exactly one MemberAddedEvent must arrive",
                    1, received.size());
            AuditEvent event = received.get(0);
            assertEquals(SecurityAuditDomain.NAME, event.getDomain());
            assertEquals(MemberAddedEvent.TYPE, event.getType());

            Map<String, Object> payload = event.getPayload();
            // Commit metadata decorated by DispatchAuditEventsHook.
            assertTrue("commit.sessionId must be decorated",
                    payload.containsKey("commit.sessionId"));
            assertTrue("commit.userId must be decorated",
                    payload.containsKey("commit.userId"));
            assertTrue("commit.timestamp must be decorated",
                    payload.containsKey("commit.timestamp"));
            // Event-specific payload.
            assertTrue("groupPath must be in payload",
                    payload.containsKey(MemberAddedEvent.PAYLOAD_GROUP_PATH));
            assertTrue("memberPath must be in payload",
                    payload.containsKey(MemberAddedEvent.PAYLOAD_MEMBER_PATH));
        }
    }

    /**
     * With the feature toggle disabled, the capture-site
     * {@code AuditEvents.isEnabled()} check in
     * {@code UserManagerImpl.recordSingleMembershipAuditEvent} short-circuits;
     * no event is delivered even though the group update succeeds.
     */
    @Test
    public void toggleDisabledSkipsCaptureSite() throws Exception {
        setToggle(false);
        try (ContentSession session = adminLogin()) {
            Root root = session.getLatestRoot();
            UserManager um = userManager(root);
            Group testGroup = um.createGroup("auditOffGroup");
            User testUser = um.createUser("auditOffUser", "pwd");
            root.commit();
            received.clear();

            root = session.getLatestRoot();
            um = userManager(root);
            testGroup = (Group) um.getAuthorizable("auditOffGroup");
            testUser = (User) um.getAuthorizable("auditOffUser");
            assertTrue(testGroup.addMember(testUser));
            root.commit();

            assertTrue("no event must be delivered with toggle disabled",
                    received.isEmpty());
        }
    }
}
