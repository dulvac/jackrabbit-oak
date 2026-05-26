# Audit pipeline — design at a glance

A short, plain-text view of the audit pipeline for PR descriptions and
terminal `cat`. For the full spec see [`design.md`](design.md); for the
user-facing guide see
[`oak-doc/src/site/markdown/security/audit.md`](../../oak-doc/src/site/markdown/security/audit.md).

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
  UserAuditEvents.memberAdded(groupPath, memberPath)    [oak-core, package-private]
      |
      v
  AuditEvents.record(root, event)                       [oak-audit-spi static facade]
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
  AuditEvents.dispatch(event)                           [oak-audit-spi static facade]
      |
      v
  BufferSink.dispatch(event)                            [oak-core, installed Sink]
      |
      |-- if FT_AUDIT disabled   -> return
      |-- if no listeners        -> return
      |
      |  for each listener whose getDomain() matches event.getDomain():
      |  INNER per-listener Throwable barrier wraps the call below
      v
  AuditEventListener.onEvents([event])                  [no payload decoration]
```

## Key components

| Component | Module | Role |
|---|---|---|
| `AuditEvent` | `oak-audit-spi` | Generic event interface — domain, type, timestamp, payload. |
| `AuditEventListener` | `oak-audit-spi` | Consumer SPI — `onEvents(List<AuditEvent>)`, scoped to a single domain (`getDomain()`), ordered by `getRank()`. |
| `AuditEventEmitter` | `oak-audit-spi` | OSGi service surface for fire-and-forget emission from any bundle. |
| `AuditEvents` | `oak-audit-spi` | Static facade — `record(root, event)` / `dispatch(event)`. Routes to the installed `Sink`. |
| `AuditEvents.Sink` | `oak-audit-spi` | SPI for the pipeline implementation. Wired by `AuditConfigurationImpl` to a `BufferSink`. |
| `AuditBufferLifecycle` | `oak-audit-spi` | `MutableRoot` lifecycle callouts — drain on refresh/rebase/commit-fail. |
| `AuditConfiguration` | `oak-audit-spi` | Typed handle on the pipeline's runtime state (`isActive()`, `NAME`, `NOOP`). NOT a `SecurityConfiguration`. |
| `SecurityAuditDomain` | `oak-security-spi` (`spi/security/audit/`) | Security-domain constant — `NAME = "oak.security"`. |
| `UserAuditTypes` | `oak-security-spi` (`spi/security/user/`) | Per-sub-domain type-string + payload-key constants for user-membership events. |
| `UserAuditEvents` | `oak-core` (`security/user/`, package-private) | Producer-side factory used by `UserManagerImpl` capture sites. |
| `AuditBuffer` | `oak-core` | `ThreadLocal` per-session event buffer. |
| `BufferSink` | `oak-core` (in `AuditConfigurationImpl`) | `AuditEvents.Sink` impl — gates on `FT_AUDIT` + listener presence, buffers on `record`, dispatches inline on `dispatch`. |
| `AuditDrainObserver` | `oak-core` | `NodeStore` `Observer` that drains the buffer on commit success. OUTER + INNER `Throwable` barriers. |
| `CommitMetadataDecorator` | `oak-core` | Adds `commit.*` payload entries on the commit-attached path. |
| `AuditEventEmitterImpl` | `oak-core` | OSGi `@Component` implementing `AuditEventEmitter`. Delegates to `AuditEvents.dispatch`. |
| `WhiteboardAuditEventListenerRegistry` | `oak-core` | Tracks `AuditEventListener` services on the Whiteboard. `getListeners()` returns by-rank-desc; `hasListenerFor(domain)` is the per-domain pre-allocation gate consulted via `BufferSink.isEnabledFor(domain)`. |
| `AuditConfigurationImpl` | `oak-core` | Pipeline owner — `FT_AUDIT` toggle, buffer, registry, sink, drain observer. OSGi-published as `AuditConfiguration`. |

## Where to learn more

- [`design.md`](design.md) — full design spec (SPI shape, OSGi/embedded wiring, threading invariants, test patterns).
- [`oak-doc/src/site/markdown/security/audit.md`](../../oak-doc/src/site/markdown/security/audit.md) — user-facing Oak documentation (listener contract, payload conventions, trust model).
- [`performance/README.md`](performance/README.md) — benchmark results and reproduction recipes.
- [`pr-description.md`](pr-description.md) — PR body draft.
