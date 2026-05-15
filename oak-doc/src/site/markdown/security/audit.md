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

Audit SPI
--------------------------------------------------------------------------------

### General

The Oak audit SPI is a domain-neutral framework for recording structured events
about repository activity and dispatching them to in-process consumers.
Listeners are registered on the OSGi Whiteboard and invoked synchronously when
events are produced; typical consumers forward events to a SIEM, write to a
compliance archive, or apply runtime policy.

The SPI is intentionally minimal. It defines an event type, a listener
interface, and an emitter service. It does not prescribe transport, persistence,
or out-of-process delivery — those are listener concerns.

Two producer paths feed a single listener registry:

- A **commit-attached** path used by Oak-internal capture sites
  (e.g. `UserManagerImpl`). Events are buffered for the duration of a session
  write, drained on successful `Root.commit()`, and decorated with commit
  metadata before dispatch. Events are dropped if the commit fails.
- A **fire-and-forget** path exposed to any OSGi bundle through the
  [AuditEventEmitter] service. Events are dispatched immediately on the calling
  thread; they are not tied to a commit and are not buffered.

Both paths converge on the same `AuditEventListener.onEvents(List<AuditEvent>)`
method. A single listener can therefore consume Oak-internal security events
and bundle-emitted custom events through one entry point.

<a name="modules"></a>
### Module Layout

| Module | Role |
|---|---|
| `oak-audit-spi`     | Domain-neutral SPI: [AuditEvent], [AuditEventListener], [AuditEventEmitter], `AuditEvents` static façade. |
| `oak-security-spi`  | Security-specific event subclasses (`SecurityAuditEvent`, `MemberAddedEvent`, …) and the `security` domain constant. Depends on `oak-audit-spi`. |
| `oak-core`          | Pipeline implementation: listener registry, commit-attached buffer, drain hook, `AuditEventEmitterImpl`. |

Consumer bundles depend on `oak-audit-spi` only. No transitive dependency on
`oak-core`, `oak-jcr`, or `oak-security-spi` is required to implement a
listener or emit events.

<a name="event_model"></a>
### Event Model

#### AuditEvent

```java
public interface AuditEvent {
    @NotNull String getDomain();
    @NotNull String getType();
    long getTimestamp();
    @NotNull Map<String, Object> getPayload();
}
```

- **Domain** — namespace identifying the event source category. Built-in
  domains include `"security"` (defined by `SecurityAuditDomain.NAME`).
  Bundles defining new event types choose their own domain string; the SPI
  imposes no schema.
- **Type** — stable identifier within the domain (e.g.
  `"user.member.added"`). Used by consumers to dispatch on specific event
  shapes.
- **Timestamp** — milliseconds since epoch at the time the event was
  constructed.
- **Payload** — open map of supplementary data. Consumers MUST tolerate
  missing keys; producers MAY add keys without versioning.

Concrete event classes typically extend a domain-specific base. Security
events extend `SecurityAuditEvent` and live in
`org.apache.jackrabbit.oak.spi.security.audit`. Bundles emitting custom
events implement `AuditEvent` directly or define their own base class.

<a name="commit_metadata_keys"></a>
#### Commit metadata payload keys

Events produced by Oak's commit-attached pipeline are decorated at drain time
with three additional payload entries:

| Key | Value | Source |
|---|---|---|
| `commit.sessionId` | session identifier of the writing session | `CommitInfo.getSessionId()` |
| `commit.userId`    | acting user id (`CommitInfo.OAK_UNKNOWN` (`"oak:unknown"`) for system commits) | `CommitInfo.getUserId()` |
| `commit.timestamp` | commit timestamp in milliseconds since epoch     | `CommitInfo.getDate()` |

Events arriving through the fire-and-forget pipeline do NOT carry these keys.
Consumers that need to tell the two sources apart inspect for the presence of
`commit.sessionId`. The `commit.userId` value `CommitInfo.OAK_UNKNOWN`
(`"oak:unknown"`) is a deliberate anonymity marker for system commits;
listeners MUST NOT attempt to resolve it to a real user.

<a name="pipelines"></a>
### Pipelines

#### Commit-attached pipeline

Used by Oak-internal capture sites such as user-management API calls. Events
are buffered against the writing session and only dispatched when
`Root.commit()` succeeds. If validators reject the commit or the merge fails,
the buffered events are discarded.

The dispatch sequence is:

1. Capture site appends an event to the per-session ThreadLocal buffer.
2. The session calls `Root.commit()`; validators run.
3. On success, `DispatchAuditEventsHook` drains the buffer, decorates each
   event with `commit.sessionId`, `commit.userId`, `commit.timestamp`, and
   hands the list to the listener registry.
4. The registry sorts listeners by rank, then the dispatch path filters by
   domain and invokes each matching listener's `onEvents(List<AuditEvent>)`.

Capture sites inside Oak record events through the internal `AuditEvents`
façade; this path is not part of the public consumer surface. Bundles wishing
to emit events should use the fire-and-forget pipeline below.

#### Fire-and-forget pipeline

Used by any OSGi bundle that wants to record an event for its own domain.
Events fire immediately on the calling thread; there is no buffering and no
rollback. The pipeline is:

1. Caller resolves `AuditEventEmitter` via `@Reference`.
2. Caller gates allocation with `isEnabledFor(domain)`.
3. Caller invokes `emit(event)`.
4. The emitter forwards to the static façade, which routes to the listener
   registry. The registry sorts listeners by rank, then the dispatch path
   filters by domain and invokes each matching listener's
   `onEvents(List<AuditEvent>)`.

Properties:

- **No commit boundary.** The event is dispatched as soon as `emit` is called;
  subsequent JCR operations do not affect it.
- **Synchronous on the calling thread.** Listeners performing I/O are
  responsible for wrapping themselves in an async dispatcher.
- **Per-listener isolation.** Exceptions thrown by one listener are logged and
  swallowed; remaining listeners still run. `emit` never propagates a listener
  exception back to the caller.
- **No payload decoration.** The event reaches listeners with exactly the
  payload the caller provided. No `commit.*` keys are added.

<a name="emitting_events"></a>
### Emitting Events From A Bundle

Bundles emit events through the [AuditEventEmitter] OSGi service. A single
implementation is registered by `oak-core`.

```java
@Component
public class ContentFragmentAuditor {

    @Reference
    private AuditEventEmitter audit;

    public void onFragmentPublished(String path, String variation) {
        if (audit.isEnabledFor("aem.content")) {
            audit.emit(new ContentFragmentPublishedEvent(path, variation));
        }
    }
}
```

The `isEnabledFor` gate short-circuits when no listener is registered for the
domain, allowing callers to skip event construction on hot paths. The check
is cheap; producers SHOULD use it.

A minimal event implementation:

```java
class ContentFragmentPublishedEvent implements AuditEvent {

    private final String path;
    private final String variation;
    private final long timestamp = System.currentTimeMillis();

    ContentFragmentPublishedEvent(String path, String variation) {
        this.path = path;
        this.variation = variation;
    }

    @Override public String getDomain()              { return "aem.content"; }
    @Override public String getType()                { return "fragment.published"; }
    @Override public long getTimestamp()             { return timestamp; }
    @Override public Map<String, Object> getPayload() {
        return Map.of("path", path, "variation", variation);
    }
}
```

Events emitted this way are not tied to a JCR session or commit. The caller
need not hold a `Session` or `Root`; lifecycle events such as workflow
transitions, replication outcomes, or background-job completion are valid
producers:

```java
@Component
public class WorkflowAuditor {

    @Reference
    private AuditEventEmitter audit;

    public void onStepCompleted(String workflowId, String stepId) {
        if (audit.isEnabledFor("aem.workflow")) {
            audit.emit(new WorkflowStepCompletedEvent(workflowId, stepId));
        }
    }
}
```

<a name="implementing_a_listener"></a>
### Implementing A Listener

A listener is an OSGi component registered as a service of type
[AuditEventListener]. The Whiteboard registry discovers it automatically.

```java
@Component(service = AuditEventListener.class)
public class SiemForwarder implements AuditEventListener {

    @Override
    public String getDomain() {
        return "security";
    }

    @Override
    public int getRank() {
        return 0;
    }

    @Override
    public void onEvents(List<AuditEvent> events) {
        for (AuditEvent e : events) {
            Map<String, Object> p = e.getPayload();
            String sessionId = (String) p.get("commit.sessionId");
            String userId    = (String) p.get("commit.userId");
            siem.forward(e, sessionId, userId);
        }
    }
}
```

Contract notes:

- **`getDomain()`** is queried on every dispatch and MUST return a stable,
  non-null value across the listener's lifetime. A listener subscribes to
  exactly one domain. To consume multiple domains, register multiple
  listener components.
- **`getRank()`** orders listeners within a domain. Higher rank first.
  Default 0. Useful when one listener must observe state set by another (for
  example, a redaction listener running before a SIEM forwarder).
- **`onEvents(List<AuditEvent>)`** is invoked with a non-empty, non-null list
  of events in capture order. The same method serves both pipelines:
  commit-attached events arrive in a batch sized by the originating session's
  buffer; fire-and-forget events arrive in singleton lists.
- Implementations MUST be non-blocking. Expensive I/O belongs in an async
  wrapper owned by the listener.
- Implementations MUST tolerate unknown payload keys and missing optional
  keys. The payload schema is open.

<a name="trust_model"></a>
### Trust Model

The fire-and-forget producer surface is open by design.

- Any bundle that resolves `AuditEventEmitter` can emit any event for any
  domain, including `"security"`. There is no compile-time check, no reserved
  domain registry, and no runtime gate on the emitting bundle.
- Listeners therefore receive caller-asserted data. An event arriving through
  `onEvents` reflects the emitting bundle's claim, not Oak-verified truth.
- Oak does not verify, sign, or annotate events with their originating
  bundle. Consumers that require Oak attestation MUST distinguish events at
  the consumer side.

The distinguishing signal is payload-based: events produced by Oak's
commit-attached pipeline carry the `commit.sessionId`, `commit.userId`, and
`commit.timestamp` keys; fire-and-forget events do not. A SIEM forwarder that
treats only the former as Oak-attested mutations is operating within the
contract.

The trade-off is explicit. The team explored a stricter design enforcing
compile-time reserved domains and typed event subclasses, but settled on the
open surface to let any higher-stack bundle emit on its own schedule. This
keeps Oak out of the per-bundle policy decision and shifts allowlisting to
the consumer side, where the deployment owner already controls listener
registration.

Recommended consumer-side discipline:

| Need | Approach |
|---|---|
| Distinguish Oak-attested mutations from caller-asserted events. | Inspect for `commit.sessionId` in the payload. Present implies commit-attached. |
| Restrict trusted producers. | Maintain a consumer-side allowlist of trusted domain prefixes and reject unknown domains. |
| Compliance audit (Oak-verified writes only). | Subscribe to `"security"` and filter for events carrying the `commit.*` keys. |

<!-- references -->
[AuditEvent]: /oak/docs/apidocs/org/apache/jackrabbit/oak/spi/audit/AuditEvent.html
[AuditEventListener]: /oak/docs/apidocs/org/apache/jackrabbit/oak/spi/audit/AuditEventListener.html
[AuditEventEmitter]: /oak/docs/apidocs/org/apache/jackrabbit/oak/spi/audit/AuditEventEmitter.html
