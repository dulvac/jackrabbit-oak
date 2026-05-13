<!-- gg:convention=jira-ticket -->
<!-- gg:base-branch=trunk -->
<!-- gg:jira-project=OAK -->

# Apache Jackrabbit Oak — Development Conventions

These conventions complement `AGENTS.md`. The authoritative project guide is `AGENTS.md`; this file consolidates branch / commit / code-style conventions in one place for quick agent and contributor reference.

## Issue Tracking

Apache Jackrabbit Oak uses Apache JIRA, project key **OAK**:

- Browse: `https://issues.apache.org/jira/browse/OAK-<number>`
- Every change references a JIRA issue. If a contributor has no ticket, ask them to file one (or for explicit confirmation that the change does not need one — rare).

## Branch Naming

All work happens on branches; never commit directly to `trunk`.

| Branch type | Pattern                            | Example                              |
|-------------|------------------------------------|--------------------------------------|
| Issue branch | `issue/OAK-<number>`              | `issue/OAK-12345`                    |
| Issue branch with description | `issue/OAK-<number>-<short-desc>` | `issue/OAK-12345-fix-token-revocation` |

Use lowercase, hyphen-separated descriptions. Keep them short but descriptive.

If no JIRA issue exists yet, ask the user for the issue number (or for confirmation that an issue is being filed).

## Branching Model

- The default branch is `trunk`. All pull requests target `trunk`.
- Create issue branches from the latest `trunk`:
  ```bash
  git checkout trunk
  git pull origin trunk
  git checkout -b issue/OAK-<number>
  ```
- Push and open a PR when ready:
  ```bash
  git push -u origin issue/OAK-<number>
  gh pr create --base trunk --repo apache/jackrabbit-oak
  ```
- **PR title:** start with the JIRA key, e.g., `OAK-12345: Description of change`.
- **PR body:** summarize the change and link the JIRA ticket. The CI commit-message check enforces the JIRA-key prefix on commits.

## Commit Conventions

Every commit message must start with the JIRA issue key:

```
OAK-XXXXX: Description of change
```

Examples:
- `OAK-12345: add CUG principal change detection`
- `OAK-12346: fix NPE in TokenProviderImpl when token expires during login`
- `OAK-11224: remove guava dependencies from oak-blob-cloud`

Multi-line commits use a blank line and a body:

```
OAK-12345: short summary

Longer explanation if needed. Wrap at ~72 characters.
Reference related tickets as OAK-XXXXX inline.
```

The PR commit-message check rejects commits without an `OAK-XXXXX` prefix.

## Java Code Style

(Authoritative source: `AGENTS.md` "Code Conventions". Highlights:)

- **Java 11 baseline** — no records, sealed types, or pattern matching for switch
- **No wildcard imports** — import each class individually
- **Apache 2.0 license header** required on every Java file (Apache RAT plugin enforces this in CI). See `AGENTS.md` for the canonical block.
- **OSGi Declarative Services** — `@Component`, `@Activate`, `@Deactivate`, `@Reference`
- **OSGi Metatype** — `@ObjectClassDefinition`, `@AttributeDefinition`, `@Designate` for configuration
- **Annotations** — `@NotNull` / `@Nullable` from `org.jetbrains.annotations`
- **Logging** — SLF4J (`org.slf4j.Logger`); never `System.out`, `System.err`, or `e.printStackTrace()`
- **Try-with-resources** for `ContentSession`, `Session`, `ResourceResolver` — no leaks
- **Never use regex / find-replace for JSON or XML** (per `AGENTS.md` "Code Conventions")

## Module-Layering Discipline

- **API** (`oak-api`, `oak-jackrabbit-api`) — public surface for repository consumers
- **SPI** (`oak-*-spi`) — extension points for plugins (NodeStore, Query, Security)
- **Default implementations** — `oak-core` and store/index modules (Document, Segment, Lucene, etc.)
- **Tools** — `oak-run`, `oak-upgrade`

Dependency direction: API ← SPI ← Implementation ← Tools. SPI must NOT depend on implementation modules.

## Testing Conventions

- **JUnit 4 (4.13.x)** — Oak's standard. Do NOT introduce JUnit 5 unless explicitly requested.
- **Mockito 5** loaded as Java agent. Some modules use **EasyMock** — check existing test style before adding new tests in a module.
- **Test naming** — descriptive method names, e.g., `testTokenExpiredBeforeRefresh`, `testCommitConflictOnConcurrentUpdate`.
- **Multi-fixture tests** — use `NodeStoreFixture` / `OakBaseTest` parameterization where the change is fixture-sensitive (NodeStore impl, indexing, blob).
- **Coverage rules:**
  - General code: **>80%** coverage
  - Security modules (`oak-security-spi`, `oak-auth-*`, `oak-authorization-*`): **100%** coverage
- **Never weaken or remove existing test assertions** to make a build pass. Investigate root cause first.
- **Integration tests** — opt-in via `mvn verify -PintegrationTesting`; required for some Document and Segment scenarios.
- **MongoDB on `localhost:27017`** required for `DOCUMENT_NS` fixture tests.

## OSGi Bundle Conventions

- Bundle metadata produced by `bnd-maven-plugin` (configured in `oak-parent`).
- Public packages explicitly listed via `Export-Package` — keep minimal; never export `*.internal.*`.
- Compile-only annotations excluded from `Import-Package` (DS annotations, Metatype annotations).
- OSGi baseline plugin runs in CI — fails on accidental breaking API changes.

## Feature Toggles

For non-trivial behavior changes, use a feature toggle. Naming: `FT_OAK-<issue>` or `FT_<DESCRIPTION>_OAK-<issue>` (e.g., `FT_OAK-11949`, `FT_CLASSIC_MOVE_OAK-10147`).

- **Bug fix:** toggle defaults ON.
- **New feature:** toggle defaults OFF.

API: `Feature.newFeature(name, whiteboard)` to create; `feature.isEnabled()` to check; `feature.close()` to clean up. See `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/spi/toggle/Feature.java`.

## Code Review Guidelines

<!-- gg:review-guidelines=true -->

### Review Focus Areas
- **Correctness** — Does the change preserve Oak's MVCC semantics? Are commit hooks idempotent? Are validators using the right `CommitFailedException` codes?
- **Module discipline** — Does the change live in the correct module? Are SPI dependencies one-way?
- **Security** — For security modules, is coverage 100%? Are sensitive properties (`rep:password`, `rep:credentials`, tokens) protected?
- **Test coverage** — Changed code has appropriate tests; fixture-sensitive changes cover the relevant fixtures.
- **OSGi** — Component lifecycle is complete; service references typed; configuration bound via Metatype; `Export-Package` is intentional.
- **License header** — Every new Java file has the Apache 2.0 header.
- **Java 11** — No records, sealed types, or pattern matching for switch.
- **Feature toggle** — Non-trivial behavior changes use a toggle (default off for features, default on for fixes).

### Review Principles
- Only flag issues that matter. No nitpicking style preferences.
- Suggest fixes; don't just point out problems.
- Acknowledge genuinely clever solutions.
- Cite `file:line` references.

## Core Principles — Priority Order

When conflicts arise between principles, prioritize in this order:

1. **Correctness** — Oak is a content repository underpinning many systems. Data corruption or permission-evaluation bugs are unacceptable.
2. **Security** — `oak-security-spi` and the auth/authorization modules are first-class concerns. 100% test coverage is non-negotiable there.
3. **Stability** — Backwards-compatible API surface; OSGi baseline plugin is a hard gate.
4. **Performance** — Commit / query / observer hot paths matter to large deployments. Use `oak-benchmarks*` to validate hot-path changes.
5. **Readability** — Oak has thousands of contributors; clear, conventional code beats clever code.
