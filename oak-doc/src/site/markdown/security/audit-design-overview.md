# Audit pipeline — design at a glance

A short, plain-text view of the audit pipeline for PR descriptions and
terminal `cat`. For the full spec see [`audit-design.md`](audit-design.md);
for the user-facing guide see [`audit.md`](audit.md).

## Summary

Two producer paths feed a single listener registry. Both paths converge on
one method: `AuditEventListener.onEvents(List<AuditEvent>)`. Pipeline
state is owned by `AuditConfigurationImpl` in `oak-core`, which holds the
`FT_AUDIT` `Feature` toggle, the `AuditBuffer`, the
`WhiteboardAuditEventListenerRegistry`, the `BufferSink` (installed into
`AuditEvents.Sink`), and the singleton `AuditDrainObserver`.

- **Commit-attached path** — Oak-internal capture (e.g. `UserManagerImpl`)
  buffers events in a per-session `ThreadLocal` during a session write,
  then drains and dispatches them on commit success via an `Observer`
  (`AuditDrainObserver`) attached to the root `NodeStore`. The drain fires
  synchronously on the commit thread, **after** durable persistence.
  Events are decorated with `commit.sessionId` / `commit.userId` /
  `commit.timestamp` before dispatch. Failed commits drop the buffer.
- **Fire-and-forget path** — Any OSGi bundle resolves `AuditEventEmitter`
  via `@Reference` and calls `emit(event)`. The event is dispatched
  immediately on the calling thread; no buffering, no commit boundary, no
  payload decoration.

Failure isolation: an OUTER `Throwable` barrier in
`AuditDrainObserver.contentChanged` ensures the audit pipeline cannot mask
itself as a commit failure (Oak's `CompositeObserver` has no per-observer
isolation). An INNER per-listener `Throwable` barrier on **both** paths
ensures one misbehaving listener cannot stop the others.

## Pipeline diagram

```
Commit-attached path (Oak-internal capture sites)

  UserManagerImpl.recordSingleMembershipAuditEvent      [oak-core]
      |
      v
  UserAuditEvents.memberAdded(groupPath, memberId, memberPath)  [oak-core, package-private]
      |
      v
  AuditEvents.record(root, event)                       [oak-core-spi static facade]
      |
      v
  BufferSink.record(root, event)                        [oak-core, installed Sink;
      |                                                  domain-listener gate]
      v
  ThreadLocal AuditBuffer                               [keyed by ContentSession id]
      |
      | ... session calls Root.commit() ...
      |   MutableRoot.commit -> NodeStore.merge -> durable persistence
      |
      v  (synchronous, same thread, via ChangeDispatcher)
  AuditDrainObserver.contentChanged(root, info)         [oak-core; OUTER Throwable barrier]
      |
      |-- if info.isExternal()   -> return (no-op)
      |-- if FT_AUDIT disabled   -> return
      |-- if buffer drain empty  -> return
      |-- if no listeners        -> return
      |
      v
  CommitMetadataDecorator.decorate(events, info)        [adds commit.sessionId,
      |                                                  commit.userId, commit.timestamp]
      v
  WhiteboardAuditEventListenerRegistry.getListeners()   [sorted by rank, desc]
      |
      |  for each listener whose getDomain() matches event.getDomain():
      |  INNER per-listener Throwable barrier wraps the call below
      v
  AuditEventListener.onEvents(List<AuditEvent>)         [consumer bundles]


Fire-and-forget path (any OSGi bundle)

  bundle (e.g. ContentFragmentAuditor)
      |
      |  @Reference AuditEventEmitter
      v
  AuditEventEmitterImpl.emit(event)                     [oak-core, OSGi @Component]
      |
      v
  AuditEvents.dispatch(event)                           [oak-core-spi static facade]
      |
      v
  BufferSink.dispatch(event)                            [oak-core, installed Sink]
      |
      |-- if FT_AUDIT disabled   -> return
      |-- if no listeners        -> return
      |
      |  strip caller-supplied reserved commit.* attestation keys
      |  for each listener: INNER per-listener Throwable barrier wraps
      |  the getDomain() match and the onEvents() call below
      v
  AuditEventListener.onEvents([event])                  [no decoration beyond the strip]
```

## Key components

| Component | Module | Role |
|---|---|---|
| `AuditEvent` | `oak-core-spi` | Generic event interface — domain, type, timestamp, payload. |
| `AuditEventListener` | `oak-core-spi` | Consumer SPI — `onEvents(List<AuditEvent>)`, scoped to a single domain (`getDomain()`), ordered by `getRank()`. |
| `AuditEventEmitter` | `oak-core-spi` | OSGi service surface for fire-and-forget emission from any bundle. |
| `AuditEvents` | `oak-core-spi` | Static facade — `record(root, event)` / `dispatch(event)`. Routes to the installed `Sink`. |
| `AuditEvents.Sink` | `oak-core-spi` | SPI for the pipeline implementation. Wired by `AuditConfigurationImpl` to a `BufferSink`. |
| `AuditBufferLifecycle` | `oak-core-spi` | `MutableRoot` lifecycle callouts — drain on refresh/rebase/commit-fail. |
| `AuditConfiguration` | `oak-core-spi` | Typed handle on the pipeline's runtime state (`isActive()`, `NAME`, `NOOP`). NOT a `SecurityConfiguration`. |
| `SecurityAuditDomain` | `oak-security-spi` (`spi/security/audit/`) | Security-domain constant — `NAME = "oak.security"`. |
| `UserAuditTypes` | `oak-security-spi` (`spi/security/user/`) | Per-sub-domain type-string + payload-key constants for user-membership events. |
| `UserAuditEvents` | `oak-core` (`security/user/`, package-private) | Producer-side factory used by `UserManagerImpl` capture sites. |
| `AuditBuffer` | `oak-core` | `ThreadLocal` per-session event buffer. |
| `BufferSink` | `oak-core` (in `AuditConfigurationImpl`) | `AuditEvents.Sink` impl — gates on `FT_AUDIT` + listener presence, buffers on `record`, dispatches inline on `dispatch`. |
| `AuditDrainObserver` | `oak-core` | `NodeStore` `Observer` that drains the buffer on commit success. OUTER + INNER `Throwable` barriers. |
| `CommitMetadataDecorator` | `oak-core` | Stamps the three reserved `commit.*` attestation entries at drain time (commit-attached) and strips caller-supplied values for the same keys at dispatch (fire-and-forget). |
| `AuditEventEmitterImpl` | `oak-core` | OSGi `@Component` implementing `AuditEventEmitter`. Delegates to `AuditEvents.dispatch`. |
| `WhiteboardAuditEventListenerRegistry` | `oak-core` | Tracks `AuditEventListener` services on the Whiteboard. `getListeners()` returns by-rank-desc; `hasListenerFor(domain)` is the per-domain pre-allocation gate consulted via `BufferSink.isEnabledFor(domain)`. |
| `AuditConfigurationImpl` | `oak-core` | Pipeline owner — `FT_AUDIT` toggle, buffer, registry, sink, drain observer. OSGi-published as `AuditConfiguration`. |

## Where to learn more

- [`audit-design.md`](audit-design.md) — full design spec (SPI shape, OSGi/embedded wiring, threading invariants, test patterns).
- [`audit.md`](audit.md) — user-facing Oak documentation (listener contract, payload conventions, trust model).
- [`audit-performance.md`](audit-performance.md) — benchmark results and reproduction recipes.
