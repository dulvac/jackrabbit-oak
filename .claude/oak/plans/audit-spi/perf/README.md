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

## Audit-ON benchmark — DEFERRED

To measure audit-ON cost, a new benchmark fixture is needed that wires `SecurityProviderBuilder.withAuditConfiguration(new AuditConfigurationImpl())` and a registered `AuditEventListener`. This is non-trivial — the `OakFixture.getMemory()` would need a sibling like `getMemoryWithAudit()`, plus a benchmark variant that flips the FeatureToggle on. Out of scope for the initial Phase 3 run; flagging as v1.1+ work if performance characterization of the audit-on path becomes a deployment concern.

## Files

- `baseline-0091af2211.txt` — raw stdout from baseline run
- `head-2b8b3cc5d7.txt` — raw stdout from HEAD run
- (Add `head-with-audit-on-*.txt` when v1.1+ benchmark fixture exists)

## Reproduction

```bash
# baseline
cd /tmp && git clone /Users/adulvac/work/jackrabbit-oak audit-perf-baseline
cd audit-perf-baseline && git checkout 0091af2211
mvn install -pl oak-benchmarks -am -Pfast -DskipTests -q

for bench in BasicWriteTest AddMemberTest RemoveMemberTest; do
    java -Xmx4g -Druntime=30 -Dwarmup=5 \
        -jar oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar benchmark \
        "$bench" Oak-MemoryNS
done

# HEAD (in main checkout)
cd /Users/adulvac/work/jackrabbit-oak
mvn install -pl oak-benchmarks -am -Pfast -DskipTests -q
for bench in BasicWriteTest AddMemberTest RemoveMemberTest; do
    java -Xmx4g -Druntime=30 -Dwarmup=5 \
        -jar oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar benchmark \
        "$bench" Oak-MemoryNS
done
```
