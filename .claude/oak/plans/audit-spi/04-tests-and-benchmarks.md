# Audit SPI — Tests & Benchmarks Plan (Path α)

**Owner:** turing.
**Reads:** `00-design-brief.md` (authoritative). Cross-checks against alex's `02-oak-validation.md`, grace's `03-skeleton/*`, ada's `01-architecture.md` once those land.
**Scope of this file:** the exhaustive test matrix, parameterized fixture strategy, failure-mode coverage, coverage targets, benchmark plan, and the pre-merge gate. No source code edits in this plan — only test/benchmark inventory.

Conventions:
- JUnit 4 (4.13.x) + Mockito 5 (loaded as Java agent) for unit tests. No JUnit 5.
- Use existing module style — `MutableRootTest` already exists in `oak-core/.../core/`, security tests under `oak-core/.../security/...`.
- Coverage budgets: **100%** for `oak-security-spi/.../audit/*` (façades) and `oak-core/.../security/audit/*` (impl). **>80%** for any other touched lines.
- Test naming: `methodName_scenario_expectedOutcome` — matches Oak's existing style (see `MutableRootTest`, `PermissionHookTest`).
- All assertions must verify observable behavior, never internal mock interactions alone — challenge any tests that pass without exercising a real failure path.

---

## 1. Unit tests

Each bullet is one `@Test`. Format: `ClassName.testName` + one-line description of the property being asserted.

### 1.1 `AuditBufferTest`

Lives in `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/AuditBufferTest.java`.
Exercises the `ThreadLocal<Map<String,List<AuditEvent>>>` buffer, its lifecycle hooks (`onCommitFailed`, `onRefresh`), and lazy allocation. Pure unit — no NodeStore, no Whiteboard.

**API surface (updated per grace's skeleton):** `append`, `peek` (non-destructive read), `drain` (read-and-clear), `onCommitFailed`, `onRefresh`, package-private `isAllocatedOnCurrentThread()` (test seam).

- `AuditBufferTest.append_singleSessionSingleThread_storesAndPeeksEvents` — append two events under sessionId `"s-1"`, `peek("s-1")` returns the same list in insertion order; `isAllocatedOnCurrentThread()` is `true`.
- `AuditBufferTest.peek_calledTwice_returnsSameNonNullList` — `peek` is non-destructive; second call returns equal contents (validates the new non-destructive contract that lets Snapshot run idempotently on merge retry).
- `AuditBufferTest.drain_calledOnce_returnsListAndClearsEntry` — `drain("s-1")` returns the staged events; immediate `peek("s-1")` returns `null`.
- `AuditBufferTest.drain_calledTwice_returnsNullSecondTime` — `drain` is destructive; second call is `null` (no duplicate dispatch).
- `AuditBufferTest.peek_nonExistentSession_returnsNullNoAllocation` — `peek("nope")` returns `null` and `isAllocatedOnCurrentThread()` is `false`.
- `AuditBufferTest.drain_nonExistentSession_returnsNullNoAllocation` — same property for `drain`.
- `AuditBufferTest.append_twoSessionsSameThread_eventsKeptSeparate` — append on `"s-1"` and `"s-2"` on the same thread; `peek` for each returns its own list, with no cross-contamination; `drain` of one leaves the other intact.
- `AuditBufferTest.append_sameSessionTwoThreads_eventsKeptSeparate` — spawn two threads, both append under sessionId `"s-1"`, each thread's `peek("s-1")` returns only its own appends (validates `ThreadLocal` not shared key collision).
- `AuditBufferTest.onCommitFailed_clearsOnlyTargetSession` — pre-populate `"s-1"` and `"s-2"`; call `onCommitFailed("s-1")`; `peek("s-1")` is null, `peek("s-2")` still returns its events.
- `AuditBufferTest.onCommitFailed_unknownSession_isIdempotent` — call with `"never-existed"`; no throw; no entry created; `isAllocatedOnCurrentThread()` stays `false` if it was `false` (lazy invariant).
- `AuditBufferTest.onCommitFailed_calledTwice_idempotent` — populate, call `onCommitFailed` twice; no throw on second call, `peek` still returns `null`.
- `AuditBufferTest.onRefresh_clearsTargetSessionOnly` — same as `onCommitFailed` but exercises the refresh entry point (semantically equivalent today, but kept distinct so behavior can diverge later).
- `AuditBufferTest.lazyAllocation_peekBeforeAppend_doesNotAllocateMap` — call `peek` before any `append`; `isAllocatedOnCurrentThread()` returns `false` afterwards (lazy invariant — empty peek does not materialize the per-thread map).
- `AuditBufferTest.lazyAllocation_drainBeforeAppend_doesNotAllocateMap` — same for `drain`.
- `AuditBufferTest.threadLocal_threadDeath_doesNotLeakMap` — start thread, append, let it die; reference to the buffer instance (via `WeakReference` on the map) is GC-eligible on next GC (use `System.gc()` + assertion with bounded retries). Validates no permanent leak via thread-bound storage.
- `AuditBufferTest.append_nullEvent_throwsNPE` — defensive: `append("s-1", null)` throws `NullPointerException` (don't silently corrupt the buffer).
- `AuditBufferTest.append_nullSessionId_throwsNPE` — defensive: `append(null, evt)` throws `NullPointerException`.
- `AuditBufferTest.clearAll_calledOnCurrentThread_purgesCurrentThreadMap` — append events under two sessionIds on the calling thread; call `clearAll()`; `peek` for both returns `null`; `isAllocatedOnCurrentThread()` is `false`. Pins the contract that `clearAll` is the deactivate-time scrub for the calling thread's storage.
- `AuditBufferTest.clearAll_doesNotTouchOtherThreads_documentedBoundedLeak` — append events on thread A and thread B; call `clearAll()` on thread A; thread B's `peek` STILL returns its events. This is the documented bounded cross-thread leak grace called out — `clearAll` is `ThreadLocal.remove()` on the calling thread only, and the design's acceptance criterion is that any other thread's stale state will be flushed by its next `onRefresh`/`onCommitFailed` or by thread death. Test pins this *as observed behavior*, not as a defect.

**Coverage:** 100% lines and branches across `append`/`peek`/`drain`/`onCommitFailed`/`onRefresh`/`clearAll`/`isAllocatedOnCurrentThread`.

### 1.2 `WhiteboardAuditEventListenerRegistryTest`

Lives in `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/WhiteboardAuditEventListenerRegistryTest.java`.
Uses `org.apache.jackrabbit.oak.spi.whiteboard.DefaultWhiteboard` (real impl, no mocks) per Oak convention — `DefaultWhiteboard` is already used in `oak-core` tests.

- `WhiteboardAuditEventListenerRegistryTest.hasListenerFor_noListeners_returnsFalse` — empty whiteboard; `hasListenerFor("security")` is `false`.
- `WhiteboardAuditEventListenerRegistryTest.hasListenerFor_listenerRegisteredAfterFirstCall_returnsTrue` — call `hasListenerFor("security")` first (with no listener) → returns `false`. THEN register a listener for `"security"`. Re-call `hasListenerFor("security")` → returns `true`. This is the smoking-gun test that pins live lookup, not stale-cache behavior.
- `WhiteboardAuditEventListenerRegistryTest.hasListenerFor_listenerRegisteredForDomain_returnsTrue` — register a listener whose `getDomain()` returns `"security"`; `hasListenerFor("security")` is `true`.
- `WhiteboardAuditEventListenerRegistryTest.hasListenerFor_listenerForOtherDomain_returnsFalse` — register listener for domain `"replication"`; `hasListenerFor("security")` is `false`.
- `WhiteboardAuditEventListenerRegistryTest.hasListenerFor_listenerDeregistered_returnsFalse` — register listener; `Registration.unregister()`; `hasListenerFor("security")` returns `false` (live state, not cached).
- `WhiteboardAuditEventListenerRegistryTest.hasListenerFor_multipleListenersSameDomain_returnsTrue` — register two listeners both for `"security"`; `hasListenerFor("security")` is `true` after both; remains `true` after deregistering only one; returns `false` after deregistering both.
- `WhiteboardAuditEventListenerRegistryTest.hasAnyListener_emptyWhiteboard_returnsFalse` — initial state; `hasAnyListener()` is `false`.
- `WhiteboardAuditEventListenerRegistryTest.hasAnyListener_listenerRegistered_returnsTrue` — register listener (any domain); `hasAnyListener()` is `true`.
- `WhiteboardAuditEventListenerRegistryTest.hasAnyListener_listenerDeregistered_returnsFalse` — register, then deregister; `hasAnyListener()` is `false`.
- `WhiteboardAuditEventListenerRegistryTest.dispatch_singleListener_invokedOnce` — register one listener, dispatch a 1-event list for its domain; listener `onCommit` called exactly once with the same list.
- `WhiteboardAuditEventListenerRegistryTest.dispatch_multipleListenersSameDomain_allInvoked` — register two listeners; dispatch; both receive the same event list (no second copy semantics — list is immutable view).
- `WhiteboardAuditEventListenerRegistryTest.dispatch_listenersForOtherDomains_notInvoked` — register listener for `"security"` and `"replication"`; dispatch for `"security"` only; replication listener never called.
- `WhiteboardAuditEventListenerRegistryTest.dispatch_listenersSortedByRankingDescending` — register three listeners with ranking `0`, `100`, `-50`; verify call order via shared atomic counter — rank-100 first, rank-0 second, rank-(-50) last.
- `WhiteboardAuditEventListenerRegistryTest.dispatch_listenerThrowsRuntimeException_otherListenersStillInvoked` — register two listeners; first throws `RuntimeException`; second still invoked. Verify exception is logged (capture via SLF4J test appender) and not propagated.
- `WhiteboardAuditEventListenerRegistryTest.dispatch_listenerThrowsError_otherListenersStillInvoked` — same with `Error` (e.g., `OutOfMemoryError`); listener isolation must not let an `Error` from one listener bubble up and abort the dispatch chain mid-commit. Catch `Throwable`, log, continue.
- `WhiteboardAuditEventListenerRegistryTest.dispatch_emptyEventList_listenersNotInvoked` — call dispatch with `events.isEmpty()`; no listener `onCommit` invocation (no point waking them).
- `WhiteboardAuditEventListenerRegistryTest.dispatch_nullDomainFromListener_skipped` — defensive: a misbehaving listener returning `getDomain() == null` must be skipped (not crash the dispatcher), and a warning logged once.
- `WhiteboardAuditEventListenerRegistryTest.concurrentRegistrationAndCheck_consistent` — concurrency: 8 threads alternating `register`/`unregister`/`hasListenerFor`/`getListeners`; final state matches the actual whiteboard contents (use `CountDownLatch` + asserts after). Live-lookup correctness under load — no stale-cache reads possible since there is no cache.

**Coverage:** 100%. The `hasListenerFor` live-lookup behavior is the single most correctness-critical piece — the `hasListenerFor_listenerRegisteredAfterFirstCall_returnsTrue` test pins the no-stale-cache invariant.

### 1.3 `SnapshotAuditBufferHookTest`

Lives in `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/SnapshotAuditBufferHookTest.java`.
Uses `MemoryNodeStore` per Oak convention for hook tests (e.g., `PermissionHookTest`). The `CommitInfo` carries a `CommitContext`; assertions read its private attribute.

**Contract update (grace's skeleton):** Snapshot is **non-destructive**. It uses `AuditBuffer.peek(sessionId)` to copy staged events into `CommitContext` and leaves the `ThreadLocal` untouched. `DispatchAuditEventsHook` is the sole authority that calls `drain(sessionId)` (in a `finally` block — see §1.4). This change closes the "retry-loses-events" residual gap flagged in the design brief: on a merge retry, the second Snapshot still sees the events.

- `SnapshotAuditBufferHookTest.processCommit_bufferHasEvents_copiedToCommitContext` — pre-populate `AuditBuffer` for sessionId `"s-1"` with 2 events; create `CommitInfo` with sessionId `"s-1"` and a fresh `CommitContext`; run `SnapshotAuditBufferHook.processCommit(before, after, commitInfo)`; assert `CommitContext` now contains an entry keyed by the private constant with the same 2 events.
- `SnapshotAuditBufferHookTest.processCommit_bufferHasEvents_threadLocalNotCleared` — same setup; after hook runs, `AuditBuffer.peek(sessionId)` still returns the same 2 events (snapshot is **non-destructive** — pins the retry-safety contract).
- `SnapshotAuditBufferHookTest.processCommit_bufferEmpty_noOp` — buffer has no entry for sessionId; hook runs; `CommitContext` has no audit key set; assert the hook returns `after` unchanged.
- `SnapshotAuditBufferHookTest.processCommit_calledTwiceSameCommit_idempotent` — invoke hook twice with same `CommitInfo` (simulates merge retry where validators fail and merge restarts). First call writes events to `CommitContext`. After `ResetCommitAttributeHook` resets `CommitContext` between attempts, second call still sees the same events in the buffer (non-destructive!) and re-writes the same payload to `CommitContext`. Listener-observable behavior is unchanged. Test asserts: `peek` returns events both before and after each invocation, and `CommitContext` payload after the second call matches the payload after the first.
- `SnapshotAuditBufferHookTest.processCommit_toggleDisabled_noOp` — `Feature` reports disabled; hook short-circuits before touching `AuditBuffer` (verify via spy that `AuditBuffer.peek` was not called).
- `SnapshotAuditBufferHookTest.processCommit_noListenersForAnyDomain_skipsCommitContextWrite` — toggle on, but `WhiteboardAuditEventListenerRegistry.hasAnyListener()` is false; hook short-circuits BEFORE peeking the buffer. Buffer is **not** drained either — `MutableRoot.commit`'s normal lifecycle handles cleanup on the next refresh/failed-commit, and on a successful commit with no listener Dispatch's `finally`-drain still runs (verified in §1.4). This avoids paying the peek cost in the no-listener fast path.
- `SnapshotAuditBufferHookTest.processCommit_commitContextMissing_logsAndContinues` — defensive: caller forgot to install a `CommitContext` (shouldn't happen — `MutableRoot` always installs — but assert hook doesn't NPE; logs at WARN once).
- `SnapshotAuditBufferHookTest.processCommit_eventListIsImmutableInCommitContext` — read the stored list from `CommitContext`; verify `add()` throws `UnsupportedOperationException` (prevents the dispatch hook from mutating it under contention).
- `SnapshotAuditBufferHookTest.processCommit_returnsAfterStateUnchanged` — hook is pure side-effect on `CommitContext`; `processCommit` must return its `after` argument identity-equal (`==`), not a rebuild.

### 1.4 `DispatchAuditEventsHookTest`

Lives in `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/DispatchAuditEventsHookTest.java`.
Verifies `DispatchAuditEventsHook implements PostValidationHook`.

**Constructor (grace's skeleton):** `DispatchAuditEventsHook(Feature toggle, AuditBuffer buffer, WhiteboardAuditEventListenerRegistry registry)`. Tests construct directly with a real `AuditBuffer` (not a mock) so the `finally`-drain contract can be asserted end-to-end.

- `DispatchAuditEventsHookTest.processCommit_commitContextMissing_noDispatchNoDrain` — no `CommitContext` in `CommitInfo`; hook returns immediately; listener not invoked; `buffer.peek(sessionId)` unchanged (nothing to drain — we never claimed responsibility).
- `DispatchAuditEventsHookTest.processCommit_commitContextKeyMissing_noDispatchNoDrain` — `CommitContext` present but no audit key (Snapshot was a no-op); hook returns immediately; buffer unchanged.
- `DispatchAuditEventsHookTest.processCommit_singleDomainEvents_dispatchedToMatchingListener` — `CommitContext` has 3 events, all domain `"security"`; one `"security"` listener registered; listener `onCommit` invoked once with all 3 events.
- `DispatchAuditEventsHookTest.processCommit_singleDomainEvents_bufferDrainedAfterDispatch` — same setup; after `processCommit` returns, `buffer.peek(sessionId)` returns `null` (drained in `finally`). Pins the new "Dispatch is the sole drain authority" contract.
- `DispatchAuditEventsHookTest.processCommit_multipleDomains_groupedAndDispatchedSeparately` — `CommitContext` has 4 events: 2 `"security"`, 2 `"replication"`; verify `"security"` listener receives only the 2 `"security"` events (same order they were captured), `"replication"` listener receives only the 2 `"replication"` events.
- `DispatchAuditEventsHookTest.processCommit_domainOrderingDeterministic` — same as above; assert dispatch order across domains is alphabetical (stable, predictable). Defensive against `HashMap` iteration nondeterminism — group container must be `TreeMap` or sorted.
- `DispatchAuditEventsHookTest.processCommit_eventOrderingPreservedPerDomain` — register listener that records the events it sees; verify list order matches capture order (this is the JIRA-debuggability invariant).
- `DispatchAuditEventsHookTest.processCommit_listenerExceptionDoesNotPropagate` — one listener throws `RuntimeException`; hook completes; verify `after` is returned identity-equal to input; second listener (for a different domain) still invoked. Asserts the post-validation chain is not aborted.
- `DispatchAuditEventsHookTest.processCommit_listenerThrows_bufferStillDrainedInFinally` — listener throws `RuntimeException`; assert `buffer.peek(sessionId)` returns `null` after `processCommit` returns. **Non-negotiable** — without this, a misbehaving listener would leave staged events to leak into the next commit. Pins the `finally`-block guarantee.
- `DispatchAuditEventsHookTest.processCommit_listenerThrowsError_bufferStillDrainedInFinally` — same with an `Error` (e.g., `OutOfMemoryError`); `finally` must drain. The hook itself must not propagate the `Error` (catch `Throwable` per-listener), but if for any reason it does, the `finally` still scrubs the buffer.
- `DispatchAuditEventsHookTest.processCommit_noListenerForDomain_eventsDroppedAndBufferDrained` — `CommitContext` has events for `"replication"`, no listener for `"replication"` registered; events silently dropped AND buffer drained. The drain is unconditional once Snapshot wrote to `CommitContext` (otherwise the same events would re-leak on subsequent commits).
- `DispatchAuditEventsHookTest.processCommit_isPostValidationHook_marker` — `instanceof PostValidationHook`. Trivial but enforces the chain-position contract.
- `DispatchAuditEventsHookTest.processCommit_toggleDisabled_noOp` — feature toggle off; hook short-circuits before reading `CommitContext`; buffer untouched (toggle-off is genuinely zero-side-effect).
- `DispatchAuditEventsHookTest.processCommit_returnsAfterStateIdentity` — pure side-effect hook; `processCommit(before, after, info) == after`.
- `DispatchAuditEventsHookTest.dispatch_runsOnMergeThread` — capture `Thread.currentThread()` inside listener; assert equal to the thread that invoked `processCommit` (this is the "MDC/Sling context preserved" contract).

### 1.5 `AuditEventsTest`

Lives in `oak-security-spi/src/test/java/org/apache/jackrabbit/oak/spi/security/audit/AuditEventsTest.java`.
SPI-level tests for the static façade `AuditEvents.record(Root, AuditEvent)` and `AuditEvents.isEnabled()`. Pure unit, mostly Mockito.

**Reset between tests:** `@After` calls `AuditEvents.install(null)` to restore the `NOOP` sink (grace's confirmed API — single `volatile Sink` field, swap-on-install, null restores NOOP). No reflection, no separate test-only reset method.

- `AuditEventsTest.isEnabled_noSinkInstalled_returnsFalse` — fresh state (`install(null)` in `@Before`); `isEnabled()` is `false`; subsequent `record(...)` is a no-op (verify `Root.getContentSession()` not called).
- `AuditEventsTest.isEnabled_sinkInstalledFeatureToggleOff_returnsFalse` — install a sink whose backing `Feature.isEnabled()` returns `false`; `AuditEvents.isEnabled()` is `false`.
- `AuditEventsTest.isEnabled_sinkInstalledFeatureToggleOn_returnsTrue` — install a sink whose `Feature.isEnabled()` returns `true`; `AuditEvents.isEnabled()` is `true`.
- `AuditEventsTest.install_nullArg_restoresNoopSink` — install a real sink; record an event; install `null`; `isEnabled()` is `false`; record is no-op (verify via captured count on the previously installed sink — count unchanged after `install(null)`).
- `AuditEventsTest.install_replacesExistingSink` — install sink A, install sink B, record event; only B's count incremented (A no longer receives). Pins the swap-not-stack semantic.
- `AuditEventsTest.record_toggleDisabled_noOp` — sink installed but toggle off; `AuditEvents.record(root, event)`; verify `root.getContentSession()` not called, no buffer append.
- `AuditEventsTest.record_toggleEnabledNoListenerForDomain_noOp` — toggle on, but the sink's `hasListenerFor(event.getDomain())` short-circuit returns `false`; verify buffer not touched (no allocation cost in this case).
- `AuditEventsTest.record_toggleEnabledListenerPresent_appendedToBuffer` — toggle on, sink reports listener for `"security"`; record a `MemberAddedEvent`; verify `AuditBuffer.append(sessionId, event)` invoked with the session-derived id from `root.getContentSession().toString()`.
- `AuditEventsTest.record_rootNotInstanceOfAuditEventAware_noOp` — mock `Root` that does **not** implement the marker; `record` short-circuits gracefully (no `ClassCastException`). Confirms the design's safety net for unusual Root impls (e.g., subclasses in tests).
- `AuditEventsTest.record_nullEvent_throwsNPE` — defensive: `record(root, null)`.
- `AuditEventsTest.record_nullRoot_throwsNPE` — defensive: `record(null, event)`.

### 1.6 `AuditConfigurationTest`

Lives in `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/AuditConfigurationTest.java`.
Validates the OSGi component / `SecurityConfiguration` impl.

**Deactivate ordering contract (per grace's round-4 update, ada/alex race observation):** `@Deactivate` must execute these four steps in this order:
  1. `featureToggle.close()` — prevents new captures from passing `isEnabled()`.
  2. `registry.stop()` — prevents new dispatch.
  3. NOOP installs — `AuditEvents.install(null)` and `AuditBufferLifecycle.install(null)`.
  4. `buffer.clearAll()` — purges current-thread storage on the deactivating thread.

Any other ordering opens a race where a concurrent capture site writes to the buffer after we cleared it.

- `AuditConfigurationTest.activate_installsLifecycleListener` — call `@Activate`; verify `AuditBufferLifecycle.install(...)` was called with a non-NOOP listener (use a test seam — expose the current listener via package-private accessor).
- `AuditConfigurationTest.activate_installsAuditEventsSink` — call `@Activate`; verify `AuditEvents.install(sink)` was called with a non-NOOP sink. Confirms both static façades are wired together in one activation.
- `AuditConfigurationTest.deactivate_reinstallsNoopLifecycle` — call `@Deactivate`; verify current `AuditBufferLifecycle` listener is the NOOP sentinel.
- `AuditConfigurationTest.deactivate_reinstallsNoopAuditEventsSink` — call `@Deactivate`; verify `AuditEvents.isEnabled()` returns `false` (NOOP sink reports disabled regardless of toggle state).
- `AuditConfigurationTest.activate_registersFeatureToggle` — verify `Feature.newFeature(name, whiteboard)` was invoked with name `FT_AUDIT` (fork convention; upstreaming would rename to `FT_AUDIT_OAK-<NNNNN>`).
- `AuditConfigurationTest.deactivate_closesFeatureToggle` — verify `Feature.close()` was called (unregisters from whiteboard, prevents leak).
- `AuditConfigurationTest.deactivate_orderIsToggleCloseRegistryStopNoopInstallBufferClearAll` — record method invocations into an ordered list via Mockito `InOrder` spying on `featureToggle`, `registry`, the static façades (test seam), and `buffer`; assert exact sequence: `(1) featureToggle.close()` → `(2) registry.stop()` → `(3) AuditEvents.install(null) + AuditBufferLifecycle.install(null)` → `(4) buffer.clearAll()`. **Critical race-prevention test** — any reordering is a regression.
- `AuditConfigurationTest.deactivate_callsBufferClearAll` — verify `buffer.clearAll()` invoked at deactivate time (separate assertion in case `InOrder` test above is reworked).
- `AuditConfigurationTest.getCommitHooks_returnsTwoHooks` — `getCommitHooks("default")` returns a non-empty iterable; sizing is exactly 2.
- `AuditConfigurationTest.getCommitHooks_firstHookIsSnapshot` — first hook in the returned list is `instanceof SnapshotAuditBufferHook`. (Order matters — snapshot must run before dispatch.)
- `AuditConfigurationTest.getCommitHooks_secondHookIsPostValidationDispatch` — second hook is `instanceof DispatchAuditEventsHook` AND `instanceof PostValidationHook`. Asserts the design's "regular hook + PostValidationHook" shape.
- `AuditConfigurationTest.getCommitHooks_noPostValidationHookInRegularSlot` — first hook is **not** a `PostValidationHook`. Defensive — guarantees the chain position contract holds.
- `AuditConfigurationTest.getValidators_returnsEmpty` — audit does not validate; `getValidators(...)` returns empty (chain weight).
- `AuditConfigurationTest.getName_returnsAudit` — `SecurityConfiguration.getName()` returns `"audit"` (matches the constant in `SecurityAuditDomain`/equivalent).
- `AuditConfigurationTest.toString_includesFeatureName` — debug-friendly toString includes the toggle name (operability).
- `AuditConfigurationTest.activate_deactivate_activate_idempotent` — cycle twice; final state matches first activation (no double-registration, no NPE on second activate).
- `AuditConfigurationTest.NOOP_returnsEmptyCommitHooks` — `AuditConfiguration.NOOP.getCommitHooks(workspaceName)` returns empty iterable. Pins the BlobAccessProvider-style NoOp pattern grace adopted: callers do not test for null around `getConfiguration(AuditConfiguration.class)`; they get a real `NOOP` instance whose hooks are empty.
- `AuditConfigurationTest.NOOP_returnsEmptyValidators` — `AuditConfiguration.NOOP.getValidators(...)` returns empty.
- `AuditConfigurationTest.NOOP_isNotNull` — `AuditConfiguration.NOOP != null`. Trivial but pins the no-null-contract.

**Wiring test moved out of this class.** The `setAuditConfiguration(null) → NOOP` round-trip lives in `InternalSecurityProviderTest` (same `oak.security.internal` package — `InternalSecurityProvider` is package-private; reaching from `oak.security.audit` would need reflection). See §1.8.

### 1.8 `InternalSecurityProviderTest` (incremental coverage only)

Lives in `oak-core/src/test/java/org/apache/jackrabbit/oak/security/internal/InternalSecurityProviderTest.java`. This file already exists in trunk (wiring tests for other configurations). Audit-related additions:

- `InternalSecurityProviderTest.setAuditConfigurationNull_restoresNoopIdentity` — after `setAuditConfiguration(null)`, `getConfiguration(AuditConfiguration.class) == AuditConfiguration.NOOP` (identity comparison). Mirrors the existing `setAuthenticationConfiguration(null)` / `setUserConfiguration(null)` patterns in the same class.
- `InternalSecurityProviderTest.setAuditConfigurationNonNull_storesAndReturnsInstance` — after `setAuditConfiguration(impl)`, `getConfiguration(AuditConfiguration.class) == impl` (identity).
- `InternalSecurityProviderTest.getConfiguration_audit_neverReturnsNull` — fresh `InternalSecurityProvider`; `getConfiguration(AuditConfiguration.class) != null` (defaults to NOOP). Pins the no-null-on-the-public-API contract.

### 1.7 `MembersAddedBulkEventTest` and `MembersRemovedBulkEventTest`

Lives in `oak-security-spi/src/test/java/org/apache/jackrabbit/oak/spi/security/audit/MembersAddedBulkEventTest.java` and `MembersRemovedBulkEventTest.java`. Pure SPI unit tests on the immutable value type. Mirror tests are listed once for `MembersAdded*`; the `MembersRemoved*` class has the same shape and same number of tests.

**Contract (round 4 + team-lead's `failedIds` reversal + alex's `isContentId` rename + grace's "no equals/hashCode in v1"):**

Factory signature:
```java
public static MembersAddedBulkEvent of(@NotNull String groupPath,
                                        @NotNull Set<String> memberIds,
                                        boolean isContentId,
                                        @NotNull Set<String> failedIds);
```

Shape:
- **Typed accessors** return `Set<String>` (immutable). `getMemberIds()`, `getFailedIds()`.
- **Payload map** stores `List<String>` (serializer-friendly per team-lead — `Set` doesn't survive most JSON/XML round-trips cleanly). Use `List.copyOf` for immutability. Order in the `List` preserves the caller's iteration order.
- `memberIds` must be **non-empty** (factory throws `IllegalArgumentException`). `failedIds` may be empty (an all-succeeded operation is the common case).
- Capture-site shortcut `UserManagerImpl.recordBulkMembershipAuditEvent` skips emit when `memberIds.isEmpty()` (post-filter), upholding the constructor invariant.
- **No `equals`/`hashCode` override in v1** (per grace): identity equality only. Rationale — `SecurityAuditEvent.timestamp` would make any content-equality choice surprising, and Set-vs-List ambiguity (accessor returns Set, payload stores List) means picking one for equality is a design decision we can defer. No production caller needs event content-equality (listeners process, they don't dedupe).
- **`isContentId` (singular)** — flag name in the payload key (`PAYLOAD_IS_CONTENT_ID = "isContentId"`), accessor (`isContentId()`), and field. Matches the source param name in `UserManagerImpl.onGroupUpdate(... boolean isContentId, ...)`. The semantic is unchanged: `true` = IDs are JCR content-IDs; `false` = IDs are authorizable-IDs.
- **Failure-layer scope of `failedIds`** (per grace, alex's Javadoc clarification): `failedIds` is the **MembershipWriter (staging-layer)** partial-failure subset only. It does NOT capture validator rejections — validator failures roll back the whole commit and discard the event entirely (Path α: `MutableRoot.commit`'s catch → `AuditBufferLifecycle.onCommitFailed`). An event with `failedIds == Set.of()` does NOT imply that downstream validators (PermissionValidator, etc.) passed; it only implies the staging layer accepted every ID. Audit consumers must not infer commit success from `failedIds.isEmpty()`. Consumers that need "commit succeeded" semantics observe via the dispatch path itself — if their listener was invoked, the commit succeeded by definition (PostValidationHook position).

- `MembersAddedBulkEventTest.factory_emptyMemberIds_throwsIAE` — `of(groupPath, Set.of(), false, Set.of())` throws `IllegalArgumentException` with a message mentioning `memberIds`. Pins the upstream invariant.
- `MembersAddedBulkEventTest.factory_emptyFailedIdsAccepted` — `of(groupPath, Set.of("u1"), false, Set.of())` returns a valid event with empty `getFailedIds()`. Common case: all-succeeded operation.
- `MembersAddedBulkEventTest.factory_nullMemberIds_throwsNPE` — defensive.
- `MembersAddedBulkEventTest.factory_nullFailedIds_throwsNPE` — defensive: `failedIds == null` is **not** the same as empty.
- `MembersAddedBulkEventTest.factory_nullGroupPath_throwsNPE` — defensive.
- `MembersAddedBulkEventTest.factory_validInput_returnsEventWithExpectedAccessors` — `of("/groups/admins", LinkedHashSet["u1","u2","u3"], false, LinkedHashSet["bad-1"])`; verify `getGroupPath()`, `getMemberIds()`, `isContentId()`, `getFailedIds()` return inputs.
- `MembersAddedBulkEventTest.accessor_getMemberIds_returnsImmutableSet` — `event.getMemberIds().add("u4")` throws `UnsupportedOperationException`. Accessor returns `Set`, even though payload is `List`.
- `MembersAddedBulkEventTest.accessor_getFailedIds_returnsImmutableSet` — same property for `failedIds`.
- `MembersAddedBulkEventTest.payload_memberIdsValueIsList` — assert `event.getPayload().get(PAYLOAD_MEMBER_IDS) instanceof List` (NOT `Set`). Pins the serializer-friendly representation — a listener that round-trips the payload through JSON/XML gets a usable structure.
- `MembersAddedBulkEventTest.payload_failedIdsValueIsList` — same for `failedIds`.
- `MembersAddedBulkEventTest.payload_isImmutable` — `event.getPayload().put("x", "y")` throws `UnsupportedOperationException` (top-level map immutable); nested lists also immutable.
- `MembersAddedBulkEventTest.payload_memberIdsPreservesInsertionOrder` — input `LinkedHashSet["u3","u1","u2"]`; `event.getPayload().get(PAYLOAD_MEMBER_IDS)` is a `List` iterating in `[u3, u1, u2]` order; `event.getMemberIds()` iterates in the same order. Debug-critical when chunking a 1000-member batch in logs. **This is the semantic test grace asked for** — pins behavior, not equality.
- `MembersAddedBulkEventTest.payload_failedIdsPreservesInsertionOrder` — same for `failedIds`.
- `MembersAddedBulkEventTest.isContentIdFlag_propagatedTrue` — pass `isContentId=true`; assert `event.isContentId()` is `true` and `event.getPayload().get(PAYLOAD_IS_CONTENT_ID)` is `Boolean.TRUE`.
- `MembersAddedBulkEventTest.isContentIdFlag_propagatedFalse` — same for `false`.
- `MembersAddedBulkEventTest.getDomain_returnsSecurity` — `event.getDomain()` returns the constant `SecurityAuditDomain.NAME` (`"security"`).
- `MembersAddedBulkEventTest.getType_distinctFromSingleVariant` — `event.getType()` returns a constant distinct from `MemberAddedEvent.TYPE`. Listeners switching on type must be able to tell bulk from single.
- `MembersAddedBulkEventTest.toString_includesMemberAndFailedCounts_notFullIdLists` — `toString()` includes both counts (`"3 members, 1 failed"`) rather than the full ID lists. Operability concern: 10k-member events shouldn't blow logs.

(Mirror set for `MembersRemovedBulkEventTest` — 16 tests, identical shape.)

**Not in §1.7** (per grace's round-5 decisions):
- No `equals_*` / `hashCode_*` tests. Identity equality is the v1 contract. If a future caller needs content-equality, that's a follow-up SPI evolution with explicit field-by-field decisions (especially `timestamp`).

---

## 2. Integration tests — `MutableRootAuditIntegrationTest`

Lives in `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/MutableRootAuditIntegrationTest.java`.

`@RunWith(Parameterized.class)` over `NodeStoreFixture` — minimum **SEGMENT_TAR** + **DOCUMENT_NS**. Pattern follows existing parameterized tests like `oak-jcr/.../AbstractRepositoryTest.java`. `DOCUMENT_NS` requires `nsfixtures=DOCUMENT_NS` or local Mongo — use `@Assume(MongoUtils.isAvailable())` pattern from `oak-store-document` tests so the CI matrix can skip cleanly.

Each test registers a recording `AuditEventListener` and enables the toggle in `@Before`. Tests use the real `UserManagerImpl.onGroupUpdate` call site to exercise capture (per grace's skeleton — capture was moved here from `MembershipProvider.addMember` so single-variant add/remove has path-resolved members; bulk variant is deferred for v1). Events carry only the structural fields (`groupPath`, `memberPath` for the single variant); the acting user is read from `commitInfo.getUserId()` inside the listener, NOT from the event payload.

- `MutableRootAuditIntegrationTest.successPath_singleSaveDispatchesEvents` — `group.addMember(user)`; `session.save()`; recording listener received exactly 1 `MemberAddedEvent` with the right `groupPath` and `memberPath`. Listener's captured `commitInfo.getUserId()` equals `"admin"`. Listener's recorded `Thread.currentThread()` equals the calling thread.
- `MutableRootAuditIntegrationTest.successPath_multipleEventsInOneSave_dispatchedAsBurst` — 5 `addMember` calls; one `save()`; listener invoked **once** with a list of 5 events in capture order.
- `MutableRootAuditIntegrationTest.successPath_twoSequentialSaves_twoBursts` — `addMember`, `save()`, `addMember`, `save()`; listener invoked twice; each burst has 1 event.
- `MutableRootAuditIntegrationTest.commitFailurePath_constraintViolation_bufferCleared` — induce `CommitFailedException` via **real permission denial**: a non-admin session attempts `group.addMember(user)` on a group it doesn't have `REP_USER_MANAGEMENT` privilege on. The denial throws from `PermissionValidatorProvider` (`oak-core/.../security/authorization/AuthorizationConfigurationImpl.java:168-173`), wrapped in the validator `EditorHook` at `MutableRoot.java:303` — pinned **after** `SnapshotAuditBufferHook`. After the throw, register a listener and do a clean `save()` for an unrelated change → listener receives **no** stale events.
- `MutableRootAuditIntegrationTest.commitFailurePath_validatorThrowsAfterCapture_noListenerInvocation` — same trigger as above (permission denial → validator throw). Confirms the order: capture happens, snapshot hook runs (pinned earlier in chain), validator `EditorHook` throws (pinned line 303 — after snapshot, before postValidationHooks), dispatch hook (PostValidationHook, pinned line 305) never runs. Listener is **not** invoked. Buffer cleared by `MutableRoot.commit`'s catch → `AuditBufferLifecycle.onCommitFailed`. Next save: no stale events. (Per alex: `PermissionValidatorProvider` is in `AuthorizationConfigurationImpl.getValidators()`, so this is deterministic without test-only `SecurityConfiguration`.)
- `MutableRootAuditIntegrationTest.refreshPath_capturedThenRefreshed_noDispatch` — `addMember(...)`; `root.refresh()` (or `session.refresh(false)`); `addMember(...)` again; `save()`; listener receives ONLY the second event (refresh discarded the first capture).
- `MutableRootAuditIntegrationTest.rebasePath_capturedThenRebased_noDispatch` — same shape via `root.rebase()`. (At JCR level, rebase is internal — exercise via `Root` API directly through `ContentSession`.) Listener receives only post-rebase events.
- `MutableRootAuditIntegrationTest.crossSessionIsolationOnSameThread_noCrossTalk` — open `ContentSession s1` and `s2` on the same thread (sequential, not concurrent). Capture event on `s1`. Call `s2.save()` without any capture on it — listener receives **0** events from the `s2` save. Then `s1.save()` — listener receives the event from `s1` only.
- `MutableRootAuditIntegrationTest.threadLocalContextPreserved_mdcVisibleInListener` — pre-populate SLF4J MDC (`MDC.put("trace.id","abc-123")`) on the calling thread. Listener reads `MDC.get("trace.id")` inside `onCommit` and asserts equality. Validates the "dispatch on the merge thread" guarantee end-to-end.
- `MutableRootAuditIntegrationTest.threadLocalContextPreserved_documentNS_acrossMergeThread` — DOCUMENT_NS specifically: `DocumentNodeStore.merge` may use an internal merge mutex; verify the listener still sees MDC because dispatch is synchronous on the caller's thread (already verified in design brief at `DocumentNodeStore.java:1143`). Fixture-specific assertion catches regressions if the DocumentNodeStore commit path ever moves to a worker thread.
- `MutableRootAuditIntegrationTest.systemRootBehavior_systemUserOperationsEmitEvents` — open a `SystemRoot` (via `Oak.getContentRepository()`'s internal acquisition path), perform a membership change; listener receives an event whose `commitInfo.getUserId()` equals exactly `CommitInfo.OAK_UNKNOWN` (literal `"oak:unknown"`). Confirmed by alex: `SystemSubject` carries `SystemPrincipal` (not `SystemUserPrincipal`), so `AuthInfoImpl.createFromSubject` (`oak-security-spi/.../AuthInfoImpl.java:54-59`) returns `userID=null`; `CommitInfo`'s constructor normalizes `null → OAK_UNKNOWN` (`oak-store-spi/.../CommitInfo.java:94`). Assertion: `assertEquals(CommitInfo.OAK_UNKNOWN, capturedCommitInfo.getUserId())` — exact, deterministic.
- `MutableRootAuditIntegrationTest.toggleOff_noCapture_noDispatch` — toggle off in `@Before`; `addMember` + `save`; listener invoked **0** times. Validates the disabled-feature zero-side-effect contract.
- `MutableRootAuditIntegrationTest.listenerRegistrationAfterCapture_doesNotSeeStaleEvents` — capture an event (toggle on, but no listener registered yet — verifies the "no listener → short-circuit" path); register listener; `save()`; listener receives **0** events (capture short-circuited at record time before storage).
  - **Tension flag:** this conflicts with the TOCTOU row in the design brief. Confirmed with design — late listener does NOT see in-flight captures. Test pins the documented behavior.
- `MutableRootAuditIntegrationTest.bulkPath_addMembers_singleBulkEventNotNSingletons` — exercises the bulk capture site (`UserManagerImpl.recordBulkMembershipAuditEvent` / `onGroupUpdate` bulk variant). `group.addMembers("u1","u2","u3","u4","u5")`; `save()`; listener receives **exactly 1** `MembersAddedBulkEvent` whose `memberIds` is `{u1,u2,u3,u4,u5}` in insertion order, NOT 5 `MemberAddedEvent`s. Pins the bulk-vs-singleton dispatch invariant grace flagged.
- `MutableRootAuditIntegrationTest.bulkPath_removeMembers_singleBulkEventNotNSingletons` — same shape for `MembersRemovedBulkEvent` via `group.removeMembers(...)`.
- `MutableRootAuditIntegrationTest.bulkPath_allFailedIds_noEventEmitted` — call the bulk add path with member IDs that all fail validation (e.g., 5 IDs that don't resolve to any authorizable). When `memberIds.isEmpty()` post-filter, `recordBulkMembershipAuditEvent` short-circuits (grace's capture-site shortcut). Listener receives **0** events. Edge case but worth pinning since it could regress silently if the shortcut is removed. Note: this is the only case where `failedIds` is dropped; partial-failure events carry both sets.
- `MutableRootAuditIntegrationTest.bulkPath_partialFailures_eventCarriesBothSets` — bulk add of 5 IDs, 2 fail. Listener receives **1** `MembersAddedBulkEvent` whose `getMemberIds()` is the 3 successful ones, AND `getFailedIds()` is the 2 failing ones. Pins team-lead's reversal — the event carries the full picture (both sets), so audit consumers can answer "we tried 5, succeeded on 3, here are the 2 that failed." Also assert payload-map shape: `payload.get(PAYLOAD_MEMBER_IDS) instanceof List`, `payload.get(PAYLOAD_FAILED_IDS) instanceof List`.
- `MutableRootAuditIntegrationTest.bulkPath_allSucceeded_failedIdsIsEmptySet` — bulk add of 5 IDs, all succeed. Listener receives **1** event with `getMemberIds()` = 5 ids, `getFailedIds() == Set.of()` (empty but non-null). Pins the common-case shape.
- `MutableRootAuditIntegrationTest.bulkPath_isContentIdFlagSetCorrectly` — invoke the bulk path; verify the emitted event's `isContentId()` matches whether the upstream call passed content-IDs or authorizable-IDs (grace's `isContentId` payload field). Renamed from `contentIds` per alex's micro-nit aligning with `UserManagerImpl.onGroupUpdate(... boolean isContentId, ...)`.
- `MutableRootAuditIntegrationTest.bulkPath_failedIdsEmptyDoesNotMeanValidatorsPassed` — pin the failure-layer-scope contract grace and alex called out. Setup: bulk `group.addMembers("u1","u2")` where both IDs succeed at the MembershipWriter (so `failedIds == Set.of()` would be the natural shape) BUT a downstream validator throws `CommitFailedException` (e.g., permission denial on the group node itself, triggered via a non-admin session). Expected: listener is **NOT invoked** at all (validator failure → commit fails → buffer cleared → no dispatch). Negative-space assertion confirms the documentation: a consumer must not interpret `failedIds.isEmpty()` as "validators passed" — they observe validator-pass-or-fail via the dispatch path itself. This test would catch a future regression where a refactor moves dispatch ahead of the validator chain (it would then emit a stale event from a failed commit).

**Cross-fixture matrix:**
| Test | SEGMENT_TAR | DOCUMENT_NS | Comment |
|---|---|---|---|
| successPath_* | required | required | core flow |
| commitFailurePath_* | required | required | DocumentNodeStore retries differ |
| refreshPath_* | required | required | rebase semantics differ on Document |
| crossSessionIsolation | required | required | session lifecycle |
| threadLocalContextPreserved_mdcVisible | required | required | dispatch-thread invariant |
| threadLocalContextPreserved_documentNS_acrossMergeThread | — | required | fixture-specific |
| systemRootBehavior | required | required | system commit path |
| toggleOff | required | — (skip; toggle is store-agnostic) | fast |
| bulkPath_* | required | required | bulk capture site, both happy and failure cases |

---

## 3. Failure-mode tests — `StaleEventPreventionTest`

Lives in `oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/StaleEventPreventionTest.java`.
Targets the **Path α vs Path β** distinction. These are the tests that justify Path α's correctness over Path β (the "buffer-only, no MutableRoot wiring" alternative discussed in design discovery).

- `StaleEventPreventionTest.globalHookThrowsBeforeSnapshot_bufferCleared` — register a throwing `CommitHook` via `Oak.with(throwingHook)` (the **global hook slot**, fed at `MutableRoot.java:285` — pinned **before** all security regular hooks including `SnapshotAuditBufferHook`). Per alex: do NOT use a peer `SecurityConfiguration` for this — HashSet iteration order makes "before vs after Snapshot" flaky; the global slot is positionally guaranteed. Steps: build `Oak.with(throwingHook).with(securityProvider)...`, enable audit toggle, register listener; capture an event via `AuditEvents.record(...)`; call `session.save()` → throws `CommitFailedException(OAK, 999, "test-induced")`; assert `AuditBuffer.take(sessionId)` returns `null` after the throw (cleared by `MutableRoot.commit`'s `catch (Throwable t)` → `AuditBufferLifecycle.onCommitFailed`); next save on the same session: listener receives **no** stale events. **This is the smoking-gun test for Path α — it must pass deterministically.**
- `StaleEventPreventionTest.validatorThrowsAfterSnapshot_noListenerInvocation` — trigger via real permission denial (validator throws from inside `EditorHook` at the pinned position `MutableRoot.java:303` — after Snapshot, before Dispatch). Re-asserted here in isolation from §2: buffer was cleared by snapshot hook (pointer moved to `CommitContext`), `EditorHook` throws → `MutableRoot.commit` catches → `AuditBufferLifecycle.onCommitFailed` is invoked but is a no-op (buffer already empty for that session). On next merge attempt, `ResetCommitAttributeHook` clears `CommitContext` → no stale dispatch. Validate listener invocation count is 0 across both the failed and a subsequent clean save.
- `StaleEventPreventionTest.refreshAfterFailedSave_clearsBuffer` — capture events; trigger a failed save (induce via permission denial on a different node); `root.refresh()`; new captures; clean save → listener receives **only** the post-refresh captures. Belt-and-suspenders: even if step 1's catch path had a bug, refresh's explicit clear would still scrub stale state.
- `StaleEventPreventionTest.repeatedFailedSave_noEventAccumulation` — capture 1 event; failed save; capture 1 event; failed save; capture 1 event; successful save. Listener receives **exactly 1** event. Validates each failed save scrubs the buffer (no creeping accumulation).
- `StaleEventPreventionTest.documentNS_branchMergeRetry_exactlyOnceDispatch_eventsNotLost` — DOCUMENT_NS-specific. Force a retryable merge conflict (write contention via two concurrent sessions, second retries). Capture 1 event; the snapshot hook runs twice for the same `CommitInfo` (once per attempt). On the successful attempt, listener must be invoked **exactly once** with the event. This is now a strict correctness improvement over the design brief's documented "events lost on retry" residual gap — grace's non-destructive Snapshot + `finally`-drain in Dispatch closes that gap. Test asserts: (a) the listener is invoked exactly once, (b) the listener receives the originally-captured event (not lost between attempts), (c) `buffer.peek(sessionId)` is `null` after the successful merge (Dispatch's `finally` drained). Not `@Ignore`d — runs in CI on the Mongo profile.
- `StaleEventPreventionTest.commitContextResetBetweenAttempts_dispatchOnlyOnSuccessfulMerge` — verify that if a merge attempt rolls back (transient conflict, NodeStore-internal retry), the next attempt starts with a fresh `CommitContext` (already guaranteed by `ResetCommitAttributeHook`). Listener invoked once per *successful* merge, not once per attempt.
- `StaleEventPreventionTest.sessionLogoutWithPendingCaptures_bufferReclaimed` — capture event, do NOT save, call `session.logout()` / `ContentSession.close()`. Open a new session on the same thread (reuses the thread, ergo the same `ThreadLocal`). Save unrelated work → listener receives **0** events. Validates we don't leak pending captures across session lifecycles. **Implementation note:** the buffer is keyed by sessionId, so a new session has a different key; this test pins that behavior.

---

## 4. Benchmark plan — `AuditOverheadBenchmark`

Lives in `oak-benchmarks/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditOverheadBenchmark.java`.
Extends `AbstractTest` (the `oak-benchmarks` test base, see `AddMembersTest` for the pattern). Registered in `BenchmarkRunner`. Runnable via:

```bash
mvn -pl oak-benchmarks -DskipTests=false exec:java \
    -Dexec.mainClass="org.apache.jackrabbit.oak.benchmark.BenchmarkRunner" \
    -Dexec.args="AuditOverheadBenchmark Oak-Tar Oak-Mongo"
```

### 4.1 Modes (CLI flags / system properties)
- `audit.mode=disabled` — feature toggle off. The hot path includes the single volatile read inside `AuditEvents.isEnabled()` and nothing else.
- `audit.mode=enabled-no-listener` — toggle on, `AuditConfiguration` deployed, **no listener** registered on the whiteboard. Hot path: toggle check + live `Tracker.getServices()` returns `Collections.emptyList()` + `isEmpty()` short-circuit + return. Hooks installed but short-circuit.
- `audit.mode=enabled-with-listener` — toggle on, `AuditConfiguration` deployed, 1 `RecordingAuditEventListener` registered. Workload generates 100 events per `save()` burst.

### 4.2 Workload — membership churn
- Pre-populate 1 group + 100 users (the standard `AddMembersTest` setup).
- Per iteration: call `group.addMember(userN)` × `batchSize` then `session.save()`. Repeat to fill the measurement window.
- Parameters:
  - `numberOfMembers = 1000` (total adds per iteration)
  - `batchSize = 100` (saves per iteration ≈ 10; events per save = 100)
  - `iterations = 10` warm-up + `50` measurement (Oak's `AbstractTest` defaults)

### 4.3 Fixtures
- **Oak-Tar** (SEGMENT_TAR) — primary baseline; on-disk, single JVM.
- **Oak-Mongo** (DOCUMENT_NS) — clustered store; merge thread is the calling thread (verified in brief).
- Skip remote segment fixtures (AWS/Azure) for v1 — their overhead is dominated by network I/O, audit dispatch noise is below the floor.

### 4.4 Metrics

For each `<mode, fixture>` combination, report:
- p50, p95, p99 latency per `save()` (ms)
- throughput (saves/sec)
- allocations per save (`oak-benchmarks` `-PallocAnalysis` / JFR-based; if not wired, manual GC-counter delta)
- GC pause time during the measurement window

### 4.5 Thresholds (pre-merge gates)

Computed as **regression vs. the `audit.mode=disabled` baseline on the same fixture/run**, NOT a fixed absolute number — relative overhead is what matters.

| Mode | Threshold (p50 regression) | Threshold (p99 regression) |
|---|---|---|
| `disabled` | **< 1%** | **< 2%** |
| `enabled-no-listener` | **< 2%** | **< 4%** |
| `enabled-with-listener` (100 events/save) | **< 5%** | **< 10%** |

If any mode crosses its p50 budget, the merge is blocked until grace + ada agree it's intrinsic (e.g., listener I/O the user opted into).

### 4.6 Methodology

1. **JVM args:** `-server -Xms2g -Xmx2g -XX:+UseG1GC` (matches Oak's standard benchmark setup; pinned to remove GC-target variance).
2. **CPU pinning:** single-thread workload pinned to one core via `taskset -c 2` (Linux) / OS-equivalent on macOS. Documented in the benchmark JavaDoc.
3. **Warm-up:** 10 iterations discarded.
4. **Measurement:** 50 iterations, report aggregate.
5. **GC between modes:** explicit `System.gc()` + 500ms quiesce; reset per-thread allocation counters.
6. **Repetitions:** run each `<mode, fixture>` combo 3 times in different orders to detect order-dependent caching artifacts. Report standard deviation; flag any combo whose stddev exceeds 15% of the mean for re-runs.
7. **Listener implementation in `enabled-with-listener`:** `RecordingAuditEventListener` writes to an `ArrayList` (no I/O, no logging). This isolates dispatcher overhead from listener-side cost.
8. **Snapshot capture:** results committed to `oak-benchmarks/results/audit-overhead/<date>-<commit>.csv` with the JIRA ID once assigned.

### 4.7 What the benchmark **must** prove

- **Path α's per-commit overhead is bounded** when feature toggle is off (validates the design's "0 ns" claim within measurement precision).
- **No GC spike** in `enabled-with-listener` mode beyond what the listener's own allocations explain — proves the dispatcher itself is allocation-light.
- **DOCUMENT_NS vs SEGMENT_TAR**: relative overhead is the same (proves NodeStore-agnosticism — required for v1).

---

## 5. Pre-merge gate commands

Run all of the following from the repo root. Each step is a hard gate.

```bash
# Step 1 — SPI module: unit tests + coverage
mvn -pl oak-security-spi verify -Pcoverage -Dskip.coverage=false
# Gate: BUILD SUCCESS. JaCoCo report at oak-security-spi/target/site/jacoco/index.html
# must show 100% line + branch coverage for org/apache/jackrabbit/oak/spi/security/audit/**

# Step 2 — Core module: unit tests + coverage
mvn -pl oak-core verify -Pcoverage -Dskip.coverage=false
# Gate: BUILD SUCCESS. JaCoCo report:
#   - 100% for org/apache/jackrabbit/oak/security/audit/**
#   - existing MutableRoot.java coverage not regressed (compare against trunk)

# Step 3 — Cross-module rebuild (we touched oak-security-spi)
mvn clean install -pl oak-security-spi -amd -DskipTests
# Gate: all downstream modules still compile (OSGi baseline check included).

# Step 4 — Integration tests, SEGMENT_TAR (default)
mvn -pl oak-core test -Dtest='MutableRootAuditIntegrationTest,StaleEventPreventionTest'
# Gate: all tests green.

# Step 5 — Integration tests, DOCUMENT_NS (requires local MongoDB on :27017)
mvn -pl oak-core test -Dtest='MutableRootAuditIntegrationTest,StaleEventPreventionTest' \
    -Dnsfixtures=DOCUMENT_NS
# Gate: all tests green. Skip if MongoDB unavailable (CI must run this in the Mongo profile).

# Step 6 — RAT / license headers
mvn -pl oak-security-spi,oak-core apache-rat:check
# Gate: no missing license headers.

# Step 7 — Benchmark sanity run (full suite is too long for CI; this is a smoke)
mvn -pl oak-benchmarks compile
java -cp oak-benchmarks/target/classes:... \
     org.apache.jackrabbit.oak.benchmark.BenchmarkRunner \
     AuditOverheadBenchmark Oak-Tar --warmup 2 --iterations 5 --runtime 5
# Gate: benchmark completes without error, p99 within ballpark. Full perf gate is human-reviewed.

# Step 8 — oak-jcr integration smoke (audit must not regress JCR-level tests)
mvn -pl oak-jcr test -Dtest='*GroupTest,*MembershipTest'
# Gate: all green.

# Step 9 — Full module CI (final gate)
mvn -pl oak-security-spi,oak-core,oak-jcr,oak-benchmarks clean verify
# Gate: BUILD SUCCESS.
```

**Required commit:**
- Coverage HTML reports (or CSV summary) attached to the PR description, showing 100% on the audit packages.
- Benchmark CSV from §4.5 attached, with p50/p99 deltas vs the `disabled` baseline for each fixture.

---

## 6. Open testability questions for the team

### 6.1 Resolved

**From alex:**
1. **Deterministic `CommitFailedException` triggers** — confirmed by alex with source citations:
   - **"Before Snapshot" (global slot)** — use `Oak.with(throwingHook)`. The global hook is fed at `MutableRoot.java:285`, pinned **before** all security regular hooks regardless of `HashSet<SecurityConfiguration>` iteration order. Used in `StaleEventPreventionTest.globalHookThrowsBeforeSnapshot_bufferCleared`. Do NOT use a peer `SecurityConfiguration` for this case — HashSet bucketing makes it intermittent.
   - **"After Snapshot, before Dispatch" (validator slot)** — use real permission denial. `PermissionValidatorProvider` is in `AuthorizationConfigurationImpl.getValidators()` (`oak-core/.../security/authorization/AuthorizationConfigurationImpl.java:168-173`); validators are wrapped in a single `EditorHook` pinned at `MutableRoot.java:303` — after all regular hooks (including Snapshot), before all PostValidationHooks (Dispatch). Used in `commitFailurePath_*` and `validatorThrowsAfterSnapshot_noListenerInvocation`.
   - **"After Dispatch"** — alex flagged this slot's ordering is also `HashSet`-based (within `postValidationHooks`). Out of scope; not in the test plan.
2. **`SystemRoot` userId** — confirmed by alex: assertion is exactly `assertEquals(CommitInfo.OAK_UNKNOWN, capturedCommitInfo.getUserId())`. Trace: `SystemSubject` → `SystemPrincipal` (not `SystemUserPrincipal`) → `AuthInfoImpl.createFromSubject` returns `null` → `CommitInfo` constructor normalizes `null → OAK_UNKNOWN` at `oak-store-spi/.../CommitInfo.java:94`. Constant literal: `"oak:unknown"`.

**From grace:**
3. **`AuditEvents` reset between tests** — `AuditEvents.install(null)` is the documented reset mechanism. Static façade with single `volatile Sink` field; `install(Sink)` swaps; `install(null)` restores internal `NOOP`. No separate `@VisibleForTesting reset()` needed. Tests use `install(null)` in `@After`. Option (b) (Whiteboard-resolved instance) rejected — would defeat the single-volatile-read fast-path promise.
4. **`AuditBuffer` testability accessor** — grace added package-private `boolean isAllocatedOnCurrentThread()` returning `tl.get() != null`. Cheap, no allocation. Tests use this in §1.1 `lazyAllocation_*_doesNotAllocateMap`.
5. **`AuditEvent.capturedThreadId`** — **NOT** present in v1. Listeners that care about the capture thread call `Thread.currentThread().getId()` inside `onCommit` themselves. Adding it to the event would be wasted state on the no-listener fast path. Test `record_capturedThreadIdRecordedOnEvent` **dropped** from §1.5.
6. **`SnapshotAuditBufferHook` is non-destructive** (grace's improvement over the design brief, alex-validated): uses `buffer.peek(sessionId)`, leaves `ThreadLocal` untouched. `DispatchAuditEventsHook` is the sole authority that calls `buffer.drain(sessionId)`, in a `finally` block. This **closes the "retry-loses-events" residual gap** flagged in the design brief. §1.3, §1.4, §3 all updated to pin the new semantics.
7. **`DispatchAuditEventsHook` constructor signature** — `(Feature, AuditBuffer, WhiteboardAuditEventListenerRegistry)`. Tests construct directly with a real `AuditBuffer` (not a mock) so the `finally`-drain contract is asserted end-to-end.
8. **Event payload trim** — `MemberAddedEvent`/`MemberRemovedEvent` no longer carry `performedBy`. Listeners read `commitInfo.getUserId()`. §2 success-path assertion updated accordingly.
9. **Capture-site move** — capture moved from `MembershipProvider.addMember` to `UserManagerImpl.onGroupUpdate` (single variant). §2 description updated.

**From grace (round 4):**
10. **Bulk SPI events added — in scope for v1.** `MembersAddedBulkEvent` and `MembersRemovedBulkEvent` join the SPI alongside the singleton variants. Factory: `of(groupPath, memberIds, contentIds, failedIds)`. **Typed accessors return `Set<String>`** (`getMemberIds`, `getFailedIds`, both immutable). **Payload map stores `List<String>`** (serializer-friendly per team-lead's reversal — `Set` doesn't survive JSON/XML round-trips cleanly). Insertion order preserved in both `Set` view and `List` payload. `memberIds` must be non-empty (factory IAE); `failedIds` may be empty. Capture-site shortcut `UserManagerImpl.recordBulkMembershipAuditEvent` skips emit only when `memberIds.isEmpty()` post-filter. New tests: §1.7 (`MembersAddedBulkEventTest` + `MembersRemovedBulkEventTest`, 18 + 18 = 36 unit tests); §2 (`bulkPath_*`, 6 integration tests × 2 fixtures). Updated §8 "What is NOT in scope": bulk-variant capture is now **in** scope.
11. **Deactivate ordering pinned** — 4-step sequence: `featureToggle.close()` → `registry.stop()` → NOOP installs on both static façades → `buffer.clearAll()`. Any reordering is a race regression (a concurrent capture could write to the buffer between toggle-close and clearAll if order were inverted). New `AuditBuffer.clearAll()` method covered by §1.1 (`clearAll_calledOnCurrentThread_purgesCurrentThreadMap`, `clearAll_doesNotTouchOtherThreads_documentedBoundedLeak`). The bounded cross-thread leak is **documented**, not a defect — other threads' stale state is flushed by their next refresh / failed-commit / thread death. §1.6 has the new `deactivate_orderIsToggleCloseRegistryStopNoopInstallBufferClearAll` `InOrder` assertion.
12. **`NoOpAuditConfiguration` (BlobAccessProvider-style)** — `InternalSecurityProvider.auditConfiguration` is initialized to `AuditConfiguration.NOOP` (never null). Tests do not check for null around `getConfiguration(AuditConfiguration.class)`; they assert `== AuditConfiguration.NOOP` (identity) or behavior (empty hooks/validators). New tests in §1.6: `NOOP_returnsEmptyCommitHooks`, `NOOP_returnsEmptyValidators`, `NOOP_isNotNull`.

**From grace (round 5):**
13. **Bulk event payload includes `failedIds` (team-lead's reversal).** Factory takes both `memberIds` and `failedIds`. Accessors return `Set<String>`, payload stores `List<String>`. `memberIds` non-empty (factory IAE); `failedIds` may be empty. Capture-site shortcut still skips emit only when `memberIds.isEmpty()` post-filter. **Failure-layer scope (grace + alex, Javadoc round):** `failedIds` is the MembershipWriter (staging-layer) partial-failure subset only. Validator rejections are NOT captured in `failedIds` — they roll back the whole commit and discard the event entirely. Consumers must not interpret `failedIds.isEmpty()` as "validators passed"; commit success is observed via the dispatch path itself (PostValidationHook position). Pinned by §2 `bulkPath_failedIdsEmptyDoesNotMeanValidatorsPassed`. Tests in §1.7 + §2 updated.
14. **`setAuditConfiguration(null)` test is in `InternalSecurityProviderTest`, not `AuditConfigurationTest`.** Internal wiring concern (`InternalSecurityProvider` is package-private in `oak.security.internal`; the setter is `public` only for `SecurityProviderBuilder.build()` to call). Moved to §1.8 — 3 tests covering set-null/set-non-null/default. `AuditConfigurationTest` no longer reaches into the wiring container.
15. **No `equals`/`hashCode` override on bulk events in v1.** Identity equality. Rationale: (a) `SecurityAuditEvent.timestamp` makes any content-equality choice surprising; (b) Set-accessor vs List-payload ambiguity makes "order-sensitive or not?" a design decision we can defer; (c) no production caller needs event content-equality (listeners process, don't dedupe). Dropped 2 tests from §1.7 (`equality_sameContent_areEqual`, `equality_differentMemberIdOrder_areNotEqual`). Replaced with `payload_memberIdsPreservesInsertionOrder` / `payload_failedIdsPreservesInsertionOrder` — semantic tests on the contract that actually matters (serialization order), not a hash-table contract.
16. **`contentIds` → `isContentId` rename (alex's micro-nit, applied).** Accessor `isContentId()`, field `isContentId`, payload key constant `PAYLOAD_IS_CONTENT_ID = "isContentId"`. Aligns with `UserManagerImpl.onGroupUpdate(... boolean isContentId, ...)`. Semantic unchanged. All test names referencing `contentIds` renamed in §1.7 and §2.

### 6.2 Pending

1. **ada:** for `StaleEventPreventionTest.documentNS_branchMergeRetry_exactlyOnceDispatch_eventsNotLost`, what is the cleanest way to trigger a deterministic retry on DOCUMENT_NS in a test (vs. flakiness)? Possibly need a custom `CommitHook` that throws `MERGE` failure once then succeeds — analogous to existing `DocumentNodeStoreTest` patterns.
2. **shannon:** to confirm whether `RecordingAuditEventListener` belongs in `oak-security-spi/src/test` (shared test fixture) or in `oak-core/src/test` (private to impl tests). Prefer the former so it's reusable from `oak-benchmarks` and future security configurations' tests without copy-paste.

---

## 7. Test count & coverage summary

| Bucket | Count | Coverage target |
|---|---|---|
| `AuditBufferTest` | 19 unit tests | 100% (lines + branches) |
| `WhiteboardAuditEventListenerRegistryTest` | 14 unit tests | 100% |
| `SnapshotAuditBufferHookTest` | 9 unit tests | 100% |
| `DispatchAuditEventsHookTest` | 15 unit tests | 100% |
| `AuditEventsTest` | 11 unit tests | 100% (SPI façade) |
| `AuditConfigurationTest` | 18 unit tests | 100% |
| `InternalSecurityProviderTest` (audit additions) | 3 unit tests | covers wiring container |
| `MembersAddedBulkEventTest` | 16 unit tests | 100% (SPI value type) |
| `MembersRemovedBulkEventTest` | 16 unit tests | 100% (SPI value type, mirrors above) |
| `MutableRootAuditIntegrationTest` | 20 tests × 2 fixtures (SEG, DOC) = up to 40 runs | end-to-end |
| `StaleEventPreventionTest` | 7 tests, mostly multi-fixture | failure-mode |
| `AuditOverheadBenchmark` | 3 modes × 2 fixtures = 6 runs | perf gate |

**Total unit tests:** 121 (round 5 net delta: dropped 2 equality tests per class in §1.7 (−4 total), moved 1 wiring test from §1.6 to §1.8 with 3 tests replacing it (net +2). Note: `contentIds → isContentId` is a rename only, no count change).
**Total integration test runs:** ~47 (added 1 failure-layer-scope defensive test × 2 fixtures, grace's Javadoc-round suggestion: `bulkPath_failedIdsEmptyDoesNotMeanValidatorsPassed`).
**Total perf measurements:** 6 + 3 repetitions = 18.

Coverage commitment: **100% on `oak-security-spi/.../audit/**` and `oak-core/.../security/audit/**`** at merge time. **>80%** on the 3 lines added to `MutableRoot.java` (those lines are explicitly covered by `MutableRootAuditIntegrationTest.refreshPath_*` and `commitFailurePath_*`).

---

## 8. Tests we are **not** writing (and why)

- **No JUnit 5 tests.** Per `AGENTS.md` and Oak convention.
- **No tests for `BackgroundAuditEventListener` / async dispatch.** Out of scope per design brief §"What is NOT in scope for v1". Add when the async wrapper ships.
- **No tests for capture sites beyond `UserManagerImpl.onGroupUpdate` (single AND bulk variants — both in scope for v1 per grace's round 4).** Other capture sites (USER_CREATED, ACL_*, TOKEN_*, CUG_*) come in v2 with their own tests. We test the **mechanism** end-to-end via singleton + bulk `onGroupUpdate`; once mechanism is solid, additional capture sites are mechanically trivial.
- **No tests for validator-side capture.** Explicitly rejected in design brief.
- **No tests for cross-cluster external-change audit events.** Out of scope.
- **No tests that weaken or remove existing assertions.** Per `AGENTS.md` "do not weaken or remove existing test assertions to make the build pass" — anything that fails after the change is investigated, never silenced.
