# PR description — v3 Observer-based audit drain

Draft for team-lead. Paste into the GitHub PR body when opening the v3 PR.
This file lives under `oak-audit-spi/docs/` for the same reason the v2
PR-review docs do — co-located with the design spec for archaeology.

---

## Title

```
OAK-XXXXX: audit-spi v3 — Observer-based commit-attached drain, decouple from SecurityConfiguration
```

(JIRA number TBD per team-lead's filing cadence.)

## Summary

Replaces the audit pipeline's pair of commit hooks (`SnapshotAuditBufferHook` +
`DispatchAuditEventsHook`) with a single `NodeStore.Observer` (`AuditDrainObserver`)
that fires on commit success, **after** durable persistence. Audit is no longer
modeled as a `SecurityConfiguration`; it's a top-level Oak concern.

The user-API-level audit-event SPI (`AuditEvent`, `AuditEventListener`,
`AuditEventEmitter`, `AuditEvents`) is unchanged — same listener contract,
same capture sites, same payload conventions, same trust model. What changes
is the wiring underneath: how the buffered events get from the per-session
ThreadLocal buffer to listener invocations, and how `AuditConfiguration`
participates in the Oak component graph.

## Motivation

Three problems with the v2 hook-based architecture:

1. **`CommitContext` exfiltration concern.** v2 used `CommitContext` to ferry
   the buffered event list between the snapshot hook and the dispatch hook.
   `CommitContext` is a shared, string-keyed channel visible to any
   `CommitHook` running in the same commit — documented as an accepted
   limitation under the bundle-deploy trust model, but not ideal.
2. **Ghost-event window.** v2's `DispatchAuditEventsHook` ran as a
   `PostValidationHook`, i.e. inside the merge pipeline before durable
   persistence. A successful dispatch followed by a durable-commit failure
   could log a ghost event (an audit event for a write that never landed).
3. **`AuditConfiguration` modeled as a `SecurityConfiguration` is a category
   error.** Audit pipeline state is orthogonal to authentication,
   authorization, user/group management, etc.; coupling it to the security
   composition machinery added plumbing without providing a benefit.

v3 closes all three: events never enter `CommitContext`, dispatch fires
after `NodeStore.merge(...)` returns, and `AuditConfiguration` is a
top-level OSGi service published from `oak-audit-spi`.

## What changed (file-level summary)

**New files:**

- `oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditConfiguration.java`
   — moved from `oak-security-spi`; drops `extends SecurityConfiguration`; keeps
   `NAME`, `isActive()`, `NOOP`.
- `oak-audit-spi/src/test/java/org/apache/jackrabbit/oak/spi/audit/AuditConfigurationTest.java`
   — moved (institutional knowledge preserved per sage's invariant #4).
- `oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditDrainObserver.java`
   — implements `Observer.contentChanged`. OUTER + INNER `Throwable` barriers,
   `isExternal()` short-circuit, no `BackgroundObserver` wrap (documented in
   class Javadoc).
- `oak-audit-spi/docs/design-v3-observer-drain.md` — canonical v3 design.

**Deleted:**

- `oak-core/.../SnapshotAuditBufferHook.java` + `DispatchAuditEventsHook.java`
   (and the `CommitContext` stash mechanism they used).
- `oak-security-spi/.../spi/security/audit/AuditConfiguration.java` and its
   test (moved to `oak-audit-spi`).

**Modified:**

- `AuditConfigurationImpl`: drops `extends ConfigurationBase`,
   `@Component(service = AuditConfiguration.class)` only (was multi-role with
   `SecurityConfiguration.class`). Adds a singleton `AuditDrainObserver` field
   constructed once at the end of `initialize(Whiteboard)`, exposed via
   `public Observer getDrainObserver()`. `@Activate` publishes the singleton
   via `bundleContext.registerService(Observer.class.getName(), ...)`;
   `@Deactivate` unregisters the service then calls `dispose()`. `dispose()`
   gains a `observerRegistration == null` precondition guard.
- `SecurityProviderBuilder.withAuditConfiguration(...)`, `InternalSecurityProvider`
   audit field + setter + getConfigurations entry, `SecurityProviderRegistration`
   `@Reference auditConfiguration` block — all REMOVED. Audit is wired
   independently.
- `oak-run-commons/.../OakFixture.getMemoryNSWithAudit` — rewired to use
   explicit `((Observable) store).addObserver(audit.getDrainObserver())` with
   per-store Closeable tracking. See §6 of the design for the
   `Oak.with(Observer)` caveat (default-whiteboard auto-attach is bypassed
   once the caller passes their own whiteboard).
- `AuditPipelineIT`, `AuditWiringIT` — rewired to v3 path. Existing assertions
   preserved.
- `oak-doc/src/site/markdown/security/audit.md` — Module Layout table updated,
   "Commit-attached pipeline" section explains Observer-based drain, "Probing
   Pipeline State" example uses `@Reference AuditConfiguration` (no longer
   `SecurityProvider.getConfiguration`).
- `oak-audit-spi/docs/design.md` — new §0 status note pointing at the v3
   design; §3.2b JMM observation marked RESOLVED.
- `oak-audit-spi/README.md` — Further Reading updated to surface the v3
   design doc.

**Package version bumps:**

- `oak-audit-spi/.../spi/audit/package-info.java`: `@Version("1.0.0")` →
   `@Version("1.1.0")` (binary-additive — new `AuditConfiguration` type;
   existing types unchanged).
- `oak-security-spi/.../spi/security/audit/package-info.java`:
   `@Version("1.0.0")` → `@Version("2.0.0")` (`AuditConfiguration` REMOVED
   from the package — breaking; bnd-baseline confirms).

## Build status

```
mvn clean install -pl oak-audit-spi,oak-security-spi,oak-core,oak-run-commons -am
```

All green:

| Module             | Tests | Coverage gate | RAT | bnd-baseline      |
|--------------------|-------|---------------|-----|-------------------|
| oak-audit-spi      | 29    | 100% / 100%   | ✅  | N/A (new module)  |
| oak-security-spi   | green | 100% / 100%   | ✅  | 0 errors, 0 warnings (`AuditConfiguration` removal triggers MAJOR bump, accepted) |
| oak-core           | 4747  | met (>80%)    | ✅  | 0 errors, 0 warnings |
| oak-run-commons    | green | (no gate)     | ✅  | (no baseline)     |

51 audit-related tests across the four modules. Coverage of new v3 code:

- `AuditDrainObserver`: 13 tests in `AuditDrainObserverTest`
   (`isExternal` short-circuit, toggle-off, empty-buffer, no-listeners,
   `groupByDomain` cross-domain, rank-ordering, per-listener Throwable
   isolation across LinkageError / RuntimeException / OutOfMemoryError /
   NoClassDefFoundError, OUTER Throwable barrier via poisoned event,
   happy-path with decorator).
- `AuditConfigurationImpl`: 13 tests in `AuditConfigurationImplTest`
   (existing `isActive` cases + cases (a)–(g) per design §10 + an
   `InOrder` dispose-sequence test pinning the "Observer FIRST, internals
   second" invariant via Mockito).
- Integration: 15 `AuditPipelineIT` + 3 `AuditWiringIT` (UserManagerImpl
   end-to-end), plus the masquerade-prevention IT (counter-based poison
   exercising sage's I8 outer-barrier invariant under realistic conditions).

## Performance

No measurable regression. v3 audit-ON-vs-OFF deltas are sub-ms across all
microbench scales — same noise envelope as v2. Numbers + reproduction in
`oak-audit-spi/docs/performance/head-observer-drain-d4725d1a4c.txt` and the
performance `README.md`. Bounds:

- Empty-commit overhead: < 100 ns / commit (same as v2).
- Captured-event overhead: < 2 µs / event (same as v2).

The hypothesis "v3 marginally faster on audit-OFF" (one Observer with
`isExternal()` short-circuit vs. v2's two hooks each with their own toggle
check) came back inconclusive — the deltas are below the framework's
ms-granular resolution. Verdict: v3 is not slower.

## Security invariants

Verified by sage in #12 (initial approve) + re-verified after the
singleton/precondition refinements. All 16 invariants from the audit
checklist held byte-identical or strictly stronger. Highlights:

- **OUTER Throwable barrier in `AuditDrainObserver.contentChanged`**
   prevents audit-induced commit-failure masquerade (`CompositeObserver`
   has no per-observer isolation; `DocumentNodeStore`/`LockBasedScheduler`
   fire observers after the commit is durable, so any throw out of the
   audit Observer would surface as a fake commit-failure RuntimeException
   to the merge caller).
- **No `BackgroundObserver` wrap**: documented in `AuditDrainObserver`
   class Javadoc with the `BackgroundObserver.java:283-286` queue-overflow
   citation. The async wrapper drops `CommitInfo.sessionId` on overflow,
   which would silently lose audit events under load.
- **`info.isExternal()` first-statement gate** in `doContentChanged`
   covers the bootstrap `EMPTY_EXTERNAL` invocation, cluster replication,
   segment external head movement, and COW/Composite/Branch transitive
   in one predicate.
- **Dispose-order precondition** (`observerRegistration == null` guard at
   top of `dispose()`) converts misuse (direct `dispose()` after `@Activate`
   without `@Deactivate`) into a loud `IllegalStateException` rather than a
   silent half-torn-down state.

## Design archaeology

- [`oak-audit-spi/docs/design-v3-observer-drain.md`](oak-audit-spi/docs/design-v3-observer-drain.md)
   — canonical v3 design (this PR's reference). 850+ lines: pipeline
   ownership, OSGi wiring, embedded wiring, threading invariants, test
   rewiring, trade-offs.
- [`oak-audit-spi/docs/design.md`](oak-audit-spi/docs/design.md) §0 — v3
   status note. The rest of `design.md` describes the v2 architecture as
   reference for the unchanged primitives (`AuditEvent`,
   `AuditEventListener`, capture-site contracts, trust model).
- [`oak-audit-spi/docs/history/design-item-2-v1-vetoed.md`](oak-audit-spi/docs/history/design-item-2-v1-vetoed.md)
   — v1 attempt at top-level audit via a new `CommitHookProvider` SPI in
   `oak-store-spi`. Vetoed for scope reasons. v3 reaches the same
   architectural goal (top-level audit, decoupled from SecurityConfiguration)
   via a different mechanism (`Observer`, not new SPI in `oak-store-spi`).

## Test plan

- [x] `mvn clean install -pl oak-audit-spi,oak-security-spi,oak-core,oak-run-commons -am`
       green incl. coverage + RAT + bnd-baseline.
- [x] All 51 audit-related tests pass.
- [x] `AuditConfigurationImplTest` covers cases (a)–(g) per design §10
       (singleton invariant guard, dispose precondition, ISE pre-init AND
       post-dispose, InOrder dispose sequence).
- [x] `AuditDrainObserverTest` covers OUTER Throwable barrier, isExternal
       short-circuit, listener Throwable isolation across multiple error
       types.
- [x] `AuditPipelineIT` migration-path no-op assertion (direct
       `nodeStore.merge(...)` produces no listener invocations) +
       masquerade-prevention IT (poisoned event does not propagate to
       commit caller).
- [x] `AuditWiringIT` UserManagerImpl → buffer → Observer → listener
       end-to-end with `commit.*` payload assertions.
- [ ] Manual smoke in an OSGi container with `FT_AUDIT` flipped ON and a
       listener subscribed to the `security` domain (pre-merge sanity).
- [ ] Confirm no transitive impact in `oak-jcr`, `oak-store-document`,
       `oak-segment-tar`, `oak-upgrade` (alex's cross-Oak sanity in #13
       found no callers; CI will confirm).

## Breaking changes

- `org.apache.jackrabbit.oak.spi.security.audit.AuditConfiguration` is
   REMOVED. Consumers must switch to
   `org.apache.jackrabbit.oak.spi.audit.AuditConfiguration` (same
   `NAME` constant value, same `isActive()` method, same `NOOP` instance —
   only the package changes; the `extends SecurityConfiguration` parent
   class drops).
- `SecurityProvider.getConfiguration(AuditConfiguration.class)` no longer
   returns the audit configuration (audit is not a `SecurityConfiguration`
   in v3). Components must `@Reference AuditConfiguration` directly or
   resolve from the Whiteboard.
- `SecurityProviderBuilder.withAuditConfiguration(...)`,
   `InternalSecurityProvider.setAuditConfiguration(...)`,
   `SecurityProviderRegistration.bindAuditConfiguration(...)` — methods
   REMOVED. Embedded callers wire `AuditConfigurationImpl` directly:

   ```java
   AuditConfigurationImpl audit = new AuditConfigurationImpl();
   audit.initialize(whiteboard);
   Closeable observer = ((Observable) store).addObserver(audit.getDrainObserver());
   // ... use Oak ...
   observer.close();
   audit.dispose();
   ```

   See `oak-audit-spi/docs/design-v3-observer-drain.md` §6.

The fork has no external consumers of the moved/removed APIs; on upstream
the SPI module (`oak-audit-spi`) is brand-new, so the breaking change
manifests only on `oak-security-spi`'s audit package (whose MAJOR version
bump signals this exactly).

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
