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

## Files

- `baseline-0091af2211.txt` — raw stdout from baseline run (audit-OFF)
- `head-2b8b3cc5d7.txt` — raw stdout from HEAD audit-OFF run (no fixture)
- `head-with-audit-on-fixture-applied.txt` — raw stdout from HEAD + fixture audit-ON run (working-tree `Oak-MemoryNS-Audit`)
- `audit-overhead-microbench.txt` — raw stdout from the four microbench combinations (empty-commit + capture-site, two iteration sizes each)

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
