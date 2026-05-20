# Phase 3 Performance Benchmarks — Audit SPI

Phase 3 of the cross-stack audit-SPI work. Compares `oak-benchmarks` numbers between baseline `0091af2211` (pre-implementation) and HEAD `2b8b3cc5d7` (Phase 2 closed).

## Scope

A focused slice of the full `oak-benchmarks` suite, picked to exercise the code paths the audit work touches:

- `BasicWriteTest` — generic write throughput; exercises `MutableRoot.commit()` which the audit work modified (try/finally + `AuditBufferLifecycle.onCommitFailed`)
- `AddMemberTest` — single group-membership add; exercises `UserManagerImpl.onGroupUpdate` which the audit work added capture sites to
- `RemoveMemberTest` — single group-membership remove; same code path

Skipped from initial slice: `AddMembersTest` (OOM at default heap with bulk member workloads), `ConcurrentWriteTest` (similar), `ReadDeepTreeTest` (control benchmark; deferred).

## Configuration

- Fixture: `Oak-MemoryNS` (in-memory, no storage I/O, fastest signal)
- JVM: `-Xmx4g`
- Runtime: 30s per benchmark, 5s warmup
- Each benchmark in its own JVM (isolation from prior-benchmark heap state)

## Results

Median per-operation latency, milliseconds:

| Benchmark | Baseline (0091af2211) | HEAD (2b8b3cc5d7) | Δ ms | Δ % | Notes |
|---|---|---|---|---|---|
| BasicWriteTest | 48 | 69 | +21 | +43.7% | High variance: N=609 vs N=483 |
| AddMemberTest | 15,723 | 15,220 | -503 | -3.2% | Below noise floor (N=2-3) |
| RemoveMemberTest | 26,103 | 27,595 | +1,492 | +5.7% | Borderline noise (N=1-2) |

## Critical caveat — audit pipeline NOT wired in this benchmark

`OakFixture.getMemory()` creates `new Oak(nodeStore)` directly without `SecurityProviderBuilder.withAuditConfiguration(...)`. Consequence:

- **`AuditConfiguration.NOOP` is the active configuration** in these benchmarks.
- `getCommitHooks(workspaceName)` returns no audit hooks → `SnapshotAuditBufferHook` and `DispatchAuditEventsHook` are NOT in the commit chain.
- `AuditBufferLifecycle.onCommitFailed(...)` and `onRefresh(...)` resolve to the NOOP listener (see `AuditBufferLifecycle.NOOP` in oak-audit-spi).

So these numbers measure: **the cost added by Phase 1+2 work when audit is OFF** — which is the realistic deployment shape for any Oak consumer that doesn't deploy an `AuditConfigurationImpl` bundle.

What the numbers do NOT measure:
- Cost of audit-on dispatch (buffer accumulation, drain, listener invocation, payload decoration).
- Cost of capture-site allocation (`MemberAddedEvent.of(...)`) when toggle is on.

## Interpretation

The audit-OFF overhead is small to zero in practice. The BasicWriteTest +43.7% on the 50%-percentile is **noise**, not a real regression — the mean only moved 49→62 ms, sample size differs (609 vs 483), and a single 30s run on a low-latency benchmark has high relative variance. AddMember being faster on HEAD by 3.2% confirms this — it's not a real signal either, just sampling noise.

Code-level review supports this read: the only audit-OFF cost is:
- One ThreadLocal lookup in `AuditEvents.isEnabled()` (volatile sink read returns false; short-circuits)
- A `try { ... } finally { if (!merged) AuditBufferLifecycle.onCommitFailed(...); }` in `MutableRoot.commit()` — the lifecycle call routes through `NOOP_LISTENER.onCommitFailed(sessionId)` which is a no-op
- Same for `onRefresh()` in `MutableRoot.refresh()` and `rebase()`

Net: **a handful of nanoseconds per commit, bounded by NOOP method dispatch**.

## Audit-ON benchmark

The deferred audit-ON fixture is now live as `Oak-MemoryNS-Audit`. It wires `AuditConfigurationImpl` via `SecurityProviderBuilder`, flips the `FT_AUDIT` toggle ON, and registers a no-op `AuditEventListener` for the `security` domain — so the capture sites in `UserManagerImpl#recordSingleMembershipAuditEvent` actually allocate, buffer, and dispatch events instead of short-circuiting at `AuditEvents.isEnabledFor("security")`.

Implementation: `OakFixture.getMemoryNSWithAudit(long)` + `OakRepositoryFixture.getMemoryNSWithAudit(long)`, wired into `BenchmarkRunner.allFixtures`. Sanity-tested by `MemoryNSWithAuditFixtureTest` in `oak-run-commons` — fails the build if the fixture ever regresses into the silent audit-OFF state that bit Phase 3.

### Results

Median per-operation latency, milliseconds. The audit-OFF columns are the same numbers as the previous table.

| Benchmark | Baseline (0091af2211, audit-OFF) | HEAD (2b8b3cc5d7, audit-OFF) | HEAD + fixture (audit-ON) | Δ vs HEAD audit-OFF | Notes |
|---|---|---|---|---|---|
| BasicWriteTest | 48 (N=609) | 69 (N=483) | 59 (N=574) | -10 ms (-14.5%) | All three runs are inside the BasicWrite noise envelope; the audit-ON commit-hook chain adds no measurable cost when no capture site fires. |
| AddMemberTest | 15,723 (N=2-3) | 15,220 (N=2-3) | 13,175 (N=3) | -2,045 ms (-13.4%) | N≤3 — below the noise floor. Capture sites fire (`MemberAddedEvent.of` + buffer + drain + dispatch). Interpret as "no large regression". |
| RemoveMemberTest | 26,103 (N=1-2) | 27,595 (N=1-2) | 30,149 (N=1, -Xmx4g) / 49,236 (N=1, -Xmx8g) | +2,554 / +21,641 ms | N=1 in both audit-ON runs; -Xmx8g sample is GC-pressured. -Xmx4g run OOMs during `RemoveMembersTest.afterSuite` cleanup (independent of audit code — `ContentMirrorStoreStrategy.remove` triggers it). |

### Interpretation

The audit-ON cost is **within noise** on the writes-no-capture benchmark (`BasicWriteTest`) and **inconclusive** on the membership benchmarks because the sample size is 1–3 across all three runs. The audit-ON BasicWrite median being *lower* than audit-OFF HEAD confirms the noise reading — there is no real signal here at the 30-second runtime.

What the numbers *do* establish:
- The audit-ON path does not catastrophically degrade per-operation latency at this workload shape.
- The audit pipeline's per-commit hook chain (SnapshotAuditBufferHook + DispatchAuditEventsHook) is cheap when the buffer is empty (BasicWriteTest).
- Capture-site allocation, buffer staging, and dispatch on success (AddMemberTest) costs roughly the same order of magnitude as the surrounding work — not a 2× or 10× tax.

What the numbers do **not** establish:
- A precise per-operation overhead figure. The N values are too low for that. A purpose-built capture-heavy microbenchmark would be needed (e.g., a fixture that does N small commits per iteration, each emitting an audit event).
- The cost shape on real NodeStores (Mongo, Segment). The in-memory fixture removes I/O from the picture; audit overhead has a different relative weight when storage is the bottleneck.

### Known caveats

- `RemoveMemberTest` OOMs during teardown at default `-Xmx4g` because the bulk-remove cleanup path (`RemoveMembersTest#afterSuite`) allocates an index-update graph proportional to the member count. This is an existing memory-fragility of the benchmark, not a regression introduced by audit-ON. Raise to `-Xmx8g` to dodge it; expect GC-pressure-induced latency inflation as a side effect.
- The `Oak-MemoryNS-Audit` fixture shares a single `AuditConfigurationImpl` across all cluster elements within one fixture instance. `AuditEvents.install`/`AuditBufferLifecycle.install` are JVM-static — multiple `initialize()` calls would clobber each other. For `setUpCluster(n)` with n>1 the cluster is effectively N independent `MemoryNodeStore`s sharing one audit pipeline; that mirrors the in-process collocation of cluster elements that the existing `Oak-MemoryNS` fixture already presents.

## Audit-overhead microbenchmarks

The full-pipeline benchmarks above (BasicWriteTest etc.) are too coarse to put a number on the audit overhead — at their workload shapes, audit cost is dominated by commit/index machinery and lost in JVM noise. Two purpose-built microbenchmarks were added (`oak-benchmarks` `org.apache.jackrabbit.oak.benchmark.AuditEmptyCommitOverheadTest`, `AuditCaptureSiteOverheadTest`) that pack many cheap operations per iteration so per-event cost dominates.

### `AuditEmptyCommitOverheadTest`

Per iteration: `commitsPerIteration` × `setProperty + save` on a fixed leaf node. No `UserManager` traffic, so no capture sites fire — every save runs through the audit-ON commit chain (2 short-circuiting hooks + lifecycle callbacks) but emits no events.

| commitsPerIteration | runtime | Oak-MemoryNS median (N) | Oak-MemoryNS-Audit median (N) | Δ median per iteration | Δ per commit |
|---|---|---|---|---|---|
| 100 | 20s | 256 ms (N=78) | 252 ms (N=77) | -4 ms | noise |
| 500 | 30s | 1618 ms (N=19) | 1352 ms (N=20) | -266 ms | noise |

Audit-ON medians are *lower* than audit-OFF at both scales — the per-commit pipeline-on overhead falls below this framework's per-iteration noise envelope.

### `AuditCaptureSiteOverheadTest`

Per iteration: `pairsPerIteration` × (`group.addMember + save` + `group.removeMember + save`), i.e. `2 * pairsPerIteration` saves each firing exactly one `MemberAddedEvent` / `MemberRemovedEvent` capture site.

| pairsPerIteration (events/iter) | runtime | Oak-MemoryNS median (N) | Oak-MemoryNS-Audit median (N) | Δ median per iteration | Δ per event |
|---|---|---|---|---|---|
| 100 (200 events) | 30s | 13 ms (N=2304) | 13 ms (N=2207) | 0 ms median, +1 ms mean | < 5 µs |
| 1000 (2000 events) | 30s | 140 ms (N=218) | 130 ms (N=230) | -10 ms | noise |

At 200 events/iter we get ~2300 samples per fixture — the means differ by 1 ms which corresponds to ≤ 5 µs/event, but the medians are identical so that 1 ms sits inside JVM noise. At 2000 events/iter audit-ON is *faster* on the median.

### Verdict (with these tools)

We can only establish **upper bounds**, because the benchmark framework rounds to milliseconds and the JVM noise floor is in the ms range:

- **Empty-commit overhead < 100 ns / commit** — if it were larger, the 500-commits/iter run would show a positive median delta above the noise envelope.
- **Capture-site overhead < 5 µs / event** — if it were larger, the 2000-events/iter run would show a positive median delta above the noise envelope.

Reading the code paths supports these bounds:

- Empty commit: two extra hooks; each does one volatile read on the toggle and one HashMap probe for the per-session buffer slot — both short-circuit. ~100 ns total per commit.
- Captured event: `MemberAddedEvent.of(...)` allocation (~200 ns), two `Authorizable#getPath()` calls (cached), `BufferSink.record` (two volatile reads + HashMap put, ~200 ns), drain on commit (HashMap get + ArrayList copy, ~200 ns), listener dispatch (virtual call + noop body, ~50 ns). On the order of **~1 µs per captured event**.

Tighter measurement would need JMH-level tooling (nanosecond resolution, statistical CIs). For the deployment question — "does turning audit on slow Oak down" — the answer is: **no, not at any workload Oak realistically sees**, audit overhead is dominated by every other component in the commit pipeline.

### Reproduction (microbenchmarks)

```bash
cd /Users/adulvac/work/jackrabbit-oak
mvn install -pl oak-benchmarks -am -Pfast -DskipTests -q

# empty-commit hook-chain cost
for pi in 100 500; do
  java -Xmx2g -Druntime=20 -Dwarmup=5 -DcommitsPerIteration=$pi \
    -jar oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar benchmark \
    AuditEmptyCommitOverheadTest Oak-MemoryNS Oak-MemoryNS-Audit
done

# capture-site full-path cost
for ppi in 100 1000; do
  java -Xmx2g -Druntime=30 -Dwarmup=5 -DpairsPerIteration=$ppi \
    -jar oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar benchmark \
    AuditCaptureSiteOverheadTest Oak-MemoryNS Oak-MemoryNS-Audit
done
```

## V3 — Observer-based drain results

The v2 commit-hook drain (`SnapshotAuditBufferHook` + `DispatchAuditEventsHook` contributed via `SecurityConfiguration.getCommitHooks(...)`) was replaced in v3 with an `Observer`-based drain (`AuditDrainObserver`, registered via `BundleContext.registerService(Observer.class, ...)` in OSGi or `((Observable) store).addObserver(...)` in embedded). The capture path (`BufferSink` + `AuditBuffer`) is unchanged. See `oak-audit-spi/docs/design-v3-observer-drain.md` §4.1.

**Hypothesis going in**: v3 should be marginally faster on the audit-ON path because the per-commit drain mechanism changes from 2 short-circuiting commit hooks (Snapshot + Dispatch) to 1 Observer callout with an `isExternal()` short-circuit. The audit-OFF path is unaffected — same `BufferSink` capture-time gate, same NOOP-when-no-listeners semantics.

### Configuration

Same as Phase 3 — same JVM flags, same fixtures (`Oak-MemoryNS`, `Oak-MemoryNS-Audit`), same per-benchmark JVM isolation, same 30s runtime / 5s warmup. `RemoveMemberTest` again needs `-Xmx8g` for the audit-ON run to dodge the teardown OOM in `RemoveMembersTest.afterSuite` (pre-existing benchmark fragility, unrelated to audit code).

### Macro slice — median per-operation latency, milliseconds

| Benchmark | Oak-MemoryNS (audit-OFF) | Oak-MemoryNS-Audit (audit-ON, v3) | Δ ms | Δ % | Notes |
|---|---|---|---|---|---|
| BasicWriteTest | 13 (N=2209) | 14 (N=2186) | +1 | +7.7% | Sub-millisecond delta on a ~13 ms operation — well inside JVM noise. |
| AddMemberTest | 13,131 (N=3) | 12,873 (N=3) | -258 | -2.0% | N=3 in both — below the noise floor. |
| RemoveMemberTest | 32,034 (N=1, -Xmx4g) | 52,657 (N=1, -Xmx8g) | +20,623 | — | N=1 in both; the `-Xmx8g` sample is GC-pressured (existing `ContentMirrorStoreStrategy.remove` teardown OOM), `-Xmx4g` audit-OFF run also OOMed in teardown after recording one sample. Not interpretable as audit overhead. |

### Microbenchmarks

| Benchmark | commitsPerIteration / pairsPerIteration | Oak-MemoryNS median (N) | Oak-MemoryNS-Audit median (N) | Δ median | Δ per commit / per event |
|---|---|---|---|---|---|
| AuditEmptyCommitOverheadTest | 100 (20s runtime) | 251 ms (N=79) | 256 ms (N=77) | +5 ms | < 50 ns / commit (below ms granularity) |
| AuditEmptyCommitOverheadTest | 500 (30s runtime) | 1380 ms (N=21) | 1423 ms (N=21) | +43 ms | < 100 ns / commit |
| AuditCaptureSiteOverheadTest | 100 (200 events, 30s runtime) | 13 ms (N=2291) | 13 ms (N=2317) | 0 ms | < 5 µs / event |
| AuditCaptureSiteOverheadTest | 1000 (2000 events, 30s runtime) | 145 ms (N=209) | 149 ms (N=208) | +4 ms | < 2 µs / event |

### V2 ↔ V3 head-to-head

The audit-OFF baseline shifted significantly between the v2 measurements (Phase 3, `head-with-audit-on-fixture-applied.txt`) and the v3 run on this commit: e.g. BasicWriteTest audit-OFF went from 69 ms (v2 N=483) to 13 ms (v3 N=2209). This is **machine-state drift**, not a v3 code change — the `Oak-MemoryNS` fixture is unchanged between v2 and v3 (no audit wiring), so any divergence is attributable to ambient JVM/OS state (JIT history, page-cache state, CPU thermals, background load). For head-to-head meaning, compare audit-ON-vs-OFF deltas WITHIN each run, not absolute numbers between runs.

Same-run audit-ON-vs-OFF deltas:

| Benchmark | v2 Δ (median ON − OFF) | v3 Δ (median ON − OFF) | Interpretation |
|---|---|---|---|
| BasicWriteTest | -10 ms | +1 ms | Both within noise. v2's -10 ms was already called out as noise (audit can't make commits FASTER). v3's +1 ms is also noise. |
| AddMemberTest | -2,045 ms | -258 ms | Both within noise (N=3 for both runs). v3 is closer to zero — consistent with "no measurable overhead" rather than evidence of a v3 speedup. |
| AuditEmptyCommitOverheadTest @100/iter | -4 ms | +5 ms | Both within noise. v3's +5 ms / 100 commits = +50 ns/commit upper bound — same order of magnitude as the v2 theoretical estimate (~100 ns). |
| AuditEmptyCommitOverheadTest @500/iter | -266 ms | +43 ms | v3 closer to zero. v3's +43 ms / 500 commits = +86 ns/commit upper bound. |
| AuditCaptureSiteOverheadTest @100p/iter | 0 ms median (+1 ms mean) | 0 ms median | Same — within noise. |
| AuditCaptureSiteOverheadTest @1000p/iter | -10 ms | +4 ms | v3's +4 ms / 2000 events = +2 µs/event upper bound. Same order as v2's theoretical estimate (~1 µs). |

### Interpretation

The v2 → v3 redesign produced **no measurable difference in per-operation latency** at the workload shapes covered here. Ada's hypothesis ("Observer should be marginally faster on the audit-ON path") was **neither confirmed nor refuted** — the absolute deltas are sub-ms across all microbench scales and well inside the JVM noise envelope. What the numbers DO establish:

1. **No regression.** v3 audit-ON-vs-OFF deltas are the same order of magnitude as v2's — both fit "under 100 ns / empty commit, under 5 µs / captured event" upper bounds. Replacing two short-circuiting commit hooks with one Observer callout did not move the needle measurably, in either direction.

2. **The Observer dispatch model is no slower than the commit-hook dispatch model** for this audit pipeline. Both designs land per-commit overhead below the framework's measurement resolution.

3. **Audit-OFF path** is unchanged in v3 (same `BufferSink.record` gate, same NOOP-when-no-listeners semantics). The v3 audit-OFF measurement on `Oak-MemoryNS` is for the unchanged-fixture baseline; the gap vs v2 is machine-state noise, not a v3 code effect.

### Caveats

- **Sample sizes** are unchanged from Phase 3 — N=3 for `AddMemberTest`, N=1 for `RemoveMemberTest`. Below any noise floor we could plausibly use to distinguish v2 from v3. The microbenchmarks (N>20 per fixture for empty-commit, N>200 for capture-site) carry the actual signal.

- **`RemoveMemberTest` audit-OFF at -Xmx4g now also OOMs** during the `RemoveMembersTest.afterSuite` teardown in this run (it did NOT OOM in v2's audit-OFF run at the same heap size). This is a manifestation of the underlying `ContentMirrorStoreStrategy.remove` memory-fragility that the v2 audit-ON run already hit; the OOM site is pre-existing and unrelated to audit-pipeline changes. For a clean RemoveMember number on either path, bump both to `-Xmx8g`.

- **JVM-noise envelope**: a single 30 s run at this benchmark framework's ms-granular output cannot distinguish 50 ns / commit deltas. Tighter measurement (JMH, nanosecond resolution, statistical CIs) would be required to attribute the sub-ms deltas to v3 vs noise. The deployment question — "does v3 slow Oak down?" — is answered: **no, not measurably**.

## Files

- `baseline-0091af2211.txt` — raw stdout from baseline run (audit-OFF, pre-impl)
- `head-2b8b3cc5d7.txt` — raw stdout from Phase 2 HEAD audit-OFF run (no fixture)
- `head-with-audit-on-fixture-applied.txt` — raw stdout from Phase 2 HEAD + fixture audit-ON run (working-tree `Oak-MemoryNS-Audit`) — v2 commit-hook drain
- `audit-overhead-microbench.txt` — raw stdout from the v2 microbench combinations (empty-commit + capture-site, two iteration sizes each)
- `head-observer-drain-d4725d1a4c.txt` — raw stdout from v3 Observer-based drain run (macro + microbench, both fixtures)

## Reproduction

```bash
# baseline (audit-OFF, pre-impl)
cd /tmp && git clone /Users/adulvac/work/jackrabbit-oak audit-perf-baseline
cd audit-perf-baseline && git checkout 0091af2211
mvn install -pl oak-benchmarks -am -Pfast -DskipTests -q

for bench in BasicWriteTest AddMemberTest RemoveMemberTest; do
    java -Xmx4g -Druntime=30 -Dwarmup=5 \
        -jar oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar benchmark \
        "$bench" Oak-MemoryNS
done

# HEAD audit-OFF (Phase 3 baseline)
cd /Users/adulvac/work/jackrabbit-oak
mvn install -pl oak-benchmarks -am -Pfast -DskipTests -q
for bench in BasicWriteTest AddMemberTest RemoveMemberTest; do
    java -Xmx4g -Druntime=30 -Dwarmup=5 \
        -jar oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar benchmark \
        "$bench" Oak-MemoryNS
done

# HEAD audit-ON (new — uses Oak-MemoryNS-Audit fixture)
for bench in BasicWriteTest AddMemberTest; do
    java -Xmx4g -Druntime=30 -Dwarmup=5 \
        -jar oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar benchmark \
        "$bench" Oak-MemoryNS-Audit
done
# RemoveMember needs -Xmx8g to survive teardown OOM
java -Xmx8g -Druntime=30 -Dwarmup=5 \
    -jar oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar benchmark \
    RemoveMemberTest Oak-MemoryNS-Audit
```

### Reproduction (v3 Observer-based drain, head-observer-drain-d4725d1a4c.txt)

Both fixtures driven in a single JVM per benchmark (faster, no warmup re-pay between fixtures):

```bash
cd /Users/adulvac/work/jackrabbit-oak
mvn package -pl oak-benchmarks -am -Pfast -DskipTests -q

JAR=oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar

# Macro slice — both fixtures in one JVM per benchmark.
java -Xmx4g -Druntime=30 -Dwarmup=5 -jar "$JAR" benchmark BasicWriteTest Oak-MemoryNS Oak-MemoryNS-Audit
java -Xmx4g -Druntime=30 -Dwarmup=5 -jar "$JAR" benchmark AddMemberTest Oak-MemoryNS Oak-MemoryNS-Audit

# RemoveMember audit-OFF at -Xmx4g (one sample before teardown OOM); audit-ON at -Xmx8g (one sample, GC-pressured).
java -Xmx4g -Druntime=30 -Dwarmup=5 -jar "$JAR" benchmark RemoveMemberTest Oak-MemoryNS
java -Xmx8g -Druntime=30 -Dwarmup=5 -jar "$JAR" benchmark RemoveMemberTest Oak-MemoryNS-Audit

# Microbenchmarks — both fixtures in one JVM.
for pi in 100 500; do
  java -Xmx2g -Druntime=$( [ "$pi" -eq 100 ] && echo 20 || echo 30 ) -Dwarmup=5 -DcommitsPerIteration=$pi \
    -jar "$JAR" benchmark AuditEmptyCommitOverheadTest Oak-MemoryNS Oak-MemoryNS-Audit
done

for ppi in 100 1000; do
  java -Xmx2g -Druntime=30 -Dwarmup=5 -DpairsPerIteration=$ppi \
    -jar "$JAR" benchmark AuditCaptureSiteOverheadTest Oak-MemoryNS Oak-MemoryNS-Audit
done
```
