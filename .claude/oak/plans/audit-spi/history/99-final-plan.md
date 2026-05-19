# Audit SPI — Proposed Plan (Path α, v1)

**Status:** pending manual review. This plan is the team's consolidated proposal; the human author will review before any code lands.
**Branch:** work happens on the author's fork — no Apache JIRA OAK ticket required for this iteration. Branch name and commit-message style follow the fork's conventions, not upstream's. If the change is later proposed upstream, an `OAK-XXXXX` ticket should be filed at that time and the feature-toggle constant renamed accordingly (see "Toggle" row in §3 and the upstreaming notes in §9).
**No code committed yet.** All artifacts in `.claude/oak/plans/audit-spi/`.

This document is the single readable plan for review. Supporting evidence and rationale live in the source-of-truth files alongside it:

- `01-architecture.md` — ada — full architecture decisions and risk register
- `02-oak-validation.md` — alex — every claim cited against trunk source
- `03-skeleton/` — grace — 19 Java files + 6 unified diffs + README, all `git apply --check`-verified
- `04-tests-and-benchmarks.md` — turing — 121 unit tests + ~47 integration runs (across 2 fixtures) + 7 failure-mode tests + 18 perf measurements with p50/p99 budgets
- `05-research-notes.md` — shannon — Whiteboard ranking portability finding, prior OAK-audit history
- `06-scope-and-flow.md` — flow diagram (sequence + component) and fact-checked event-coverage analysis

---

## 1. What this is

A generic, domain-tagged audit-event pipeline inside Oak. Internal Oak modules (security first; other domains later) record structured events at API call sites. Events are buffered per-session, dispatched as one burst on commit success, **on the same thread that called `Root.commit()`** — so AEM/Sling request context (SLF4J MDC, HTTP correlation IDs) is preserved end-to-end without explicit propagation.

Listeners register via the Oak Whiteboard. v1 ships with the `"security"` domain (`MemberAdded`, `MemberRemoved`, `MembersAddedBulk`, `MembersRemovedBulk`). Adding domains is mechanical — no SPI change.

---

## 2. Scope

### In scope for v1

- New SPI in `oak-security-spi/.../spi/security/audit/`:
  - `AuditEvent` (`@ProviderType`), `AuditEventListener` (`@ConsumerType`), `AuditEventAware` (marker), `AuditEvents` (static façade), `AuditBufferLifecycle` (static façade), `AuditConfiguration` (typed `SecurityConfiguration` sub-interface), `SecurityAuditDomain`, `SecurityAuditEvent` (abstract base), `MemberAddedEvent`, `MemberRemovedEvent`, `MembersAddedBulkEvent`, `MembersRemovedBulkEvent`.
- New impl in `oak-core/.../security/audit/`: `AuditConfigurationImpl`, `AuditBuffer`, `WhiteboardAuditEventListenerRegistry`, `SnapshotAuditBufferHook`, `DispatchAuditEventsHook`, `NoOpAuditEventListener`.
- `MutableRoot.java` delta: +1 import, +3 method calls (one in `commit()` failure catch, one each in `refresh()` and `rebase()`). **No new fields.**
- Registration plumbing inside `oak-core/.../security/internal/` (still in oak-security):
  - `InternalSecurityProvider.diff` — new `volatile AuditConfiguration auditConfiguration` field (default `AuditConfiguration.NOOP`), updated `getConfigurations()` and `getConfiguration(Class)`.
  - `SecurityProviderRegistration.diff` — new `@Reference(service = AuditConfiguration.class, cardinality = OPTIONAL, policy = DYNAMIC)` + bind/unbind pair.
  - `SecurityProviderBuilder.diff` — new `withAuditConfiguration(AuditConfiguration)` step.
- Capture sites in `UserManagerImpl.diff`:
  - Single-member: `onGroupUpdate(Group, boolean isRemove, Authorizable member)` (line 371).
  - Bulk: `onGroupUpdate(Group, boolean isRemove, boolean isContentId, Set<String> memberIds, Set<String> failedIds)` (line 393).
- `oak-security-spi-pom.diff` — adds `org.apache.jackrabbit.oak.spi.security.audit` to `<Export-Package>`.
- Feature toggle `FT_AUDIT` (default **off** — new feature, not bug fix), wired through `AuditConfigurationImpl.@Activate/@Deactivate`.
- Tests: 121 unit + ~47 integration runs across SEGMENT_TAR+DOCUMENT_NS fixtures + 7 failure-mode + 18 perf measurements with p50/p99 budgets. Includes `InOrder`-mockito assertion pinning the 4-step deactivation sequence (race-prevention regression test), `bulkPath_partialFailures_eventCarriesBothSets` proving both `memberIds` and `failedIds` are populated, and `bulkPath_failedIdsEmptyDoesNotMeanValidatorsPassed` pinning the "failure-layer scope" Javadoc invariant — empty `failedIds` does NOT imply validators passed; consumers observe commit success via dispatch invocation itself.

### Out of scope for v1 (deferred to v1.1+)

- Capture sites beyond `UserManagerImpl.onGroupUpdate` (USER_CREATED, ACL_*, TOKEN_*, CUG_*, …) — same pattern, mechanically easy, just lots of files.
- External-change (cross-cluster) audit events.
- `OakInitializer` and async-task paths — they bypass `MutableRoot.commit()` entirely and don't run security configurations' hooks (see `02-oak-validation.md` §6, §9). Initializer mutations are not auditable in v1.
- The deprecated `SecurityProviderImpl` (`oak-core/.../security/SecurityProviderImpl.java:65` — `@Deprecated`, zero non-test consumers per alex's grep). Embedded users still using the deprecated constructor get no audit; documented migration to `SecurityProviderBuilder`.
- A `BackgroundAuditEventListener` async wrapper analogous to `BackgroundObserver`. Listener contract requires non-blocking; if a consumer needs async, they implement it themselves.
- Validator-side capture (deriving events from NodeState diff). Explicitly rejected by user — would require re-implementing Oak security semantics outside Oak.
- Optional `GroupMembershipEvent` abstract parent for the four membership events (grace's loose end #1). Would be a v1.1 refactor if listeners want polymorphic dispatch.
- `boolean isActive()` on `AuditConfiguration` for runtime introspection (grace's loose end #2). Add if a real consumer asks.
- Inventory of downstream consumers of the existing OAK-2516 SLF4J logger `org.apache.jackrabbit.oak.audit` (shannon's flag) — recommended before v1 ships, but separable from this PR.

---

## 3. Architectural decisions (consolidated)

| Topic | Decision | Source |
|---|---|---|
| SPI module | `oak-security-spi` (existing — depends on `oak-core-spi` for Whiteboard/Feature and `oak-store-spi` for CommitHook/CommitContext/PostValidationHook/CommitInfo/NodeState) | `01-architecture.md` §1 |
| New package | `org.apache.jackrabbit.oak.spi.security.audit` (add to `Export-Package` in `oak-security-spi/pom.xml`) | `01-architecture.md` §1 |
| New SPI interface | `AuditConfiguration extends SecurityConfiguration` — typed marker, no v1 methods. Resolves alex's §7 OSGi registration blocker. | `01-architecture.md` §1, `02-oak-validation.md` §7 |
| Impl class name | `AuditConfigurationImpl` (matches `UserConfigurationImpl`, `AuthorizationConfigurationImpl` convention) | `01-architecture.md` §2 |
| Registration cardinality | UNARY OPTIONAL DYNAMIC — audit is a singleton resource; absence is non-fatal; hot-swap supported. Precedent: `UserConfigurationImpl.java:238` (OPTIONAL+DYNAMIC on `BlobAccessProvider`). | `01-architecture.md` §2 |
| NoOp default | `AuditConfiguration.NOOP` is the default value for `InternalSecurityProvider.auditConfiguration` (never null). Keeps `getConfiguration(AuditConfiguration.class)` `@NotNull` like the six core types; `getConfigurations()` includes it unconditionally with `emptyList()` from `getCommitHooks` — uniform contract. | grace Round 4, alex's FYI |
| Deprecated `SecurityProviderImpl` | **Skipped.** Embedded users migrate to `SecurityProviderBuilder`. | `01-architecture.md` §2, alex's grep |
| Annotations | `@ProviderType` on `AuditEvent` and `AuditConfiguration`; `@ConsumerType` on `AuditEventListener` | `01-architecture.md` §1 |
| Listener ordering | `AuditEventListener.getRank()` (default 0); explicit stable sort in `WhiteboardAuditEventListenerRegistry.dispatch()` — **Whiteboard ranking is NOT portable**. `DefaultWhiteboard.java:35-64` is unsorted; `OsgiWhiteboard.java:181-194` is sorted; tests would diverge from production without explicit sort. | shannon §1, `01-architecture.md` §1 |
| Prior art | None — OAK-2516 was an SLF4J logger, not an SPI. Greenfield namespace. Avoid the logger name `org.apache.jackrabbit.oak.audit` to prevent collision with existing AEM/Sling dashboards. | shannon §2 |
| Payload contract | Non-null values via `Map.of(...)`; optional fields omitted (absent key), not sentinel; user IDs use `CommitInfo.OAK_UNKNOWN` (`"oak:unknown"`), never `""`. | `01-architecture.md` §1 ("AuditEvent.payload() contract") |
| Hot-path API | `AuditEvents.isEnabledFor(domain)` is the canonical capture-site gate (2 volatile reads + live `Tracker.getServices()` + linear scan, constant-time via singleton `emptyList()` when no listener is registered); `isEnabled()` is the coarse fallback for callers that don't know their domain. **No registry-side cache** — would go stale because `Tracker` has no add/remove callback. | `01-architecture.md` §3 |
| OSGi pattern | `@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})` — dual registration matches `UserConfigurationImpl:78` | `01-architecture.md` §2 |
| **Activation order** | toggle → registry → buffer → install — toggle must be initialized before publishing the buffer | `01-architecture.md` §2 |
| **Deactivation order** | `feature.close()` FIRST (stop new captures) → `registry.stop()` → `install(null)` → `clearAll()`. **NOT reverse-of-activate** — alex's race catch: a reverse-order teardown would leave the toggle ON between `install(null)` and `feature.close()`, causing captures into a buffer about to be cleared (guaranteed-loss window). | `01-architecture.md` §2 ("Deactivation"), alex's correction |
| Residual ThreadLocal leak | Bounded by `O(in-flight sessions × threads with captured events × bytes-per-event)`. Acknowledged v1 trade-off; no weak-reference machinery. | `01-architecture.md` §2 |
| Toggle | `Feature.newFeature("FT_AUDIT", whiteboard)` in `@Activate`; `feature.close()` first thing in `@Deactivate`. Default **disabled** (new feature, not bug fix) per `AGENTS.md`. Rename to `FT_AUDIT_OAK-<NNNNN>` if upstreamed. | `01-architecture.md` §3 |
| Hook positioning | `SnapshotAuditBufferHook` is a regular `CommitHook` (runs before validators); `DispatchAuditEventsHook` is a `PostValidationHook` (auto-routed to end of chain by `MutableRoot.java:292-294, 305`). | `02-oak-validation.md` §1 |
| Snapshot semantics | **Peek-only, non-destructive.** `SnapshotAuditBufferHook` reads `ThreadLocal[sessionId]` and writes the same list reference into `CommitContext` without clearing the ThreadLocal. `DispatchAuditEventsHook` holds drain authority and clears the ThreadLocal entry in a `finally` block. **Closes the brief's "events-lost-on-retry" residual gap** — events survive `ResetCommitAttributeHook` wiping `CommitContext` between merge retries because the ThreadLocal is the authoritative store until Dispatch drains it. Provably exactly-once across N retries. | `01-architecture.md` §5 Risk 8, grace skeleton, alex's recommendation |
| New Maven edges | **None.** `oak-core → oak-security-spi` already exists; `MutableRoot.java` adds one import only. | `01-architecture.md` §4 |
| Capture site (single) | `UserManagerImpl.onGroupUpdate(Group, boolean isRemove, Authorizable member)` (line 371) — not `MembershipProvider.addMember`. Both paths funnel through this one logical layer, covering single-member adds and removes. | `02-oak-validation.md` §10, alex's recommendation |
| Capture site (bulk) | `UserManagerImpl.onGroupUpdate(Group, boolean isRemove, boolean isContentId, Set<String> memberIds, Set<String> failedIds)` (line 393). Emits **one** event per bulk operation. Payload carries **distinct keys** `memberIds` (the staged subset that actually applied) and `failedIds` (the rejected subset) — listeners get strictly more information without mis-attribution risk because the keys are unambiguous. Short-circuits emit when `memberIds.isEmpty()`. | option (b), distinct-keys resolution |

---

## 4. SPI surface (cheat sheet)

```java
// oak-security-spi/.../spi/security/audit/

@ProviderType
public interface AuditEvent {
    @NotNull String getDomain();           // e.g. "security"
    @NotNull String getType();             // e.g. "user.member.added"
    long getTimestamp();                   // wall clock at capture, ms since epoch
    @NotNull Map<String, ?> getPayload();  // never-null values; missing fields absent (not sentinel)
}

@ConsumerType
public interface AuditEventListener {
    @NotNull String getDomain();           // listener's domain interest

    /** Higher rank invoked first. OSGi listeners should also set service.ranking to the same N. */
    default int getRank() { return 0; }

    /**
     * Invoked once per successful commit, on the same thread that called Root.commit().
     * Events is a non-empty list filtered to this listener's domain, in capture order.
     * Payload map values are never null. Optional fields are absent from the map.
     * MUST NOT throw. MUST NOT block. MUST NOT mutate the repository synchronously.
     */
    void onCommit(@NotNull NodeState root, @NotNull CommitInfo info, @NotNull List<AuditEvent> events);
}

public interface AuditEventAware { @NotNull AuditEventCollector getAuditEventCollector(); }

public final class AuditEvents {
    public static boolean isEnabled() { /* 1 volatile read + AtomicBoolean.get */ }
    public static boolean isEnabledFor(@NotNull String domain) { /* + live Tracker.getServices() lookup + linear scan */ }
    public static void record(@NotNull Root root, @NotNull AuditEvent event) { /* … */ }
    public static void install(@Nullable Sink sink) { /* swap-on-install; null restores NOOP */ }
    public interface Sink { /* internal — implemented by AuditConfigurationImpl machinery */ }
}

public final class AuditBufferLifecycle {
    public interface Listener { void onCommitFailed(String sessionId); void onRefresh(String sessionId); }
    public static void install(@Nullable Listener l) { /* swap; null restores NOOP */ }
    public static void onCommitFailed(String sessionId) { listener.onCommitFailed(sessionId); }
    public static void onRefresh(String sessionId)      { listener.onRefresh(sessionId); }
}

@ProviderType
public interface AuditConfiguration extends SecurityConfiguration {
    String NAME = "org.apache.jackrabbit.oak.audit";
    AuditConfiguration NOOP = new Noop();   // sentinel; getCommitHooks returns emptyList()
    final class Noop extends SecurityConfiguration.Default implements AuditConfiguration { /* … */ }
}

public final class SecurityAuditDomain { public static final String NAME = "security"; }
public abstract class SecurityAuditEvent implements AuditEvent { /* default domain="security" */ }

public final class MemberAddedEvent extends SecurityAuditEvent {
    public static MemberAddedEvent of(@NotNull String groupPath, @NotNull String memberPath) { … }
    // payload: { "groupPath", "memberPath" } — no performedBy (use CommitInfo.getUserId())
}
public final class MemberRemovedEvent extends SecurityAuditEvent { /* mirror */ }

public final class MembersAddedBulkEvent extends SecurityAuditEvent {
    public static MembersAddedBulkEvent of(@NotNull String groupPath,
                                            @NotNull Set<String> memberIds,    // staged subset, non-empty
                                            boolean isContentId,
                                            @NotNull Set<String> failedIds) { … }   // rejected subset, may be empty
    // payload: { "groupPath", "memberIds" (List<String>), "isContentId" (Boolean), "failedIds" (List<String>) }
}
public final class MembersRemovedBulkEvent extends SecurityAuditEvent { /* mirror */ }
```

---

## 5. The MutableRoot delta (the only change outside oak-security)

```java
// oak-core/src/main/java/org/apache/jackrabbit/oak/core/MutableRoot.java
import org.apache.jackrabbit.oak.spi.security.audit.AuditBufferLifecycle;   // +1 import

@Override
public void commit(@NotNull Map<String, Object> info) throws CommitFailedException {
    checkLive();
    ContentSession session = getContentSession();
    CommitInfo commitInfo = new CommitInfo(
            session.toString(), session.getAuthInfo().getUserID(), newInfoWithCommitContext(info));
    boolean merged = false;
    try {
        store.merge(builder, getCommitHook(), commitInfo);
        merged = true;
    } finally {
        if (!merged) {
            AuditBufferLifecycle.onCommitFailed(commitInfo.getSessionId());   // +1 call
        }
    }
    // … existing post-merge cleanup unchanged …
}

@Override
public void rebase() {
    checkLive();
    AuditBufferLifecycle.onRefresh(getContentSession().toString());           // +1 call
    // … existing body unchanged …
}

@Override
public final void refresh() {
    checkLive();
    AuditBufferLifecycle.onRefresh(getContentSession().toString());           // +1 call
    // … existing body unchanged …
}
```

Grace's improvement over the brief: `try { … merged = true; } finally { if (!merged) … }` instead of `catch (Throwable t) { …; throw t; }`. Handles `Error` transparently without catching it; same correctness, cleaner exception flow.

When the audit module is not deployed (or `AuditConfigurationImpl` is unbound), `AuditBufferLifecycle.listener` is the `NOOP` and the three calls resolve to volatile-read → empty-method-body returns (~5 ns each, all on cold paths). **Zero added cost on `commit()` success path.**

---

## 6. Failure-mode behavior matrix

| Scenario | Behavior | Source |
|---|---|---|
| Commit succeeds | Snapshot peeks ThreadLocal → CommitContext filled. Dispatch reads CommitContext, fires listeners (each in try/catch), `finally` clears ThreadLocal entry. | `01-arch` §5 Risk 8 |
| Hook in chain throws before Snapshot ran (rare — only `VersionablePathHook` or the global hook from `Oak.with(...)` — `PermissionHook` is itself a `PostValidationHook` per `PermissionHook.java:63` and runs after Snapshot) | `MutableRoot.commit()` `finally` calls `AuditBufferLifecycle.onCommitFailed(sessionId)` → ThreadLocal cleared. No stale events leak. | `02-oak-validation.md` §2 (corrected from brief) |
| Validator throws after Snapshot ran but before Dispatch | Snapshot already filled CommitContext. Validator throws → Dispatch never runs → `finally`-drain never fires. On NodeStore-internal retry, `ResetCommitAttributeHook` wipes CommitContext; **Snapshot peeks ThreadLocal again** (non-destructive) and refills CommitContext. If retry succeeds, Dispatch fires once with exactly the captured events. If retry ultimately fails, `MutableRoot.commit()` `finally` clears ThreadLocal. **Exactly-once delivery preserved across N retries.** | `01-arch` §5 Risk 8 table |
| Listener throws during Dispatch | Per-listener try/catch isolates. Other listeners still invoked. Outer `finally` clears ThreadLocal. Commit succeeded. | `01-arch` §5 Risk 8 |
| Listener throws `Error` (e.g. OOM) during Dispatch | Catch `Throwable` per-listener (turing's `dispatch_listenerThrowsError_otherListenersStillInvoked`). `finally` still drains. | `04-tests` §1.2 |
| `Root.refresh()` / `Root.rebase()` mid-transaction | `MutableRoot` calls `AuditBufferLifecycle.onRefresh(sessionId)` BEFORE the underlying refresh/rebase. ThreadLocal cleared. Subsequent captures land in a fresh entry. | This doc §5 |
| Listener registered after capture, before commit (TOCTOU) | Capture short-circuited at `isEnabledFor()` based on snapshot taken at capture time — late listener misses already-staged events from this transaction. **Documented behavior**, trade-off for the no-listener fast path. | `01-arch` §5 Risk 4 |
| `SystemRoot` commits | Lifecycle inherited from `MutableRoot`. System ops get audited. Listener filters via `commitInfo.getUserId().equals(CommitInfo.OAK_UNKNOWN)` (exact literal `"oak:unknown"` — `CommitInfo.java:94` normalizes `null → OAK_UNKNOWN`). | `02-oak-validation.md` §6 |
| `OakInitializer`/async-task mutations | **Not auditable in v1.** They bypass `MutableRoot.commit()` entirely and don't run `securityProvider.getConfigurations()` hooks. Documented as out-of-scope. | `02-oak-validation.md` §6, §9 |
| Audit module not deployed | `AuditBufferLifecycle.listener == NOOP`. `MutableRoot`'s 3 calls hit NOOP. Capture sites short-circuit on `AuditEvents.isEnabled()` (volatile read on null `feature` → false). Zero added cost. | This doc §5 |
| Toggle on, audit deployed, no listener | Two no-op hooks in the chain (each is a single volatile read + early return). `record()` short-circuits at `isEnabledFor()`. ~tens of ns per commit. | `01-arch` §3 |

---

## 7. Performance contract

| Regime | Per-commit overhead | Per-capture overhead |
|---|---|---|
| Audit not deployed | 3× NOOP virtual call (~15 ns on hot path) | `AuditEvents.record` is in oak-security-spi; static `volatile feature == null` → `isEnabled()` false → `record` returns. ~5 ns. |
| Toggle off, audit deployed | 3× NOOP-via-installed-buffer (volatile + atomic-bool false → return) | Same as above (toggle reads false). |
| Toggle on, no listener for domain | 2 no-op hooks in chain (Snapshot peek → null → return; Dispatch CommitContext-key-missing → return) | `isEnabledFor(domain)` checks cached `Set<String>` → false → return. No event allocation. |
| Toggle on, listener subscribed, no events captured this commit | Same as above (buffer null for this sessionId). | n/a (no capture site fires unless `isEnabledFor` true). |
| Toggle on, listener subscribed, N events captured | Snapshot: reference move (no copy). Dispatch: O(listeners × events for their domain). Listener cost is the listener's responsibility. | One `Map.of(...)` allocation per event + one live `Tracker.getServices()` lookup + linear scan over registered listeners (typical n ≤ 3). |

**Pre-merge benchmark thresholds (regression vs `disabled` baseline, same fixture/run):**

| Mode | p50 budget | p99 budget |
|---|---|---|
| disabled | <1% | <2% |
| enabled-no-listener | <2% | <4% |
| enabled-with-listener (100 events/burst) | <5% | <10% |

Crossing any p50 budget blocks the merge until ada + grace agree it's intrinsic. See `04-tests-and-benchmarks.md` §4.

---

## 8. Implementation order (for the implementer)

The skeleton in `03-skeleton/` is the source of truth for file contents. The order below sequences the work so each step compiles and tests in isolation.

1. **SPI files first** — `oak-security-spi/.../spi/security/audit/`:
   1. `AuditEvent`, `AuditEventListener`, `AuditEventAware`
   2. `AuditEvents` (with its nested `Sink` SPI), `AuditBufferLifecycle` (with its nested `Listener` SPI)
   3. `SecurityAuditDomain`, `SecurityAuditEvent`
   4. `MemberAddedEvent`, `MemberRemovedEvent`, `MembersAddedBulkEvent`, `MembersRemovedBulkEvent`
   5. `AuditConfiguration` (marker interface with `NAME` constant and `NOOP` sentinel)
   6. Apply `oak-security-spi-pom.diff` (export the new package)
   7. Write `AuditEventsTest` (SPI-level unit tests).
   8. **Build gate:** `mvn -pl oak-security-spi verify -Pcoverage`. 100% coverage on the audit package.

2. **Internal registration plumbing** — `oak-core/.../security/internal/`:
   1. Apply `InternalSecurityProvider.diff` (add `volatile AuditConfiguration auditConfiguration` field defaulting to `NOOP`; update `getConfigurations()` and `getConfiguration(Class)`).
   2. Apply `SecurityProviderRegistration.diff` (new `@Reference` with bind/unbind). Audit does NOT go into `requiredServicePids` defaults — opt-in via OSGi config.
   3. Apply `SecurityProviderBuilder.diff` (new `withAuditConfiguration` method).
   4. **Build gate:** `mvn -pl oak-core compile`.

3. **Impl** — `oak-core/.../security/audit/`:
   1. `AuditBuffer` (ThreadLocal map, lazy alloc, `peek`/`drain`/`onCommitFailed`/`onRefresh`/`isAllocatedOnCurrentThread`).
   2. `WhiteboardAuditEventListenerRegistry` (extends `AbstractServiceTracker`; `hasListenerFor(domain)` linear-scans live `Tracker.getServices()` — no cached domain set since `Tracker` exposes no add/remove notification; `dispatch(events, root, info)` with explicit rank-descending sort).
   3. `SnapshotAuditBufferHook` (regular `CommitHook`; peek-only).
   4. `DispatchAuditEventsHook` (`PostValidationHook`; drain authority in `finally`).
   5. `NoOpAuditEventListener` (registered as a default for diagnostics; TRACE only).
   6. `AuditConfigurationImpl` — `@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})`, `@Activate` order: toggle → registry → buffer → install. `@Deactivate` order: `feature.close()` → `registry.stop()` → `install(null)` × 2 → `buffer.clearAll()`.
   7. Unit tests for all six (per `04-tests-and-benchmarks.md` §1.1–1.4, §1.6).
   8. **Build gate:** `mvn -pl oak-core verify -Pcoverage`. 100% coverage on the audit package.

4. **Capture sites and `MutableRoot` wiring** — `oak-core/.../security/user/` and `oak-core/.../core/`:
   1. Apply `UserManagerImpl.diff` (instrument both `onGroupUpdate` overloads, `isEnabledFor` short-circuit, private helpers).
   2. Apply `MutableRoot.diff` (+1 import, 3 method calls, no new fields).
   3. Integration tests per `04-tests-and-benchmarks.md` §2 (`MutableRootAuditIntegrationTest` — 13 tests × 2 fixtures).
   4. Failure-mode tests per `04-tests-and-benchmarks.md` §3 (`StaleEventPreventionTest` — 7 tests).
   5. **Build gate:** `mvn -pl oak-core test -Dtest='MutableRootAuditIntegrationTest,StaleEventPreventionTest'`; then `-Dnsfixtures=DOCUMENT_NS` (requires local Mongo).

5. **Benchmark** — `oak-benchmarks/`:
   1. `AuditOverheadBenchmark` per `04-tests-and-benchmarks.md` §4. JMH-style, three modes × two fixtures × three repetitions.
   2. Attach CSV (`p50/p99` deltas vs `disabled` baseline) to the PR description.

6. **Pre-merge gate** — full sequence in `04-tests-and-benchmarks.md` §5:
   ```bash
   mvn -pl oak-security-spi verify -Pcoverage -Dskip.coverage=false
   mvn -pl oak-core verify -Pcoverage -Dskip.coverage=false
   mvn clean install -pl oak-security-spi -amd -DskipTests   # downstream still compiles
   mvn -pl oak-core test -Dtest='MutableRootAuditIntegrationTest,StaleEventPreventionTest'
   mvn -pl oak-core test -Dtest='MutableRootAuditIntegrationTest,StaleEventPreventionTest' -Dnsfixtures=DOCUMENT_NS
   mvn -pl oak-security-spi,oak-core apache-rat:check
   # Benchmark smoke: AuditOverheadBenchmark Oak-Tar --warmup 2 --iterations 5
   mvn -pl oak-jcr test -Dtest='*GroupTest,*MembershipTest'
   mvn -pl oak-security-spi,oak-core,oak-jcr,oak-benchmarks clean verify
   ```

---

## 9. Open items / follow-ups (post-v1)

1. **`org.apache.jackrabbit.oak.audit` logger inventory.** Existing SLF4J logger from OAK-2516 (2015). Used by `oak-jcr/.../delegate/SessionDelegate.java:81`. Risk: undocumented AEM/Sling operator dashboards consume it. Recommend a separate investigation before v1 ships in production. Out of scope for this PR. (shannon §2)
2. **Bulk variant `groupPath` resolution for failed memberIds.** v1 emits one bulk event with the successful subset. If listeners want to know what failed and at what path, that's a v1.1 addition — alex notes per-id path resolution has perf cost.
3. **`GroupMembershipEvent` abstract parent** for polymorphic dispatch — v1.1 if listeners ask. Grace's loose end #1.
4. **`AuditConfiguration.isActive()`** runtime introspection — v1.1 if a real consumer surfaces. Grace's loose end #2.
5. **Capture site expansion** — USER_CREATED/DELETED/PASSWORD_CHANGED, ACL_*, ACE_*, REPO_ACL_*, PRINCIPAL_POLICY_*, CUG_*, TOKEN_* — all follow the same pattern (define event class in oak-security-spi; instrument capture site with `isEnabledFor` + `record`). Mechanically straightforward, just lots of files. v1.1.
6. **`BackgroundAuditEventListener`** async wrapper analog to `BackgroundObserver` — if consumers regularly need async, ship one in `oak-security-spi`. Until then, listener contract requires non-blocking.
7. **Upstreaming (if pursued later).** Before submitting this work to `apache/jackrabbit-oak`, file an `OAK-XXXXX` JIRA ticket, rename the `FT_AUDIT` toggle constant to `FT_AUDIT_OAK-<NNNNN>` per `AGENTS.md` "Feature Toggles", and align branch (`issue/OAK-XXXXX`) + commit-message prefix (`OAK-XXXXX: …`) with upstream's `CONVENTIONS.md`. v1 on the fork does not require any of this.

---

## 10. File inventory in `03-skeleton/`

| File | Destination | LOC | Purpose |
|---|---|---|---|
| `oak-security-spi__AuditEvent.java` | oak-security-spi/.../spi/security/audit/ | ~80 | SPI interface |
| `oak-security-spi__AuditEventListener.java` | " | ~115 | Listener SPI (`getRank()` + `onCommit`) |
| `oak-security-spi__AuditEventAware.java` | " | ~40 | Marker |
| `oak-security-spi__AuditEvents.java` | " | ~130 | Static façade + Sink SPI |
| `oak-security-spi__AuditBufferLifecycle.java` | " | ~95 | Lifecycle façade + Listener SPI |
| `oak-security-spi__AuditConfiguration.java` | " | ~75 | Marker interface + NAME + NOOP |
| `oak-security-spi__SecurityAuditDomain.java` | " | ~30 | NAME constant |
| `oak-security-spi__SecurityAuditEvent.java` | " | ~55 | Abstract base for security domain |
| `oak-security-spi__MemberAddedEvent.java` | " | ~90 | Concrete event |
| `oak-security-spi__MemberRemovedEvent.java` | " | ~90 | Concrete event |
| `oak-security-spi__MembersAddedBulkEvent.java` | " | ~150 | Concrete event (bulk) — payload includes both `memberIds` and `failedIds` |
| `oak-security-spi__MembersRemovedBulkEvent.java` | " | ~150 | Concrete event (bulk) — payload includes both `memberIds` and `failedIds` |
| `oak-core__AuditConfigurationImpl.java` | oak-core/.../security/audit/ | ~220 | OSGi component |
| `oak-core__AuditBuffer.java` | " | ~150 | ThreadLocal buffer |
| `oak-core__WhiteboardAuditEventListenerRegistry.java` | " | ~130 | Listener tracker + dispatch |
| `oak-core__SnapshotAuditBufferHook.java` | " | ~95 | Peek-only |
| `oak-core__DispatchAuditEventsHook.java` | " | ~150 | PostValidationHook, `finally`-drain |
| `oak-core__NoOpAuditEventListener.java` | " | ~60 | Default listener (TRACE) |
| `MutableRoot.diff` | oak-core/.../core/ | 1 import + 3 calls | The only change outside oak-security |
| `UserManagerImpl.diff` | oak-core/.../security/user/ | ~70 | Capture sites (single + bulk) |
| `InternalSecurityProvider.diff` | oak-core/.../security/internal/ | ~30 | New `volatile` field + accessors |
| `SecurityProviderRegistration.diff` | " | ~40 | `@Reference` + bind/unbind |
| `SecurityProviderBuilder.diff` | " | ~30 | `withAuditConfiguration` |
| `oak-security-spi-pom.diff` | oak-security-spi/ | 1 line | Add to `<Export-Package>` |
| `README.md` | (skeleton dir) | ~150 | Local inventory + deprecated-skip note |

**Totals:** 19 Java files (~2370 LOC) + 6 unified diffs + README. All diffs `git apply --check`-verified individually and together against trunk.

---

## 11. Why this design — the one-paragraph summary

Capture at API call sites in oak-security via `AuditEvents.record(root, event)`; gate behind `AuditEvents.isEnabledFor(domain)` (volatile + live `Tracker.getServices()` + linear scan; constant-time `Collections.emptyList()` short-circuit when no listener is registered) for zero allocation in the no-listener fast path. Buffer per-session in a ThreadLocal map keyed by `sessionId`. Dispatch via two commit hooks contributed by a new `AuditConfiguration` (`SecurityConfiguration` sub-interface) — a peek-only `SnapshotAuditBufferHook` (regular `CommitHook`, runs before validators) and a `DispatchAuditEventsHook` (`PostValidationHook`, runs after validators, holds drain authority in a `finally` block). Listeners discovered via Oak's Whiteboard, sorted by explicit `getRank()` (since `DefaultWhiteboard` doesn't honor `service.ranking`). Same-thread dispatch end-to-end, so SLF4J MDC and HTTP request context are preserved without explicit propagation. Three `AuditBufferLifecycle` calls in `MutableRoot.java` (failure catch + refresh + rebase) close the buffer-cleanup loop and route to NOOP when audit isn't deployed — making the whole pipeline zero-cost when off. The `AuditConfiguration` marker interface + 7th `@Reference` slot in `SecurityProviderRegistration` (`InternalSecurityProvider`, `SecurityProviderBuilder` updated to match) makes the audit configuration first-class in Oak's security provider, deployable in OSGi without bypassing the existing security composition.

---

## 12. Sign-off checklist (before merging on the fork)

- [ ] Plan reviewed and approved by the human author.
- [ ] Branch created on the fork per the fork's conventions.
- [ ] All 6 unified diffs apply cleanly (`git apply --check`).
- [ ] All Java files have the Apache 2.0 license header (RAT pre-merge check).
- [ ] JUnit 4 only (no JUnit 5).
- [ ] No wildcard imports.
- [ ] `@NotNull` / `@Nullable` from `org.jetbrains.annotations` at all API boundaries.
- [ ] `mvn -pl oak-security-spi,oak-core verify -Pcoverage` passes with 100% on the audit packages.
- [ ] Integration tests pass on both SEGMENT_TAR and DOCUMENT_NS fixtures.
- [ ] Benchmark CSV attached to PR description; all p50/p99 budgets met.
- [ ] PR description includes: summary of the new SPI, feature-toggle name (`FT_AUDIT`), and the three failure-mode tests as the smoking-gun behavioral proof.
- [ ] Documented operator-facing change: deprecated `SecurityProviderImpl` does NOT receive audit; migration to `SecurityProviderBuilder` required.
- [ ] (Recommended) Investigation of OAK-2516 logger consumers complete OR deferred for a follow-up.
- [ ] (If upstreaming) OAK-XXXXX ticket filed; `FT_AUDIT` renamed to `FT_AUDIT_OAK-<NNNNN>`; branch + commit conventions aligned with `CONVENTIONS.md`.
