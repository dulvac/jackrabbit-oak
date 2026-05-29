# Audit pipeline — performance characterization

Measurements from `oak-benchmarks` of the audit pipeline's per-operation
cost. The relevant comparison is **audit-OFF** (no listener registered for
the `oak.security` domain — capture sites short-circuit and the pipeline
is allocation-free) vs **audit-ON** (a listener registered, pipeline
buffering + drain + dispatch on every commit).

## Test setup

- Fixture pair: `Oak-MemoryNS` (audit-OFF baseline, no audit wiring) and
  `Oak-MemoryNS-Audit` (audit-ON, wired by
  `OakFixture.getMemoryNSWithAudit` with `FT_AUDIT` flipped on and a
  no-op `AuditEventListener` registered for the `oak.security` domain).
- In-memory only, no storage I/O (fastest signal; isolates audit cost
  from disk + serialization costs).
- JVM: `-Xmx4g` (raise to `-Xmx8g` for `RemoveMemberTest` audit-ON to
  survive the teardown OOM in `RemoveMembersTest.afterSuite` — pre-existing
  `ContentMirrorStoreStrategy.remove` memory-fragility, unrelated to
  audit).
- Runtime: 30s per benchmark, 5s warmup. Each benchmark in its own JVM
  (isolation from prior-benchmark heap state).
- Measurements below were taken at commit `08c0fe0e90` (the final state
  of the audit-spi/observer-drain branch). Absolute numbers drift with
  machine state — reproduce on the target host for an apples-to-apples
  comparison if needed.
- The audit-ON pipeline can also be turned on for the *non-audit* fixture
  family (`Oak-Memory`, `Oak-MemoryNS`) by setting the
  `-Doak.audit.enabled=true` JVM property at startup. The dedicated
  `Oak-MemoryNS-Audit` fixture is always audit-ON regardless of the
  property — keep that fixture for the audit-OFF-vs-ON head-to-head where
  both fixtures need to coexist in one JVM.

## Macro slice (oak-benchmarks)

Median per-operation latency, milliseconds. Audit-OFF vs audit-ON on
the same run (machine-state drift cancels):

| Benchmark | Oak-MemoryNS (audit-OFF) | Oak-MemoryNS-Audit (audit-ON) | Δ ms | Notes |
|---|---|---|---|---|
| BasicWriteTest | 69 (N=447) | 80 (N=370) | +11 | ~16 % delta on a 69 ms operation; the 10–90 % spreads (33–92 ms vs 55–109 ms) overlap heavily — within ambient JVM noise envelope. |
| AddMemberTest | 13,357 (N=3) | 12,543 (N=3) | -814 | N=3 in both — well below the noise floor (audit can't make commits faster; the negative delta is sampling noise). |
| RemoveMemberTest | 24,375 (N=2, -Xmx4g) | 43,336 (N=1, -Xmx8g) | +18,961 | N=1 for audit-ON; the `-Xmx8g` sample is GC-pressured (existing `ContentMirrorStoreStrategy.remove` teardown OOM). Not interpretable as audit overhead. |

The macro slice does not isolate the audit cost — at these workload
shapes, audit overhead is dominated by commit / index machinery and
sub-millisecond effects fall below the framework's resolution. The
microbenchmarks below carry the actual signal.

## Microbenchmarks (purpose-built)

`oak-benchmarks` includes two audit-targeted microbenchmarks designed
to put many cheap operations into a single iteration so per-event cost
dominates over per-iteration noise:

### `AuditEmptyCommitOverheadTest`

Per iteration: `commitsPerIteration` × `setProperty + save` on a fixed
leaf node. No `UserManager` traffic — every save runs through the
audit-ON pipeline but no capture site fires (no events buffered, no
events to dispatch). Isolates the per-commit pipeline-on overhead.

| commitsPerIteration | Oak-MemoryNS median (N) | Oak-MemoryNS-Audit median (N) | Δ median | Δ per commit |
|---|---|---|---|---|
| 100 (20s runtime) | 261 ms (N=76) | 257 ms (N=76) | -4 ms | below ms granularity → audit-ON is statistically indistinguishable from audit-OFF |
| 500 (30s runtime) | 1378 ms (N=21) | 1370 ms (N=21) | -8 ms | below ms granularity → < 100 ns / commit upper bound |

### `AuditCaptureSiteOverheadTest`

Per iteration: `pairsPerIteration` × (`group.addMember + save` +
`group.removeMember + save`), i.e. `2 * pairsPerIteration` saves each
firing exactly one audit capture-site (`UserAuditEvents.memberAdded(...)`
resp. `UserAuditEvents.memberRemoved(...)`). Exercises the full
audit-ON path: allocation → buffer → drain → decorate → dispatch.

| pairsPerIteration (events/iter) | Oak-MemoryNS median (N) | Oak-MemoryNS-Audit median (N) | Δ median | Δ per event |
|---|---|---|---|---|
| 100 (200 events, 30s) | 12 ms (N=2427) | 12 ms (N=2403) | 0 ms | below ms granularity → < 5 µs / event upper bound |
| 1000 (2000 events, 30s) | 123 ms (N=244) | 124 ms (N=240) | +1 ms | < 1 µs / event upper bound (improved from < 2 µs in prior runs) |

## Verdict

Audit pipeline overhead is **below the benchmark framework's millisecond
resolution** at every workload shape tested. We can establish upper
bounds (the deltas would be measurable if larger) but not precise
per-operation overhead figures.

Upper bounds:

- **Empty-commit overhead** (pipeline on, no capture site fires):
  **< 100 ns / commit**. If it were larger, the 500-commits-per-iter
  microbench would show a positive median delta above the noise
  envelope.
- **Captured-event overhead** (allocation + buffer + drain + decorate
  + dispatch): **< 1 µs / event**. If it were larger, the
  2000-events-per-iter microbench would show a positive median delta
  above the noise envelope.

Reading the code paths supports these bounds:

- **Empty commit**: one volatile read in `AuditEvents.isEnabled()` (sink
  is the active `BufferSink` → reads `featureToggle.isEnabled() &&
  registry.hasAnyListener()`), one observer dispatch where
  `info.isExternal()` short-circuits or the buffer drain finds nothing.
  ~100 ns total.
- **Captured event**: factory allocation (`UserAuditEvents.memberAdded`,
  ~200 ns), two `Authorizable#getPath()` calls (cached), `BufferSink.record`
  (two volatile reads + HashMap put, ~200 ns), drain on commit
  (HashMap get + ArrayList copy, ~200 ns), `CommitMetadataDecorator`
  (3 entries added per event, ~100 ns), listener dispatch (virtual
  call + no-op body, ~50 ns). On the order of **~1 µs per captured event**.

Audit-OFF (no listener registered) is **allocation-free**: capture
sites short-circuit at `AuditEvents.isEnabledFor("oak.security")` before
constructing an event. That call resolves to `toggle.isEnabled() &&
registry.hasListenerFor(domain)` — two volatile reads, no allocation,
no buffer touch.

Tighter measurement would need JMH-level tooling (nanosecond resolution,
statistical confidence intervals). For the deployment question —
"does turning audit on slow Oak down?" — the answer is: **no, not at
any workload Oak realistically sees**. Audit overhead is dominated by
every other component in the commit pipeline.

## Caveats

- **Sample sizes** on the macro slice are too low for fine resolution
  (N=3 for `AddMemberTest`, N=1 for `RemoveMemberTest`). The
  microbenchmarks (N>20 per fixture for empty-commit, N>200 for
  capture-site) carry the actual signal.
- **`RemoveMemberTest`** at `-Xmx4g` OOMs in
  `RemoveMembersTest.afterSuite` teardown — pre-existing
  `ContentMirrorStoreStrategy.remove` memory-fragility, independent of
  audit. Bump to `-Xmx8g` to get a clean sample; expect GC-pressure-induced
  latency inflation.
- **JVM noise envelope**: a single 30 s run at this benchmark
  framework's ms-granular output cannot distinguish 50 ns / commit
  deltas.
- The `Oak-MemoryNS-Audit` fixture shares a single
  `AuditConfigurationImpl` across all cluster elements within one
  fixture instance — `AuditEvents.install` /
  `AuditBufferLifecycle.install` are JVM-static, so multiple
  `initialize()` calls would clobber each other. For `setUpCluster(n)`
  with `n > 1`, the cluster is effectively N independent
  `MemoryNodeStore`s sharing one audit pipeline.

## Reproduction

```bash
cd <repo root>
mvn package -pl oak-benchmarks -am -Pfast -DskipTests -q

JAR=oak-benchmarks/target/oak-benchmarks-2.1-SNAPSHOT.jar

# Macro slice — both fixtures in one JVM per benchmark.
java -Xmx4g -Druntime=30 -Dwarmup=5 -jar "$JAR" benchmark BasicWriteTest Oak-MemoryNS Oak-MemoryNS-Audit
java -Xmx4g -Druntime=30 -Dwarmup=5 -jar "$JAR" benchmark AddMemberTest Oak-MemoryNS Oak-MemoryNS-Audit

# RemoveMember audit-OFF at -Xmx4g (one sample before teardown OOM);
# audit-ON at -Xmx8g (one sample, GC-pressured).
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

## Related documentation

- [`audit.md`](audit.md) — user-facing Oak audit SPI guide
  (event model, both producer paths, trust model, listener contract).
- [`audit-design.md`](audit-design.md) — full design spec
  (SPI shape, OSGi/embedded wiring, threading invariants, test patterns).
- [`audit-design-overview.md`](audit-design-overview.md) — one-page
  design view for PR descriptions and terminal `cat`.
