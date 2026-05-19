# Audit-SPI plans — reviewer guide

This directory holds the planning artifacts for the cross-stack audit-event SPI work shipping in PR #1.

## What to read

If you're reviewing the PR, read in this order:

1. [`08-cross-stack-audit-design.md`](./08-cross-stack-audit-design.md) — **the spec.** Architecture, SPI surface, both pipelines (commit-attached + fire-and-forget), end-to-end sequence diagram, trust model. Supersedes the earlier Path α design.
2. [`09-cross-stack-impl-plan.md`](./09-cross-stack-impl-plan.md) — the 11-chunk implementation plan that was executed in Phase 1.
3. [`perf/README.md`](./perf/README.md) — performance characterization: macro slice (audit-OFF + audit-ON) and two purpose-built microbenchmarks for per-commit / per-event audit overhead.

The PR body itself lists the structure of Phase 1 (implementation) + Phase 2 (fresh team review + remediation) + Phase 3 (perf), with verification output.

## What's in `history/`

The Path-α-era design cycle that preceded the current spec — kept for traceability, not required reading. Includes the original architecture (`01-architecture.md`), trunk-citation validation at design time (`02-oak-validation.md`), the Path α Java skeleton + diffs (`03-skeleton/`), the test plan (`04-tests-and-benchmarks.md`), Whiteboard-ranking research (`05-research-notes.md`), the original scope-and-flow doc (`06-scope-and-flow.md`), the bridge design that 08 explicitly supersedes (`07-bridge-design.md`), the Session→Root widening survey (`07-session-to-root-survey.md`), the consolidated Path α plan (`99-final-plan.md`), and rendered diagrams (`diagrams/`).

`08` and `09` keep one-way pointers into `history/` where they reference Path α material (the migration section, the skeleton sources the implementation chunks ported from, etc.). The internal cross-references *within* `history/` are unchanged.
