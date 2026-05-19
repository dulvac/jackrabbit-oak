# Cross-Stack Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land a domain-neutral audit SPI that lets any OSGi bundle (Oak, AEM, Sling, third-party) emit audit events to a single listener registry, alongside Oak's existing commit-attached pipeline.

**Architecture:** Extract audit primitives from `oak-security-spi` into a new neutral `oak-audit-spi` module. Replace the two-arg `AuditEventListener.onCommit(NodeState, CommitInfo, List<AuditEvent>)` with a single-arg `onEvents(List<AuditEvent>)`. Add an OSGi `AuditEventEmitter` service implemented in `oak-core` that exposes a fire-and-forget dispatch path through the same listener registry. Commit-attached events carry commit metadata (`commit.sessionId`, `commit.userId`, `commit.timestamp`) in their payload after drain-time decoration.

**Tech Stack:** Java 11, Maven 3.x, JUnit 4.13.1, Mockito 5.x, OSGi DS annotations (`org.osgi.service.component.annotations`), JetBrains `@NotNull`/`@Nullable`, OSGi versioning annotations (`@ProviderType`/`@ConsumerType`).

**Spec:** `.claude/oak/plans/audit-spi/08-cross-stack-audit-design.md`

**Existing skeleton (reference):** `.claude/oak/plans/audit-spi/history/03-skeleton/` — Path α implementation that this plan adapts.

---

## File Structure

### New module: `oak-audit-spi`

Domain-neutral SPI module. Consumer classpath: `javax.annotation`, OSGi versioning annotations, JetBrains annotations, `oak-api` (for `Root`). No security types.

```
oak-audit-spi/
├── pom.xml                                                          (NEW — Maven module)
├── README.md                                                        (NEW — module overview)
├── src/main/java/org/apache/jackrabbit/oak/spi/audit/
│   ├── package-info.java                                            (NEW — package versioning)
│   ├── AuditEvent.java                                              (MOVE from oak-security-spi)
│   ├── AuditEventListener.java                                      (MOVE + REPLACE onCommit with onEvents)
│   ├── AuditEventEmitter.java                                       (NEW — OSGi service interface)
│   ├── AuditEventAware.java                                         (MOVE from oak-security-spi)
│   ├── AuditEvents.java                                             (MOVE + ADD dispatch method)
│   ├── AuditBufferLifecycle.java                                    (MOVE from oak-security-spi)
│   └── AuditConfiguration.java                                      (MOVE from oak-security-spi)
└── src/test/java/org/apache/jackrabbit/oak/spi/audit/
    ├── AuditEventsTest.java                                         (NEW — façade tests)
    └── AuditEventListenerTest.java                                  (NEW — interface contract tests)
```

### Modified: `oak-security-spi`

Loses neutral types; keeps security-specific events. Depends on `oak-audit-spi`.

```
oak-security-spi/
├── pom.xml                                                          (MODIFY — add dep on oak-audit-spi)
└── src/main/java/org/apache/jackrabbit/oak/spi/security/audit/
    ├── SecurityAuditDomain.java                                     (unchanged)
    ├── SecurityAuditEvent.java                                      (unchanged — implements oak-audit-spi.AuditEvent)
    ├── MemberAddedEvent.java                                        (unchanged)
    ├── MemberRemovedEvent.java                                      (unchanged)
    ├── MembersAddedBulkEvent.java                                   (unchanged)
    └── MembersRemovedBulkEvent.java                                 (unchanged)
```

### Modified: `oak-core`

Audit impl pipeline adapts to the new listener method. Adds the emitter component.

```
oak-core/
├── pom.xml                                                          (MODIFY — add dep on oak-audit-spi)
└── src/main/java/org/apache/jackrabbit/oak/security/audit/
    ├── AuditBuffer.java                                             (unchanged)
    ├── AuditConfigurationImpl.java                                  (MODIFY — install dispatch in Sink)
    ├── SnapshotAuditBufferHook.java                                 (unchanged)
    ├── DispatchAuditEventsHook.java                                 (MODIFY — onEvents + payload decoration)
    ├── WhiteboardAuditEventListenerRegistry.java                    (MODIFY — onEvents API)
    ├── CommitMetadataDecorator.java                                 (NEW — decorates payload with commit.* keys)
    ├── AuditEventEmitterImpl.java                                   (NEW — OSGi @Component)
    └── NoOpAuditEventListener.java                                  (MODIFY — implement onEvents)
└── src/test/java/org/apache/jackrabbit/oak/security/audit/
    ├── DispatchAuditEventsHookTest.java                             (MODIFY — onEvents assertions + decoration tests)
    ├── WhiteboardAuditEventListenerRegistryTest.java                (MODIFY — onEvents API)
    ├── CommitMetadataDecoratorTest.java                             (NEW)
    ├── AuditEventEmitterImplTest.java                               (NEW)
    └── (existing tests adapted as needed)
```

---

## Task 1: Create `oak-audit-spi` Maven module skeleton

**Files:**
- Create: `oak-audit-spi/pom.xml`
- Create: `oak-audit-spi/README.md`
- Create: `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/package-info.java`
- Modify: `pom.xml` (root reactor — add `<module>oak-audit-spi</module>`)

### Step 1.1 — Create `oak-audit-spi/pom.xml`

- [ ] Create the new module's POM modelled on `oak-security-spi/pom.xml`. Use 100% coverage gates (`0.99` line / `1.0` branch) because this module is on the security trust boundary.

Write `oak-audit-spi/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.apache.jackrabbit</groupId>
        <artifactId>oak-parent</artifactId>
        <version>1.78-SNAPSHOT</version>
        <relativePath>../oak-parent/pom.xml</relativePath>
    </parent>

    <artifactId>oak-audit-spi</artifactId>
    <name>Oak Audit SPI</name>
    <description>Domain-neutral audit SPI. Consumed by oak-security-spi and any OSGi bundle that needs to emit or consume audit events.</description>

    <properties>
        <skip.coverage>false</skip.coverage>
        <minimum.line.coverage>0.99</minimum.line.coverage>
        <minimum.branch.coverage>1.0</minimum.branch.coverage>
    </properties>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.felix</groupId>
                <artifactId>maven-bundle-plugin</artifactId>
                <configuration>
                    <instructions>
                        <Export-Package>
                            org.apache.jackrabbit.oak.spi.audit;version=${project.version}
                        </Export-Package>
                    </instructions>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <dependencies>
        <dependency>
            <groupId>org.apache.jackrabbit</groupId>
            <artifactId>oak-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.osgi</groupId>
            <artifactId>org.osgi.annotation.versioning</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jetbrains</groupId>
            <artifactId>annotations</artifactId>
        </dependency>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### Step 1.2 — Verify `oak-parent` version

- [ ] Run `grep '<version>' oak-parent/pom.xml | head -3` to confirm the current parent version. If it is NOT `1.78-SNAPSHOT`, update the `<parent>/<version>` field in the new POM accordingly.

Run: `grep '<version>' oak-parent/pom.xml | head -3`
Expected: a single `<version>` line; substitute its value into the new POM.

### Step 1.3 — Register module in root reactor

- [ ] Edit `pom.xml` (the repository root). Locate the `<modules>` list and add `<module>oak-audit-spi</module>` immediately before `<module>oak-security-spi</module>` so build order respects the dependency.

### Step 1.4 — Create `oak-audit-spi/README.md`

```markdown
Apache Jackrabbit Oak Audit SPI
===============================

Domain-neutral audit SPI. Defines:

- `AuditEvent` — generic event interface (domain, type, timestamp, payload).
- `AuditEventListener` — single-method consumer interface (`onEvents`).
- `AuditEventEmitter` — OSGi service for any bundle to emit events.
- `AuditEvents` — static façade for Oak-internal capture sites.

Consumed by `oak-security-spi`, `oak-core`, and any consumer bundle.

This module does NOT depend on `oak-security-spi`, `oak-core`, or any
Oak-internal storage modules.
```

### Step 1.5 — Create `package-info.java`

Create `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/package-info.java`:

```java
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
@Version("1.0.0")
package org.apache.jackrabbit.oak.spi.audit;

import org.osgi.annotation.versioning.Version;
```

### Step 1.6 — Build the new module standalone

- [ ] Run `mvn install -pl oak-audit-spi -am -DskipTests` from the repo root.

Expected: BUILD SUCCESS. Verifies the POM is valid and reactor wiring works before any code lands.

### Step 1.7 — Commit

```bash
git add oak-audit-spi/pom.xml oak-audit-spi/README.md \
        oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/package-info.java \
        pom.xml
git commit -m "OAK-XXXXX: create oak-audit-spi Maven module skeleton"
```

(Substitute the real JIRA key when the user provides it. If unknown, ask the user for the key before committing.)

---

## Task 2: Add `AuditEvent` interface

**Files:**
- Create: `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEvent.java`

### Step 2.1 — Create `AuditEvent.java`

- [ ] Write the file. Same shape as the existing skeleton's `oak-security-spi__AuditEvent.java`, but new package (`org.apache.jackrabbit.oak.spi.audit`) and reframed Javadoc (no longer "before Root.commit" — the SPI is neutral on commit semantics).

```java
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

import java.util.Collections;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Structured audit event. Implementations are expected to be immutable
 * value types.
 * <p>
 * Events may originate from two pipelines:
 * <ul>
 *   <li>Oak-internal capture sites tied to a successful
 *       {@code Root.commit()}. The commit-attached drain step decorates
 *       payload with {@code commit.sessionId}, {@code commit.userId},
 *       and {@code commit.timestamp} entries before dispatch.</li>
 *   <li>Any bundle calling {@link AuditEventEmitter#emit(AuditEvent)}.
 *       Such events carry the payload provided by the caller; Oak does
 *       not add or verify any fields.</li>
 * </ul>
 * The {@link #getDomain()} value selects the listeners that receive this
 * event.
 */
@ProviderType
public interface AuditEvent {

    /**
     * Returns the domain that owns this event. Listeners are domain-scoped:
     * an {@link AuditEventListener} only receives events whose
     * {@code getDomain()} matches its own {@link AuditEventListener#getDomain()}.
     *
     * @return non-null domain name (e.g. {@code "security"}, {@code "aem.content"}).
     */
    @NotNull
    String getDomain();

    /**
     * Returns the event type identifier within the domain. Type strings are
     * stable across releases for any given domain.
     *
     * @return non-null type identifier.
     */
    @NotNull
    String getType();

    /**
     * Returns the wall-clock timestamp (millis since epoch) at which the
     * event was recorded.
     *
     * @return event timestamp in milliseconds since epoch.
     */
    long getTimestamp();

    /**
     * Returns the structured payload for this event. The default
     * implementation returns an empty map; concrete event types override
     * this to expose typed accessors and include their fields here.
     * <p>
     * For commit-attached events, the {@code DispatchAuditEventsHook} adds
     * entries with the keys {@code commit.sessionId}, {@code commit.userId},
     * and {@code commit.timestamp} at drain time. Fire-and-forget events
     * do not carry these entries.
     *
     * @return non-null, immutable payload map.
     */
    @NotNull
    default Map<String, Object> getPayload() {
        return Collections.emptyMap();
    }
}
```

### Step 2.2 — Build module

- [ ] Run `mvn install -pl oak-audit-spi -DskipTests`.

Expected: BUILD SUCCESS.

### Step 2.3 — Commit

```bash
git add oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEvent.java
git commit -m "OAK-XXXXX: add AuditEvent interface in oak-audit-spi"
```

---

## Task 3: Add `AuditEventListener` interface with single `onEvents` method

**Files:**
- Create: `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEventListener.java`

### Step 3.1 — Write the failing test

- [ ] Create `oak-audit-spi/src/test/java/org/apache/jackrabbit/oak/spi/audit/AuditEventListenerTest.java`.

```java
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

import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuditEventListenerTest {

    @Test
    public void defaultRankIsZero() {
        AuditEventListener listener = new AuditEventListener() {
            @Override
            public @NotNull String getDomain() {
                return "test";
            }

            @Override
            public void onEvents(@NotNull List<AuditEvent> events) {
                // no-op for this test
            }
        };
        assertEquals(0, listener.getRank());
    }

    @Test
    public void listenerReceivesCallToOnEvents() {
        final List<AuditEvent>[] received = new List[]{null};
        AuditEventListener listener = new AuditEventListener() {
            @Override
            public @NotNull String getDomain() {
                return "test";
            }

            @Override
            public void onEvents(@NotNull List<AuditEvent> events) {
                received[0] = events;
            }
        };
        listener.onEvents(Collections.emptyList());
        assertEquals(Collections.emptyList(), received[0]);
    }
}
```

### Step 3.2 — Run test, verify it fails

- [ ] Run: `mvn test -pl oak-audit-spi -Dtest=AuditEventListenerTest`

Expected: COMPILATION FAILURE — `AuditEventListener` class does not exist.

### Step 3.3 — Create `AuditEventListener.java`

- [ ] Write the file.

```java
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

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ConsumerType;

/**
 * Receives a burst of audit events for the listener's domain. One method
 * for both commit-attached events (drained after a successful
 * {@code Root.commit()}) and fire-and-forget events (dispatched
 * immediately via {@link AuditEventEmitter#emit(AuditEvent)}).
 * <p>
 * Implementations must be non-blocking. Invocation is synchronous on the
 * dispatching thread; expensive work (I/O, fan-out, persistence) belongs
 * in an async wrapper provided by the consumer.
 * <p>
 * Exceptions thrown from {@link #onEvents} are caught, logged, and
 * swallowed by the dispatcher; they never propagate back to the
 * dispatching thread.
 * <p>
 * Listener invocation order is determined by {@link #getRank()} (higher
 * value first). The dispatcher applies a stable sort, so listeners with
 * equal rank are invoked in {@code Whiteboard} order.
 *
 * <h3>Trust model</h3>
 * Events delivered through this method may originate from either:
 * <ul>
 *   <li>Oak-internal capture sites tied to a successful
 *       {@code Root.commit()}. Such events carry {@code commit.sessionId},
 *       {@code commit.userId}, and {@code commit.timestamp} entries in
 *       their payload. {@code commit.userId} is {@code "oak:unknown"} for
 *       system commits and listeners <strong>MUST NOT</strong> attempt to
 *       resolve it to a real user identity.</li>
 *   <li>Any bundle calling {@link AuditEventEmitter#emit(AuditEvent)}.
 *       The accuracy of such events is the emitting bundle's responsibility;
 *       Oak does not verify them. They do not carry the {@code commit.*}
 *       payload entries.</li>
 * </ul>
 * Consumers that need to distinguish between the two sources should
 * inspect the payload for the {@code commit.sessionId} key.
 */
@ConsumerType
public interface AuditEventListener {

    /**
     * Returns the domain this listener is interested in. Must be stable
     * across the listener's lifetime; the registry caches active domains
     * to short-circuit dispatch when no listener is present.
     *
     * @return non-null domain name.
     */
    @NotNull
    String getDomain();

    /**
     * Returns the dispatch rank for this listener — higher value is
     * invoked first. The default implementation returns {@code 0}.
     *
     * @return rank value.
     */
    default int getRank() {
        return 0;
    }

    /**
     * Invoked when one or more events for this listener's domain are
     * dispatched. Events arrive in capture order (earliest first).
     *
     * @param events the non-empty list of events for this listener's
     *               domain. Each event's payload map values are never
     *               null; optional fields are absent from the map.
     */
    void onEvents(@NotNull List<AuditEvent> events);
}
```

### Step 3.4 — Run test, verify it passes

- [ ] Run: `mvn test -pl oak-audit-spi -Dtest=AuditEventListenerTest`

Expected: BUILD SUCCESS, 2 tests pass.

### Step 3.5 — Commit

```bash
git add oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEventListener.java \
        oak-audit-spi/src/test/java/org/apache/jackrabbit/oak/spi/audit/AuditEventListenerTest.java
git commit -m "OAK-XXXXX: add AuditEventListener with single onEvents method"
```

---

## Task 4: Add `AuditEvents` static façade with `dispatch` method

**Files:**
- Create: `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEvents.java`
- Create: `oak-audit-spi/src/test/java/org/apache/jackrabbit/oak/spi/audit/AuditEventsTest.java`

### Step 4.1 — Write the failing test

```java
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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jackrabbit.oak.api.Root;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class AuditEventsTest {

    @After
    public void tearDown() {
        AuditEvents.install(null);
    }

    private static AuditEvent fixedEvent(@NotNull String domain) {
        return new AuditEvent() {
            @Override public @NotNull String getDomain() { return domain; }
            @Override public @NotNull String getType() { return "t"; }
            @Override public long getTimestamp() { return 0L; }
            @Override public @NotNull Map<String, Object> getPayload() { return Collections.emptyMap(); }
        };
    }

    @Test
    public void facadeNoOpWhenNoSinkInstalled() {
        assertFalse(AuditEvents.isEnabled());
        assertFalse(AuditEvents.isEnabledFor("security"));
        AuditEvents.record(mock(Root.class), fixedEvent("security"));
        AuditEvents.dispatch(fixedEvent("security"));
        // no exception, no observable effect — verified by no sink installed
    }

    @Test
    public void recordRoutesThroughInstalledSink() {
        AtomicReference<AuditEvent> received = new AtomicReference<>();
        AuditEvents.install(new AuditEvents.Sink() {
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isEnabledFor(@NotNull String domain) { return true; }
            @Override public void record(@NotNull Root root, @NotNull AuditEvent event) { received.set(event); }
            @Override public void dispatch(@NotNull AuditEvent event) { /* not used */ }
        });
        AuditEvent e = fixedEvent("security");
        AuditEvents.record(mock(Root.class), e);
        assertSame(e, received.get());
    }

    @Test
    public void dispatchRoutesThroughInstalledSink() {
        AtomicReference<AuditEvent> received = new AtomicReference<>();
        AuditEvents.install(new AuditEvents.Sink() {
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isEnabledFor(@NotNull String domain) { return true; }
            @Override public void record(@NotNull Root root, @NotNull AuditEvent event) { /* not used */ }
            @Override public void dispatch(@NotNull AuditEvent event) { received.set(event); }
        });
        AuditEvent e = fixedEvent("aem.content");
        AuditEvents.dispatch(e);
        assertSame(e, received.get());
    }

    @Test
    public void installNullResetsToNoOp() {
        AuditEvents.install(new AuditEvents.Sink() {
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isEnabledFor(@NotNull String domain) { return true; }
            @Override public void record(@NotNull Root root, @NotNull AuditEvent event) { }
            @Override public void dispatch(@NotNull AuditEvent event) { }
        });
        assertTrue(AuditEvents.isEnabled());
        AuditEvents.install(null);
        assertFalse(AuditEvents.isEnabled());
    }
}
```

### Step 4.2 — Run test, verify it fails

- [ ] Run: `mvn test -pl oak-audit-spi -Dtest=AuditEventsTest`

Expected: COMPILATION FAILURE — `AuditEvents` class does not exist.

### Step 4.3 — Create `AuditEvents.java`

```java
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

import org.apache.jackrabbit.oak.api.Root;
import org.jetbrains.annotations.NotNull;

/**
 * Static façade used by Oak-internal code (commit-attached capture sites,
 * the OSGi {@link AuditEventEmitter} impl) to talk to the audit pipeline.
 * The façade is wired to a {@link Sink} on activation of the audit module;
 * when no module is deployed (or the feature toggle is off) it short-circuits
 * with zero allocation.
 */
public final class AuditEvents {

    /**
     * Sink contract implemented by the audit module. Installed via
     * {@link #install(Sink)} on activation of the audit module; on
     * deactivation the sink is reset to a NOOP.
     */
    public interface Sink {

        /**
         * Returns {@code true} when the feature toggle is enabled and at
         * least one listener is registered (for any domain). Cheapest gate.
         */
        boolean isEnabled();

        /**
         * Returns {@code true} when at least one listener is registered
         * for the given domain. Used to avoid event allocation when no
         * consumer cares about the domain.
         */
        boolean isEnabledFor(@NotNull String domain);

        /**
         * Commit-attached path. Buffers the event against the session
         * backing the supplied {@link Root}. Dispatched on commit success;
         * discarded on commit failure.
         */
        void record(@NotNull Root root, @NotNull AuditEvent event);

        /**
         * Fire-and-forget path. Dispatches the event synchronously on the
         * calling thread to all listeners registered for its domain. Not
         * buffered; not tied to any commit.
         */
        void dispatch(@NotNull AuditEvent event);
    }

    private static final Sink NOOP = new Sink() {
        @Override public boolean isEnabled() { return false; }
        @Override public boolean isEnabledFor(@NotNull String domain) { return false; }
        @Override public void record(@NotNull Root root, @NotNull AuditEvent event) { }
        @Override public void dispatch(@NotNull AuditEvent event) { }
    };

    private static volatile Sink sink = NOOP;

    private AuditEvents() {
        // utility class
    }

    /**
     * Installs the active sink. Called by the audit module on activation.
     * Passing {@code null} resets the façade to the NOOP sink.
     */
    public static void install(Sink newSink) {
        sink = (newSink != null) ? newSink : NOOP;
    }

    public static boolean isEnabled() {
        return sink.isEnabled();
    }

    public static boolean isEnabledFor(@NotNull String domain) {
        return sink.isEnabledFor(domain);
    }

    public static void record(@NotNull Root root, @NotNull AuditEvent event) {
        sink.record(root, event);
    }

    public static void dispatch(@NotNull AuditEvent event) {
        sink.dispatch(event);
    }
}
```

### Step 4.4 — Run test, verify it passes

- [ ] Run: `mvn test -pl oak-audit-spi -Dtest=AuditEventsTest`

Expected: BUILD SUCCESS, 4 tests pass.

### Step 4.5 — Commit

```bash
git add oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEvents.java \
        oak-audit-spi/src/test/java/org/apache/jackrabbit/oak/spi/audit/AuditEventsTest.java
git commit -m "OAK-XXXXX: add AuditEvents static façade with dispatch path"
```

---

## Task 5: Add `AuditEventEmitter` OSGi service interface

**Files:**
- Create: `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEventEmitter.java`

### Step 5.1 — Create `AuditEventEmitter.java`

- [ ] Pure interface; no test needed at this layer — the impl test in Task 12 verifies behavior.

```java
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

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * OSGi service for emitting audit events. Consumed via {@code @Reference}:
 * <pre>{@code
 * @Reference private AuditEventEmitter audit;
 *
 * void onSomething() {
 *     if (audit.isEnabledFor("aem.content")) {
 *         audit.emit(new MyEvent(...));
 *     }
 * }
 * }</pre>
 * <p>
 * The emitter dispatches synchronously on the calling thread to all
 * listeners registered for the event's domain. Not tied to any commit;
 * not buffered; not rolled back on failure.
 * <p>
 * Listeners are invoked under per-listener try/catch isolation: one
 * listener throwing does not prevent others from running, and exceptions
 * are logged but never propagate back to the caller.
 * <p>
 * <strong>Trust model:</strong> any bundle that resolves this service can
 * emit any event for any domain. The event payload reflects the emitting
 * bundle's claim; Oak does not verify it. See
 * {@link AuditEventListener} class-level Javadoc for the listener-side
 * trust contract.
 */
@ProviderType
public interface AuditEventEmitter {

    /**
     * Dispatches the event to all listeners registered for the event's
     * domain. Synchronous on the calling thread.
     *
     * @param event the event to dispatch, non-null.
     */
    void emit(@NotNull AuditEvent event);

    /**
     * Returns {@code true} when at least one listener is registered for
     * the given domain. Callers should gate event allocation with this
     * method on hot paths.
     *
     * @param domain the domain to check, non-null.
     */
    boolean isEnabledFor(@NotNull String domain);
}
```

### Step 5.2 — Build module

- [ ] Run: `mvn install -pl oak-audit-spi -DskipTests`

Expected: BUILD SUCCESS.

### Step 5.3 — Commit

```bash
git add oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEventEmitter.java
git commit -m "OAK-XXXXX: add AuditEventEmitter OSGi service interface"
```

---

## Task 6: Move support types from `oak-security-spi` plan into `oak-audit-spi`

Two types are part of the Path α skeleton (in `.claude/oak/plans/audit-spi/history/03-skeleton/`) but have not yet been merged into `oak-security-spi`. They move directly into `oak-audit-spi`.

> **Plan revision (2026-05-15):** `AuditConfiguration` is NOT moved to `oak-audit-spi`. It `extends SecurityConfiguration` from `oak-security-spi`, so moving it would create a dependency cycle once Task 7 wires `oak-security-spi → oak-audit-spi`. `AuditConfiguration` stays in `oak-security-spi` (package `org.apache.jackrabbit.oak.spi.security.audit`) and is added in Task 8 alongside the security event subclasses.

**Files:**
- Create: `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEventAware.java`
- Create: `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditBufferLifecycle.java`

### Step 6.1 — Read the skeleton sources

- [ ] Read each of the following files. They each have an existing skeleton in `.claude/oak/plans/audit-spi/history/03-skeleton/`:
  - `oak-security-spi__AuditEventAware.java`
  - `oak-security-spi__AuditBufferLifecycle.java`

### Step 6.2 — Port each file, updating the package

- [ ] For each file:
  - Change the `package` declaration from `org.apache.jackrabbit.oak.spi.security.audit` to `org.apache.jackrabbit.oak.spi.audit`.
  - Update any `import` statements that reference `org.apache.jackrabbit.oak.spi.security.audit.*` to `org.apache.jackrabbit.oak.spi.audit.*`.
  - Place the file at `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/<name>.java`.

### Step 6.3 — Build module

- [ ] Run: `mvn install -pl oak-audit-spi -DskipTests`

Expected: BUILD SUCCESS.

### Step 6.4 — Commit

```bash
git add oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEventAware.java \
        oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditBufferLifecycle.java
git commit -m "audit-spi: move AuditEventAware and AuditBufferLifecycle to oak-audit-spi"
```

---

## Task 7: Add dependency on `oak-audit-spi` in `oak-security-spi`

**Files:**
- Modify: `oak-security-spi/pom.xml`

### Step 7.1 — Add the dependency

- [ ] Open `oak-security-spi/pom.xml`. Locate the `<dependencies>` block. Add the new dependency near the other Oak deps (typically near `oak-api`):

```xml
<dependency>
    <groupId>org.apache.jackrabbit</groupId>
    <artifactId>oak-audit-spi</artifactId>
    <version>${project.version}</version>
</dependency>
```

### Step 7.2 — Build oak-security-spi

- [ ] Run: `mvn install -pl oak-security-spi -am -DskipTests`

Expected: BUILD SUCCESS.

### Step 7.3 — Commit

```bash
git add oak-security-spi/pom.xml
git commit -m "OAK-XXXXX: oak-security-spi depends on oak-audit-spi"
```

---

## Task 8: Add security audit event subclasses + `AuditConfiguration` to `oak-security-spi`

These come from the Path α skeleton. They implement `AuditEvent` from `oak-audit-spi` (not `oak-security-spi` — the interface moved). `AuditConfiguration` is included here (not in Task 6) because it `extends SecurityConfiguration` from `oak-security-spi`.

**Files:**
- Create: `oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/AuditConfiguration.java`
- Create: `oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/SecurityAuditDomain.java`
- Create: `oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/SecurityAuditEvent.java`
- Create: `oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/MemberAddedEvent.java`
- Create: `oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/MemberRemovedEvent.java`
- Create: `oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/MembersAddedBulkEvent.java`
- Create: `oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/MembersRemovedBulkEvent.java`

### Step 8.1 — Port each file from the skeleton

- [ ] For each of the 7 files listed above:
  - Read the corresponding `oak-security-spi__*.java` file in `.claude/oak/plans/audit-spi/history/03-skeleton/`.
  - Place at `oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/<name>.java`.
  - Update imports: any reference to `org.apache.jackrabbit.oak.spi.security.audit.AuditEvent` (or `AuditEventListener`, `AuditEventAware`, `AuditBufferLifecycle`) becomes `org.apache.jackrabbit.oak.spi.audit.<TypeName>` (move to the neutral package).
  - The package declaration STAYS `org.apache.jackrabbit.oak.spi.security.audit` for these security-specific classes.

### Step 8.2 — Build oak-security-spi

- [ ] Run: `mvn install -pl oak-security-spi -DskipTests`

Expected: BUILD SUCCESS.

### Step 8.3 — Commit

```bash
git add oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/
git commit -m "OAK-XXXXX: add security audit event subclasses in oak-security-spi"
```

---

## Task 9: Wire `oak-audit-spi` into `oak-core`

**Files:**
- Modify: `oak-core/pom.xml`

### Step 9.1 — Add dependency

- [ ] Open `oak-core/pom.xml`. Add to `<dependencies>`:

```xml
<dependency>
    <groupId>org.apache.jackrabbit</groupId>
    <artifactId>oak-audit-spi</artifactId>
    <version>${project.version}</version>
</dependency>
```

### Step 9.2 — Build oak-core

- [ ] Run: `mvn install -pl oak-core -am -DskipTests`

Expected: BUILD SUCCESS.

### Step 9.3 — Commit

```bash
git add oak-core/pom.xml
git commit -m "OAK-XXXXX: oak-core depends on oak-audit-spi"
```

---

## Task 10: Port the commit-attached impl into `oak-core` and adapt to `onEvents`

The Path α skeleton already contains the impl files. They need to be ported with two adaptations:
1. Imports point at `org.apache.jackrabbit.oak.spi.audit.*` (not `org.apache.jackrabbit.oak.spi.security.audit.*`).
2. `DispatchAuditEventsHook` calls `listener.onEvents(decoratedEvents)` instead of `listener.onCommit(after, info, events)`.
3. `WhiteboardAuditEventListenerRegistry` and `NoOpAuditEventListener` adapt to the renamed method.

**Files (port from `.claude/oak/plans/audit-spi/history/03-skeleton/`):**
- Create: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditBuffer.java`
- Create: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditConfigurationImpl.java`
- Create: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/SnapshotAuditBufferHook.java`
- Create: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/DispatchAuditEventsHook.java` (with adaptations below)
- Create: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/WhiteboardAuditEventListenerRegistry.java`
- Create: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/NoOpAuditEventListener.java` (with adaptation below)

### Step 10.1 — Port `AuditBuffer.java`

- [ ] Read `.claude/oak/plans/audit-spi/history/03-skeleton/oak-core__AuditBuffer.java`. Update imports to point at `org.apache.jackrabbit.oak.spi.audit.*`. Place at the destination above. Package declaration `org.apache.jackrabbit.oak.security.audit` stays.

### Step 10.2 — Port `SnapshotAuditBufferHook.java`

- [ ] Same drill: read the skeleton, update imports to `org.apache.jackrabbit.oak.spi.audit.*`, place at destination.

### Step 10.3 — Port `WhiteboardAuditEventListenerRegistry.java`

- [ ] Read `.claude/oak/plans/audit-spi/history/03-skeleton/oak-core__WhiteboardAuditEventListenerRegistry.java`. Update imports. The registry's public API (`getListeners()`, `getActiveDomains()`) does not change. Place at destination.

### Step 10.4 — Port `NoOpAuditEventListener.java`

- [ ] Read the skeleton. Replace the `onCommit(NodeState, CommitInfo, List<AuditEvent>)` method body with an `onEvents(List<AuditEvent>)` implementation that performs the same TRACE-level logging:

```java
package org.apache.jackrabbit.oak.security.audit;

import java.util.List;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.jackrabbit.oak.spi.security.audit.SecurityAuditDomain.NAME;

/**
 * Reference listener used in tests and as a fallback when no production
 * listener is registered. Logs at TRACE level only; payloads are not
 * logged at higher levels by default.
 */
public final class NoOpAuditEventListener implements AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(NoOpAuditEventListener.class);

    @NotNull
    @Override
    public String getDomain() {
        return NAME;
    }

    @Override
    public void onEvents(@NotNull List<AuditEvent> events) {
        if (log.isTraceEnabled()) {
            for (AuditEvent event : events) {
                log.trace("noop audit listener: domain={} type={} payload-keys={}",
                        event.getDomain(), event.getType(), event.getPayload().keySet());
            }
        }
    }
}
```

(Imports the security `SecurityAuditDomain.NAME` since this listener is registered against the security domain.)

### Step 10.5 — Port `AuditConfigurationImpl.java`

- [ ] Read the skeleton. Update imports to `org.apache.jackrabbit.oak.spi.audit.*`. The class installs the `AuditEvents.Sink` and registers commit hooks. The Sink installed now has BOTH `record(...)` and `dispatch(...)` methods. The `dispatch` impl will be wired in Task 11 (it depends on the registry). Place a TODO-marked stub `dispatch` for now:

```java
sink.install(new AuditEvents.Sink() {
    // ... existing fields ...

    @Override
    public boolean isEnabled() { return featureToggle.isEnabled(); }

    @Override
    public boolean isEnabledFor(@NotNull String domain) {
        return featureToggle.isEnabled() && registry.getActiveDomains().contains(domain);
    }

    @Override
    public void record(@NotNull Root root, @NotNull AuditEvent event) {
        // existing implementation: append to buffer
        buffer.append(getSessionId(root), event);
    }

    @Override
    public void dispatch(@NotNull AuditEvent event) {
        // Task 11 wires fire-and-forget dispatch here.
        // For now, throw to make the gap obvious if exercised.
        throw new UnsupportedOperationException(
                "AuditEvents.dispatch not yet wired; will land in Task 11");
    }
});
```

Note: the actual fire-and-forget dispatch is implemented in Task 11. Putting an explicit throw here ensures any accidentally-installed test fixture surfaces the gap rather than silently succeeding.

### Step 10.6 — Port `DispatchAuditEventsHook.java` with payload decoration

This is the substantive change. The existing skeleton calls `listener.onCommit(after, info, events)`. Replace with: decorate each event's payload via a helper (created in Task 11), then call `listener.onEvents(decoratedEvents)`. For now, write a placeholder `decorate` that returns the events unchanged; Task 11 swaps it for the real decorator.

Write the file:

```java
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.commit.CommitContext;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.PostValidationHook;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.toggle.Feature;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PostValidationHook} that reads the snapshot parked by
 * {@link SnapshotAuditBufferHook}, decorates each event's payload with
 * commit metadata, groups events by domain, and dispatches them to all
 * matching {@link AuditEventListener}s via {@link AuditEventListener#onEvents}.
 * <p>
 * Listener invocations are wrapped in a try/catch: failures are logged
 * at {@code WARN} level and swallowed.
 */
final class DispatchAuditEventsHook implements PostValidationHook {

    private static final Logger log = LoggerFactory.getLogger(DispatchAuditEventsHook.class);

    private final Feature featureToggle;
    private final AuditBuffer buffer;
    private final WhiteboardAuditEventListenerRegistry registry;

    DispatchAuditEventsHook(@NotNull Feature featureToggle,
                            @NotNull AuditBuffer buffer,
                            @NotNull WhiteboardAuditEventListenerRegistry registry) {
        this.featureToggle = featureToggle;
        this.buffer = buffer;
        this.registry = registry;
    }

    @NotNull
    @Override
    public NodeState processCommit(NodeState before, NodeState after, CommitInfo info)
            throws CommitFailedException {
        if (!featureToggle.isEnabled()) {
            return after;
        }
        CommitContext ctx = (CommitContext) info.getInfo().get(CommitContext.NAME);
        if (ctx == null) {
            return after;
        }
        Object stashed = ctx.get(SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY);
        if (!(stashed instanceof List)) {
            return after;
        }
        @SuppressWarnings("unchecked")
        List<AuditEvent> events = (List<AuditEvent>) stashed;
        try {
            if (events.isEmpty()) {
                return after;
            }
            List<AuditEventListener> listeners = registry.getListeners();
            if (listeners.isEmpty()) {
                return after;
            }
            // Task 11 replaces this passthrough with real metadata decoration.
            List<AuditEvent> decorated = decorate(events, info);
            Map<String, List<AuditEvent>> byDomain = groupByDomain(decorated);
            for (AuditEventListener listener : listeners) {
                List<AuditEvent> forListener = byDomain.get(listener.getDomain());
                if (forListener == null || forListener.isEmpty()) {
                    continue;
                }
                dispatchOne(listener, forListener);
            }
            return after;
        } finally {
            buffer.drain(info.getSessionId());
            ctx.remove(SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY);
        }
    }

    private static @NotNull List<AuditEvent> decorate(@NotNull List<AuditEvent> events,
                                                      @NotNull CommitInfo info) {
        // Passthrough until Task 11 lands CommitMetadataDecorator.
        return events;
    }

    private static @NotNull Map<String, List<AuditEvent>> groupByDomain(@NotNull List<AuditEvent> events) {
        Map<String, List<AuditEvent>> byDomain = new HashMap<>(4);
        for (AuditEvent event : events) {
            byDomain.computeIfAbsent(event.getDomain(), k -> new ArrayList<>(events.size())).add(event);
        }
        return byDomain;
    }

    private static void dispatchOne(@NotNull AuditEventListener listener,
                                    @NotNull List<AuditEvent> events) {
        try {
            listener.onEvents(events);
        } catch (RuntimeException re) {
            log.warn("AuditEventListener {} failed for {} event(s) in domain '{}'; swallowing.",
                    listener.getClass().getName(), events.size(), listener.getDomain(), re);
        }
    }
}
```

### Step 10.7 — Build oak-core

- [ ] Run: `mvn install -pl oak-core -DskipTests`

Expected: BUILD SUCCESS.

### Step 10.8 — Commit

```bash
git add oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/
git commit -m "OAK-XXXXX: port commit-attached audit impl to oak-core (single onEvents method)"
```

---

## Task 11: Add `CommitMetadataDecorator` and wire it into `DispatchAuditEventsHook`

**Files:**
- Create: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/CommitMetadataDecorator.java`
- Create: `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/CommitMetadataDecoratorTest.java`
- Modify: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/DispatchAuditEventsHook.java:55-60` (the `decorate` method body)

### Step 11.1 — Write the failing test for the decorator

```java
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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CommitMetadataDecoratorTest {

    private static AuditEvent original(@NotNull String domain, @NotNull String type, @NotNull Map<String, Object> payload) {
        return new AuditEvent() {
            @Override public @NotNull String getDomain() { return domain; }
            @Override public @NotNull String getType() { return type; }
            @Override public long getTimestamp() { return 12345L; }
            @Override public @NotNull Map<String, Object> getPayload() { return payload; }
        };
    }

    @Test
    public void decoratesPayloadWithCommitMetadata() {
        AuditEvent in = original("security", "member.added", Map.of("group", "/g", "member", "/m"));
        CommitInfo info = new CommitInfo("session-1", "alice", Map.of(), false);
        List<AuditEvent> out = CommitMetadataDecorator.decorate(List.of(in), info);
        assertEquals(1, out.size());
        AuditEvent decorated = out.get(0);
        Map<String, Object> p = decorated.getPayload();
        assertEquals("session-1", p.get("commit.sessionId"));
        assertEquals("alice", p.get("commit.userId"));
        assertTrue(p.containsKey("commit.timestamp"));
        // original payload entries preserved
        assertEquals("/g", p.get("group"));
        assertEquals("/m", p.get("member"));
        // original event passed in not mutated
        assertEquals(Map.of("group", "/g", "member", "/m"), in.getPayload());
        assertNotSame(in, decorated);
    }

    @Test
    public void emptyEventsReturnsEmpty() {
        CommitInfo info = new CommitInfo("session-1", "alice", Map.of(), false);
        assertSame(Collections.<AuditEvent>emptyList(), CommitMetadataDecorator.decorate(Collections.emptyList(), info));
    }

    @Test
    public void preservesDomainTypeTimestamp() {
        AuditEvent in = original("aem.content", "fragment.published", Map.of("path", "/p"));
        CommitInfo info = new CommitInfo("s", "u", Map.of(), false);
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        assertEquals("aem.content", decorated.getDomain());
        assertEquals("fragment.published", decorated.getType());
        assertEquals(12345L, decorated.getTimestamp());
    }

    @Test
    public void systemCommitUserIdIsOakUnknown() {
        AuditEvent in = original("security", "system.event", Map.of());
        // CommitInfo with null userId resolves to OAK_UNKNOWN
        CommitInfo info = CommitInfo.EMPTY;
        AuditEvent decorated = CommitMetadataDecorator.decorate(List.of(in), info).get(0);
        // CommitInfo.OAK_UNKNOWN exposed via getUserId() for empty/system commits
        assertEquals(CommitInfo.OAK_UNKNOWN, decorated.getPayload().get("commit.userId"));
    }

    @Test
    public void preservesOrderOfEvents() {
        AuditEvent a = original("security", "a", Map.of());
        AuditEvent b = original("security", "b", Map.of());
        AuditEvent c = original("security", "c", Map.of());
        CommitInfo info = new CommitInfo("s", "u", Map.of(), false);
        List<AuditEvent> out = CommitMetadataDecorator.decorate(asList(a, b, c), info);
        assertEquals("a", out.get(0).getType());
        assertEquals("b", out.get(1).getType());
        assertEquals("c", out.get(2).getType());
    }
}
```

### Step 11.2 — Run test, verify it fails

- [ ] Run: `mvn test -pl oak-core -Dtest=CommitMetadataDecoratorTest`

Expected: COMPILATION FAILURE — `CommitMetadataDecorator` class does not exist.

### Step 11.3 — Implement `CommitMetadataDecorator`

```java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Decorates audit-event payloads with commit metadata (sessionId, userId,
 * timestamp) at drain time. Used exclusively by the commit-attached
 * dispatch path. Fire-and-forget events bypass this decoration.
 * <p>
 * The decorator returns NEW {@link AuditEvent} instances that wrap the
 * originals; the input events are not mutated. The wrapper's payload map
 * is unmodifiable.
 */
final class CommitMetadataDecorator {

    static final String KEY_SESSION_ID = "commit.sessionId";
    static final String KEY_USER_ID = "commit.userId";
    static final String KEY_TIMESTAMP = "commit.timestamp";

    private CommitMetadataDecorator() {
        // utility class
    }

    static @NotNull List<AuditEvent> decorate(@NotNull List<AuditEvent> events,
                                              @NotNull CommitInfo info) {
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        String sessionId = info.getSessionId();
        String userId = info.getUserId();
        long timestamp = info.getDate();
        List<AuditEvent> out = new ArrayList<>(events.size());
        for (AuditEvent e : events) {
            out.add(new DecoratedAuditEvent(e, sessionId, userId, timestamp));
        }
        return out;
    }

    private static final class DecoratedAuditEvent implements AuditEvent {

        private final AuditEvent delegate;
        private final Map<String, Object> payload;

        DecoratedAuditEvent(@NotNull AuditEvent delegate,
                            @NotNull String sessionId,
                            @NotNull String userId,
                            long commitTimestamp) {
            this.delegate = delegate;
            Map<String, Object> merged = new HashMap<>(delegate.getPayload());
            merged.put(KEY_SESSION_ID, sessionId);
            merged.put(KEY_USER_ID, userId);
            merged.put(KEY_TIMESTAMP, commitTimestamp);
            this.payload = Collections.unmodifiableMap(merged);
        }

        @Override public @NotNull String getDomain() { return delegate.getDomain(); }
        @Override public @NotNull String getType() { return delegate.getType(); }
        @Override public long getTimestamp() { return delegate.getTimestamp(); }
        @Override public @NotNull Map<String, Object> getPayload() { return payload; }
    }
}
```

### Step 11.4 — Run test, verify it passes

- [ ] Run: `mvn test -pl oak-core -Dtest=CommitMetadataDecoratorTest`

Expected: BUILD SUCCESS, 5 tests pass.

### Step 11.5 — Wire decorator into `DispatchAuditEventsHook`

- [ ] Open `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/DispatchAuditEventsHook.java`. Find the placeholder `decorate` method (Task 10.6 placed it). Replace its body:

```java
    private static @NotNull List<AuditEvent> decorate(@NotNull List<AuditEvent> events,
                                                      @NotNull CommitInfo info) {
        return CommitMetadataDecorator.decorate(events, info);
    }
```

### Step 11.6 — Build oak-core

- [ ] Run: `mvn test -pl oak-core -Dtest=CommitMetadataDecoratorTest`

Expected: BUILD SUCCESS.

### Step 11.7 — Commit

```bash
git add oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/CommitMetadataDecorator.java \
        oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/DispatchAuditEventsHook.java \
        oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/CommitMetadataDecoratorTest.java
git commit -m "OAK-XXXXX: decorate commit-attached audit events with commit.* metadata"
```

---

## Task 12: Implement `AuditEventEmitterImpl` and wire fire-and-forget dispatch

**Files:**
- Create: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditEventEmitterImpl.java`
- Create: `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/AuditEventEmitterImplTest.java`
- Modify: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditConfigurationImpl.java` — Sink `dispatch` body

### Step 12.1 — Write the failing test for `AuditEventEmitterImpl`

```java
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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEvents;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AuditEventEmitterImplTest {

    private AtomicReference<AuditEvent> dispatched;

    @Before
    public void installSink() {
        dispatched = new AtomicReference<>();
        AuditEvents.install(new AuditEvents.Sink() {
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isEnabledFor(@NotNull String domain) { return "yes".equals(domain); }
            @Override public void record(@NotNull Root root, @NotNull AuditEvent event) { /* unused */ }
            @Override public void dispatch(@NotNull AuditEvent event) { dispatched.set(event); }
        });
    }

    @After
    public void tearDown() {
        AuditEvents.install(null);
    }

    private static AuditEvent fixedEvent(@NotNull String domain) {
        return new AuditEvent() {
            @Override public @NotNull String getDomain() { return domain; }
            @Override public @NotNull String getType() { return "t"; }
            @Override public long getTimestamp() { return 0L; }
            @Override public @NotNull Map<String, Object> getPayload() { return Collections.emptyMap(); }
        };
    }

    @Test
    public void emitRoutesToFacadeDispatch() {
        AuditEventEmitterImpl impl = new AuditEventEmitterImpl();
        AuditEvent e = fixedEvent("yes");
        impl.emit(e);
        assertSame(e, dispatched.get());
    }

    @Test
    public void isEnabledForRoutesToFacade() {
        AuditEventEmitterImpl impl = new AuditEventEmitterImpl();
        assertTrue(impl.isEnabledFor("yes"));
        assertFalse(impl.isEnabledFor("no"));
    }
}
```

### Step 12.2 — Run test, verify it fails

- [ ] Run: `mvn test -pl oak-core -Dtest=AuditEventEmitterImplTest`

Expected: COMPILATION FAILURE — `AuditEventEmitterImpl` class does not exist.

### Step 12.3 — Implement `AuditEventEmitterImpl`

```java
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

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventEmitter;
import org.apache.jackrabbit.oak.spi.audit.AuditEvents;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;

/**
 * OSGi service implementation of {@link AuditEventEmitter}. Thin wrapper
 * around the static {@link AuditEvents} façade so consumer bundles do not
 * need to know about the façade.
 * <p>
 * A single instance is registered per OSGi container at activation of
 * the audit module; all consumers receive the same instance via
 * {@code @Reference AuditEventEmitter}.
 */
@Component(service = AuditEventEmitter.class)
public class AuditEventEmitterImpl implements AuditEventEmitter {

    @Override
    public void emit(@NotNull AuditEvent event) {
        AuditEvents.dispatch(event);
    }

    @Override
    public boolean isEnabledFor(@NotNull String domain) {
        return AuditEvents.isEnabledFor(domain);
    }
}
```

### Step 12.4 — Run test, verify it passes

- [ ] Run: `mvn test -pl oak-core -Dtest=AuditEventEmitterImplTest`

Expected: BUILD SUCCESS, 2 tests pass.

### Step 12.5 — Wire the Sink's `dispatch` in `AuditConfigurationImpl`

- [ ] Open `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditConfigurationImpl.java`. Find the Sink's `dispatch` method (currently throws `UnsupportedOperationException` from Task 10.5). Replace its body:

```java
@Override
public void dispatch(@NotNull AuditEvent event) {
    if (!featureToggle.isEnabled()) {
        return;
    }
    List<AuditEventListener> listeners = registry.getListeners();
    if (listeners.isEmpty()) {
        return;
    }
    String domain = event.getDomain();
    List<AuditEvent> single = Collections.singletonList(event);
    for (AuditEventListener listener : listeners) {
        if (!domain.equals(listener.getDomain())) {
            continue;
        }
        try {
            listener.onEvents(single);
        } catch (RuntimeException re) {
            log.warn("AuditEventListener {} failed on fire-and-forget dispatch in domain '{}'; swallowing.",
                    listener.getClass().getName(), domain, re);
        }
    }
}
```

Add the necessary imports at the top of the file:

```java
import java.util.Collections;
import java.util.List;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
```

If a logger field doesn't already exist on the impl, add:

```java
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuditConfigurationImpl.class);
```

### Step 12.6 — Build oak-core

- [ ] Run: `mvn install -pl oak-core -DskipTests`

Expected: BUILD SUCCESS.

### Step 12.7 — Commit

```bash
git add oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditEventEmitterImpl.java \
        oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditConfigurationImpl.java \
        oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/AuditEventEmitterImplTest.java
git commit -m "OAK-XXXXX: add AuditEventEmitterImpl and wire fire-and-forget dispatch"
```

---

## Task 13: Adapt `WhiteboardAuditEventListenerRegistry` tests to `onEvents`

If the Path α skeleton's registry test exists in `.claude/oak/plans/audit-spi/history/04-tests-and-benchmarks.md` it asserts `onCommit`. The registry's public API is unchanged but listener implementations now use `onEvents`.

**Files:**
- Create: `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/WhiteboardAuditEventListenerRegistryTest.java`

### Step 13.1 — Write the test

```java
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

import java.util.List;

import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.apache.jackrabbit.oak.spi.whiteboard.DefaultWhiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WhiteboardAuditEventListenerRegistryTest {

    private static final class StubListener implements AuditEventListener {
        private final String domain;
        private final int rank;
        StubListener(String domain, int rank) {
            this.domain = domain;
            this.rank = rank;
        }
        @Override public @NotNull String getDomain() { return domain; }
        @Override public int getRank() { return rank; }
        @Override public void onEvents(@NotNull List<AuditEvent> events) { }
    }

    @Test
    public void emptyByDefault() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry(wb);
        reg.start();
        try {
            assertEquals(0, reg.getListeners().size());
            assertTrue(reg.getActiveDomains().isEmpty());
        } finally {
            reg.stop();
        }
    }

    @Test
    public void registeredListenerIsListed() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry(wb);
        reg.start();
        try {
            wb.register(AuditEventListener.class, new StubListener("security", 0), java.util.Map.of());
            assertEquals(1, reg.getListeners().size());
            assertTrue(reg.getActiveDomains().contains("security"));
        } finally {
            reg.stop();
        }
    }

    @Test
    public void listenersSortedByRankDescending() {
        Whiteboard wb = new DefaultWhiteboard();
        WhiteboardAuditEventListenerRegistry reg = new WhiteboardAuditEventListenerRegistry(wb);
        reg.start();
        try {
            wb.register(AuditEventListener.class, new StubListener("d", 1), java.util.Map.of());
            wb.register(AuditEventListener.class, new StubListener("d", 10), java.util.Map.of());
            wb.register(AuditEventListener.class, new StubListener("d", 5), java.util.Map.of());
            List<AuditEventListener> sorted = reg.getListeners();
            assertEquals(3, sorted.size());
            assertEquals(10, sorted.get(0).getRank());
            assertEquals(5, sorted.get(1).getRank());
            assertEquals(1, sorted.get(2).getRank());
        } finally {
            reg.stop();
        }
    }
}
```

### Step 13.2 — Run test

- [ ] Run: `mvn test -pl oak-core -Dtest=WhiteboardAuditEventListenerRegistryTest`

Expected: BUILD SUCCESS, 3 tests pass. If a method signature differs from the skeleton, adapt the test (`getListeners()`, `getActiveDomains()`, `start()`, `stop()` are stable). If the registry's constructor or lifecycle differs, the test reveals it; adapt.

### Step 13.3 — Commit

```bash
git add oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/WhiteboardAuditEventListenerRegistryTest.java
git commit -m "OAK-XXXXX: test registry with onEvents-based listeners"
```

---

## Task 14: End-to-end integration test — both pipelines through one listener

**Files:**
- Create: `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/AuditPipelineIT.java`

### Step 14.1 — Write the integration test

```java
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.ContentRepository;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.audit.AuditEvent;
import org.apache.jackrabbit.oak.spi.audit.AuditEventEmitter;
import org.apache.jackrabbit.oak.spi.audit.AuditEventListener;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration test exercising both audit pipelines through a
 * single {@link AuditEventListener#onEvents} method. Uses {@link MemoryNodeStore}
 * for a real but in-process Oak.
 */
public class AuditPipelineIT {

    private ContentRepository repository;
    private List<AuditEvent> received;
    private AuditEventListener listener;
    private AuditEventEmitter emitter;

    @Before
    public void setUp() throws Exception {
        // TODO: caller of this plan — wire up the test Oak instance per the
        // existing test fixture pattern in oak-core. The skeleton has
        // `MutableRootAuditIntegrationTest` precedent in
        // .claude/oak/plans/audit-spi/history/04-tests-and-benchmarks.md. Wire up:
        //   1) Oak instance with MemoryNodeStore
        //   2) AuditConfigurationImpl registered as SecurityConfiguration
        //   3) Whiteboard with our listener registered
        //   4) AuditEventEmitterImpl instance via direct construction (or DI)
        // The two-line wiring below is illustrative; adapt to oak-core's
        // actual test fixtures:
        //
        //   Oak oak = new Oak(new MemoryNodeStore())
        //       .with(new OpenSecurityProvider())
        //       .with(auditConfigurationImpl);
        //   repository = oak.createContentRepository();
        //   emitter = new AuditEventEmitterImpl();
        //
        // until that's in place, this test will fail with NPE at the first
        // repository.login(...) and the implementer fills the fixture.
        received = new CopyOnWriteArrayList<>();
        listener = new AuditEventListener() {
            @Override public @NotNull String getDomain() { return "test.domain"; }
            @Override public void onEvents(@NotNull List<AuditEvent> events) { received.addAll(events); }
        };
    }

    @After
    public void tearDown() {
        // unregister listener, stop Oak instance
    }

    private static AuditEvent fixedEvent(@NotNull String type, @NotNull Map<String, Object> payload) {
        return new AuditEvent() {
            @Override public @NotNull String getDomain() { return "test.domain"; }
            @Override public @NotNull String getType() { return type; }
            @Override public long getTimestamp() { return System.currentTimeMillis(); }
            @Override public @NotNull Map<String, Object> getPayload() { return payload; }
        };
    }

    @Test
    public void fireAndForgetEventCarriesNoCommitMetadata() {
        emitter.emit(fixedEvent("forget", Map.of("key", "v")));
        assertEquals(1, received.size());
        AuditEvent e = received.get(0);
        assertEquals("forget", e.getType());
        assertFalse("fire-and-forget event must not carry commit.sessionId",
                e.getPayload().containsKey("commit.sessionId"));
        assertFalse(e.getPayload().containsKey("commit.userId"));
        assertEquals("v", e.getPayload().get("key"));
    }

    // Additional tests:
    //   commitAttachedEventCarriesCommitMetadata()
    //     — record via AuditEvents.record(root, event), session.save(),
    //       assert payload has commit.sessionId / userId / timestamp populated.
    //   commitFailureDropsEvent()
    //     — record, force commit failure (e.g. validator), assert no listener
    //       invocation occurred.
    //   listenerExceptionDoesNotPropagate()
    //     — register a listener that throws RuntimeException, emit; the call
    //       returns cleanly and other listeners (or the same on retry) work.

    @Test
    public void emitNoListenerForDomainIsNoOp() {
        // Listener registered for "test.domain"; emit different domain.
        emitter.emit(new AuditEvent() {
            @Override public @NotNull String getDomain() { return "other.domain"; }
            @Override public @NotNull String getType() { return "x"; }
            @Override public long getTimestamp() { return 0L; }
            @Override public @NotNull Map<String, Object> getPayload() { return Collections.emptyMap(); }
        });
        assertTrue(received.isEmpty());
    }
}
```

### Step 14.2 — Note for the implementer

- [ ] The `@Before setUp()` method has explicit TODO comments. The implementer must wire the Oak instance following the existing test pattern. If the pattern from `.claude/oak/plans/audit-spi/history/04-tests-and-benchmarks.md` §2 is available, adopt it. Otherwise look at any existing oak-core integration test using `MemoryNodeStore` + `OpenSecurityProvider` and adapt.

### Step 14.3 — Run test

- [ ] Run: `mvn test -pl oak-core -Dtest=AuditPipelineIT`

Expected: tests `fireAndForgetEventCarriesNoCommitMetadata`, `emitNoListenerForDomainIsNoOp` pass. The commit-attached scenarios should be fleshed out per the comments and run after the fixture is wired.

### Step 14.4 — Commit

```bash
git add oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/AuditPipelineIT.java
git commit -m "OAK-XXXXX: integration test for cross-stack audit pipeline"
```

---

## Task 15: Documentation — `oak-doc/src/site/markdown/security/audit.md`

**Files:**
- Create: `oak-doc/src/site/markdown/security/audit.md`

### Step 15.1 — Write the documentation

Write a public-facing markdown file that documents:
- What the audit SPI is for (compliance, SIEM forwarding, custom AEM domain events).
- The two pipelines (commit-attached, fire-and-forget) with one example each.
- How to implement a listener (one `onEvents` method, payload keys to look for).
- How to emit events from an AEM/Sling bundle (the `@Reference AuditEventEmitter` snippet from §8 of `08-cross-stack-audit-design.md`).
- The trust model — caller-asserted, what consumers must know.

Use the existing `oak-doc/src/site/markdown/security/` files as style precedent (e.g., `overview.md`, `introduction.md`).

### Step 15.2 — Validate doc renders

- [ ] Run: `mvn -Pdoc -pl oak-doc clean install -DskipTests`

Expected: BUILD SUCCESS. The doc site is generated under `oak-doc/target/site/`.

### Step 15.3 — Commit

```bash
git add oak-doc/src/site/markdown/security/audit.md
git commit -m "OAK-XXXXX: document the cross-stack audit SPI"
```

---

## Task 16: Full reactor build + targeted full test run

**Files:** (validation only — no changes)

### Step 16.1 — Full reactor build

- [ ] Run: `mvn clean install -DskipTests -Pfast` from the repo root.

Expected: BUILD SUCCESS for all modules. This catches any cross-module compile breakage from the SPI move.

### Step 16.2 — Run all tests in changed modules

- [ ] Run: `mvn test -pl oak-audit-spi,oak-security-spi,oak-core`

Expected: ALL TESTS PASS. Coverage gate on `oak-audit-spi` (0.99 line / 1.0 branch) is enforced.

### Step 16.3 — Verify oak-core coverage threshold not regressed

- [ ] Run: `mvn verify -pl oak-core -Pcoverage -Dskip.coverage=false`

Expected: coverage report generated; no regression vs the threshold configured in `oak-core/pom.xml`.

### Step 16.4 — Final commit if anything was adjusted to make Step 16.1 / 16.2 / 16.3 pass

```bash
# Only commit if changes were made; otherwise skip this step.
git add -p   # review individual hunks
git commit -m "OAK-XXXXX: fix reactor build / coverage after cross-stack audit refactor"
```

---

## Self-Review Notes

**Spec coverage** — every §1–§14 section in `08-cross-stack-audit-design.md` maps to a task:

- §1 Goals & non-goals → out of scope for impl (design rationale)
- §2 Architecture overview → Tasks 1, 6, 9, 10, 12 (module + impl + wiring)
- §3 Module layout → Tasks 1, 6, 7, 8, 9
- §4 SPI surface → Tasks 2 (`AuditEvent`), 3 (`AuditEventListener`), 4 (`AuditEvents`), 5 (`AuditEventEmitter`)
- §5 Commit-attached pipeline + payload decoration → Tasks 10, 11
- §6 Fire-and-forget pipeline + `AuditEventEmitterImpl` → Task 12
- §7 End-to-end sequence → Task 14 (integration test)
- §8 AEM caller skeletons → Task 15 (documentation includes them)
- §9 Trust model → captured in Javadoc (Tasks 3, 5) + doc (Task 15)
- §10 Migration from Path α → Tasks 6, 8, 10 (port + adapt)
- §11 Test strategy outline → Tasks 3, 4, 11, 12, 13, 14
- §12 Deferred items → not in scope
- §13 Process note → not in scope for code (was for prior cycle's retro)
- §14 Summary → no impl artifact

**Placeholder scan** — Step 14.1's `setUp()` has explicit TODO comments calling out the fixture wiring. This is deliberate — the existing oak-core test fixture pattern is reused, not invented here, and the implementer needs to pick the right pattern from existing tests. All other steps have complete code.

**Type / method consistency** — checked:
- `AuditEvent` interface in `org.apache.jackrabbit.oak.spi.audit` (Tasks 2, 11, 12)
- `AuditEventListener.onEvents(List<AuditEvent>)` (Tasks 3, 10.4, 10.6, 12.5, 13, 14)
- `AuditEventEmitter` (Tasks 5, 12)
- `AuditEvents.dispatch(AuditEvent)` (Tasks 4, 12.5)
- `CommitMetadataDecorator.decorate(List<AuditEvent>, CommitInfo)` returns `List<AuditEvent>` (Tasks 11, 11.5)
- Payload keys `commit.sessionId`, `commit.userId`, `commit.timestamp` (Tasks 11, 14, 15)

Consistent across all tasks.

---

End of plan.
