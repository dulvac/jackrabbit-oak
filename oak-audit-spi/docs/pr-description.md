# PR description — Oak audit SPI

Draft body for the GitHub PR. Lives next to the canonical design spec
for archaeology.

---

## Title

```
OAK-XXXXX: audit-spi — Observer-based commit-attached audit event pipeline
```

(JIRA number TBD per team-lead's filing cadence.)

## Design at a glance

For a one-page text view of the pipeline — both producer paths,
key components, and module layering — see
[`oak-audit-spi/docs/design-overview.md`](https://github.com/dulvac/jackrabbit-oak/blob/audit-spi/observer-drain/oak-audit-spi/docs/design-overview.md).
Full design spec is in
[`oak-audit-spi/docs/design.md`](https://github.com/dulvac/jackrabbit-oak/blob/audit-spi/observer-drain/oak-audit-spi/docs/design.md).

## Summary

Adds a structured audit-event pipeline to Oak. Capture sites (Oak-internal
or any OSGi bundle resolving the emitter) produce immutable `AuditEvent`
records that are dispatched to bundle-registered `AuditEventListener`
consumers. Two delivery paths share a single listener registry:

- **Commit-attached** — Oak-internal capture sites (e.g.
  `UserManagerImpl`) buffer events in a per-session `ThreadLocal` during
  a session write, and a `NodeStore.Observer` (`AuditDrainObserver`)
  drains the buffer on commit success — synchronously on the merge
  thread, after durable persistence. Events are decorated with
  `commit.sessionId` / `commit.userId` / `commit.timestamp` payload
  entries before dispatch. A failed commit drops the buffer.
- **Fire-and-forget** — Any OSGi bundle resolves `AuditEventEmitter`
  via `@Reference` and calls `emit(event)`. The event is dispatched
  immediately on the calling thread; no buffering, no commit boundary,
  no payload decoration.

Pipeline state is owned by `AuditConfigurationImpl` (`oak-core`),
registered as an OSGi service of type `AuditConfiguration` from the new
`oak-audit-spi` module. **`AuditConfiguration` is not a
`SecurityConfiguration`** — audit is a top-level Oak concern, orthogonal
to authentication, authorization, principal/user management, and
tokens.

## Design choices worth highlighting

1. **Observer-based drain, not commit hooks.** The pipeline subscribes to
   the root NodeStore via `Observable.addObserver(...)` and fires on
   commit success after durable persistence. Two consequences:
   (a) audit events never enter `CommitContext` (a shared, string-keyed
   channel that any `CommitHook` in the same commit could otherwise
   observe — keeping events out of it eliminates a class of cross-bundle
   information disclosure); (b) a successful dispatch implies the
   corresponding write durably persisted, so a failed durable commit
   never produces an audit event.

2. **OSGi wiring via the standard `Observer`-service idiom.** The OSGi
   `@Activate` publishes the drain observer as an `Observer` service on
   the `BundleContext`; Oak's existing `ObserverTracker` (already
   started by each `NodeStoreService`) picks it up and subscribes it to
   the root NodeStore. This matches Lucene's `LocalIndexObserver` and
   needs no new SPI in `oak-store-spi`.

3. **Domain string `"oak.security"`.** Oak's security stack emits all
   events under this domain. The `oak.` prefix namespaces it so
   listeners hosted in mixed environments (Sling, AEM, third-party
   bundles) can disambiguate Oak's security events from same-named
   domains defined elsewhere. Per-sub-domain type-string vocabularies
   (`UserAuditTypes` for now; future `AclAuditTypes`,
   `PrincipalAuditTypes`, `TokenAuditTypes`) live alongside the SPI they
   describe.

4. **Asymmetric read-write exposure for the security-domain vocabulary.**
   The type-string + payload-key constants (e.g. `UserAuditTypes`) are
   public SPI, so listener bundles can compile-time-reference them. The
   producer-side factories (e.g. `UserAuditEvents`) are
   package-private alongside their only caller (`UserManagerImpl`). The
   partition is defense-in-depth — listeners that need to distinguish
   Oak-attested events from fire-and-forget emissions MUST check for
   the `commit.*` keys in the payload (Oak unconditionally overrides
   any caller-supplied values for those keys on the commit-attached
   path).

5. **Two-layer `Throwable` isolation.** The outer barrier in
   `AuditDrainObserver.contentChanged` catches every `Throwable`
   subtype before it escapes back into Oak's observer dispatch chain
   (`CompositeObserver` has no per-observer isolation; an audit-induced
   throw would otherwise masquerade as a commit failure). The inner
   barrier wraps each `AuditEventListener.onEvents` call, so one
   misbehaving listener cannot stop the others.

6. **No `BackgroundObserver` wrap.** `BackgroundObserver` drops
   `CommitInfo.sessionId` on queue overflow, which the audit drain
   relies on to look up the per-thread buffer. The drain observer is
   registered directly; the constraint is documented on the class.

## What changed (file-level summary)

**New module:**

- `oak-audit-spi` — the domain-neutral SPI module. Defines `AuditEvent`,
  `AuditEventListener`, `AuditEventEmitter`, the `AuditEvents` static
  façade, `AuditConfiguration`, and `AuditBufferLifecycle`. Has no
  dependency on `oak-security-spi`, `oak-core`, or any storage module.

**New types in `oak-security-spi`:**

- `SecurityAuditDomain` (`org.apache.jackrabbit.oak.spi.security.audit`)
  — the `oak.security` domain constant.
- `UserAuditTypes` (`org.apache.jackrabbit.oak.spi.security.user`) —
  type-string and payload-key constants for user-membership events.
  Future ACL / principal / token events follow the same per-sub-domain
  pattern.

**New types in `oak-core`:**

- `AuditConfigurationImpl` — pipeline owner; OSGi `@Component`
  publishing `AuditConfiguration`. Holds the `FT_AUDIT` `Feature`
  toggle, the `AuditBuffer`, the `WhiteboardAuditEventListenerRegistry`,
  the `BufferSink` (installed into `AuditEvents.Sink`), and the
  singleton `AuditDrainObserver`.
- `AuditDrainObserver` — `Observer` implementation. Drains the buffer
  on commit success and dispatches to listeners. OUTER + INNER
  `Throwable` barriers; `isExternal()` short-circuit; explicit
  no-`BackgroundObserver`-wrap contract.
- `AuditBuffer` — per-thread, per-session staging area. `ThreadLocal`
  map keyed by `ContentSession` id.
- `AuditEventEmitterImpl` — OSGi `@Component` implementing
  `AuditEventEmitter`; delegates to `AuditEvents.dispatch`.
- `WhiteboardAuditEventListenerRegistry` — tracks `AuditEventListener`
  services on the Whiteboard; `getListeners()` returns by-rank-desc.
- `CommitMetadataDecorator` — adds `commit.*` payload entries on the
  commit-attached path.
- `UserAuditEvents` — package-private producer-side factories for
  user-membership events. Called from `UserManagerImpl`.

**`MutableRoot` integration:**

Three lifecycle callouts to `AuditBufferLifecycle` cover the paths the
observer doesn't see:

- `MutableRoot.java:238` (`rebase`) → `onRefresh(sessionId)`
- `MutableRoot.java:249` (`refresh`) → `onRefresh(sessionId)`
- `MutableRoot.java:270` (`commit` finally, `!merged`) →
  `onCommitFailed(sessionId)`

**`UserManagerImpl` capture sites:** `recordSingleMembershipAuditEvent`
and `recordBulkMembershipAuditEvent` call
`AuditEvents.record(root, UserAuditEvents.memberAdded(...))` etc. The
gate is `AuditEvents.isEnabled()` — when no listener is registered for
the `oak.security` domain, capture allocates nothing.

**`oak-doc/src/site/markdown/security/audit.md`:** user-facing guide for
listener bundles. Covers the event model, both producer paths, the
trust model, payload conventions, and worked examples.

**Embedded test fixture (`oak-run-commons/OakFixture.getMemoryNSWithAudit`):**
benchmark fixture with the pipeline wired and `FT_AUDIT` toggled ON.
Used by `oak-benchmarks/AuditCaptureSiteOverheadTest` and
`AuditEmptyCommitOverheadTest`.

## Build status

```
mvn clean install -pl oak-audit-spi,oak-security-spi,oak-core,oak-run-commons -am
```

All green:

| Module             | Coverage gate | RAT | bnd-baseline |
|--------------------|---------------|-----|---------------|
| oak-audit-spi      | 100% / 100%   | ✅  | N/A (new module) |
| oak-security-spi   | 100% / 100%   | ✅  | 0 errors, 0 warnings |
| oak-core           | met (>80%)    | ✅  | 0 errors, 0 warnings |
| oak-run-commons    | (no gate)     | ✅  | (no baseline) |

Audit-related tests run across the four modules, with named coverage on:

- `AuditDrainObserver`: 13 tests in `AuditDrainObserverTest`
  (`isExternal` short-circuit, toggle-off, empty-buffer, no-listeners,
  `groupByDomain` cross-domain, rank-ordering, per-listener `Throwable`
  isolation across `LinkageError` / `RuntimeException` /
  `OutOfMemoryError` / `NoClassDefFoundError`, OUTER `Throwable` barrier
  via poisoned event, happy-path with decorator).
- `AuditConfigurationImpl`: 13 tests in `AuditConfigurationImplTest`
  (lifecycle state machine + `InOrder` dispose-sequence pinning).
- Integration: `AuditPipelineIT` (15 cases) +
  `AuditWiringIT` (3 cases — UserManagerImpl end-to-end).
- Producer-side: `UserAuditEventsTest`, `UserAuditTypesTest`.

## Performance

Audit-OFF (no listener for `oak.security`) is allocation-free: capture
sites short-circuit at `AuditEvents.isEnabledFor("oak.security")` before
constructing an event. Audit-ON measurements:

- Empty-commit overhead: < 100 ns / commit.
- Captured-event overhead: < 2 µs / event.

Numbers and reproduction recipes in
[`oak-audit-spi/docs/performance/README.md`](https://github.com/dulvac/jackrabbit-oak/blob/audit-spi/observer-drain/oak-audit-spi/docs/performance/README.md).

## Security invariants

All audit invariants are pinned by tests and documented in §9 of
[`design.md`](https://github.com/dulvac/jackrabbit-oak/blob/audit-spi/observer-drain/oak-audit-spi/docs/design.md):

- **OUTER `Throwable` barrier in `AuditDrainObserver.contentChanged`**
  prevents audit-induced commit-failure masquerade. `CompositeObserver`
  has no per-observer isolation; `DocumentNodeStore` /
  `LockBasedScheduler` fire observers after the commit is durable, so
  any throw out of the audit Observer would otherwise surface as a fake
  `RuntimeException` to the merge caller.
- **No `BackgroundObserver` wrap.** Documented in `AuditDrainObserver`
  class Javadoc with the `BackgroundObserver.java:283-286`
  queue-overflow citation. The async wrapper drops
  `CommitInfo.sessionId` on overflow, which would silently lose audit
  events under load.
- **`info.isExternal()` first-statement gate** in `doContentChanged`
  covers the bootstrap `EMPTY_EXTERNAL` invocation, cluster
  replication, segment external head movement, and
  COW/Composite/Branch transitive in one predicate.
- **Dispose-order precondition** (`observerRegistration == null` guard
  at top of `dispose()`) converts misuse (direct `dispose()` after
  `@Activate` without `@Deactivate`) into a loud `IllegalStateException`
  rather than a silent half-torn-down state.
- **`AuditEvent` payload trust contract.** Oak unconditionally
  overrides caller-supplied `commit.sessionId` / `commit.userId` /
  `commit.timestamp` keys on the commit-attached path. Listeners may
  treat the **presence** of `commit.*` keys as an Oak-attested commit;
  absence signals a fire-and-forget emission whose payload reflects
  the emitting bundle's claim only.

## Test plan

- [x] `mvn clean install -pl oak-audit-spi,oak-security-spi,oak-core,oak-run-commons -am`
      green incl. coverage + RAT + bnd-baseline.
- [x] All audit-related tests pass.
- [x] `AuditConfigurationImplTest` covers lifecycle state machine,
      singleton invariant, dispose precondition, ISE pre-init AND
      post-dispose, `InOrder` dispose sequence.
- [x] `AuditDrainObserverTest` covers OUTER `Throwable` barrier,
      `isExternal` short-circuit, listener `Throwable` isolation across
      multiple error types.
- [x] `AuditPipelineIT` migration-path no-op assertion (direct
      `nodeStore.merge(...)` produces no listener invocations) +
      masquerade-prevention IT (poisoned event does not propagate to
      commit caller).
- [x] `AuditWiringIT` UserManagerImpl → buffer → Observer → listener
      end-to-end with `commit.*` payload assertions.
- [ ] Manual smoke in an OSGi container with `FT_AUDIT` flipped ON and
      a listener subscribed to the `oak.security` domain.

## Out of scope (follow-ups, separate tickets)

- Capture sites for ACL changes, principal lifecycle, token
  issue/revoke. The shape is laid down by `UserAuditTypes` /
  `UserAuditEvents`; new sub-domains plug in via their own
  `*AuditTypes` (SPI) + `*AuditEvents` (impl-private factories).
- Default WARN-level listener registration for sites that want a
  log-based "audit forwarding" out of the box.
- Persistent / SIEM-shipping reference listener.

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
