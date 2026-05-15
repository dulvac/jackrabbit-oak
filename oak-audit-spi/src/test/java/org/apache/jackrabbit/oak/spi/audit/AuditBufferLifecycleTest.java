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
package org.apache.jackrabbit.oak.spi.audit;

import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AuditBufferLifecycleTest {

    @After
    public void tearDown() {
        AuditBufferLifecycle.install(null);
    }

    @Test
    public void noOpWhenNoListenerInstalled() {
        // Exercises the NOOP listener bodies — no exception, no observable effect.
        AuditBufferLifecycle.onCommitFailed("s-1");
        AuditBufferLifecycle.onRefresh("s-1");
    }

    @Test
    public void onCommitFailedRoutesThroughInstalledListener() {
        AtomicReference<String> received = new AtomicReference<>();
        AuditBufferLifecycle.install(new AuditBufferLifecycle.Listener() {
            @Override public void onCommitFailed(@NotNull String sessionId) { received.set(sessionId); }
            @Override public void onRefresh(@NotNull String sessionId) { /* not used */ }
        });
        AuditBufferLifecycle.onCommitFailed("s-1");
        assertEquals("s-1", received.get());
    }

    @Test
    public void onRefreshRoutesThroughInstalledListener() {
        AtomicReference<String> received = new AtomicReference<>();
        AuditBufferLifecycle.install(new AuditBufferLifecycle.Listener() {
            @Override public void onCommitFailed(@NotNull String sessionId) { /* not used */ }
            @Override public void onRefresh(@NotNull String sessionId) { received.set(sessionId); }
        });
        AuditBufferLifecycle.onRefresh("s-2");
        assertEquals("s-2", received.get());
    }

    @Test
    public void installNullResetsToNoOp() {
        AtomicReference<String> received = new AtomicReference<>();
        AuditBufferLifecycle.install(new AuditBufferLifecycle.Listener() {
            @Override public void onCommitFailed(@NotNull String sessionId) { received.set(sessionId); }
            @Override public void onRefresh(@NotNull String sessionId) { received.set(sessionId); }
        });
        AuditBufferLifecycle.install(null);
        // After reset, the custom listener must not be invoked.
        AuditBufferLifecycle.onCommitFailed("s-3");
        AuditBufferLifecycle.onRefresh("s-3");
        assertNull(received.get());
    }
}
