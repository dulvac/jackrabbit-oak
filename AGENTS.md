# AGENTS.md - AI Agent Instructions for Apache Jackrabbit Oak

@.claude/PROJECT.md

## Project Overview

Apache Jackrabbit Oak is a scalable, high-performance hierarchical content repository
implementing the JCR (Java Content Repository) specification. It is a multi-module Maven
project with ~47 modules written in Java 11.

## Team Orchestration (MANDATORY for Multi-Agent Work)

**When the user asks the team to work on something (e.g., "have the team review this", "fix these issues", "discuss this approach"), the main agent MUST follow this protocol. Do NOT spawn independent subagents with plain `Agent` calls -- those agents cannot communicate with each other and findings are silently lost.**

### Step-by-Step Orchestration

1. **Create a team session:**
   ```
   TeamCreate(team_name: "oak-<purpose>")
   ```
   Use a descriptive name: `oak-review`, `oak-feature-xyz`, `oak-bugfix-12345`.

2. **Create shared tasks** for each work item:
   ```
   TaskCreate(team_name: "oak-<purpose>", title: "Review NodeStore commit-path change", description: "...", assignee: "ada")
   ```
   Create one task per distinct work item. Assign to the appropriate agent.

3. **Spawn agents into the team** (all in a single message for parallel execution):
   ```
   Agent(
     name: "ada",
     subagent_type: "ada",
     team_name: "oak-<purpose>",
     mode: "bypassPermissions",   // required — see Critical Constraints below
     prompt: "You are joining team 'oak-<purpose>'. Your teammates: sage (security), turing (QA). Do NOT read config.json on startup — defer until you are ready to send your first message (by then all agents have registered). Your task: ... Use SendMessage to share findings with teammates and report to team-lead when done."
   )
   ```
   Always include in the prompt:
   - The team name
   - **A teammate manifest** listing all other agents being spawned (names + roles)
   - Instruction to **defer config.json read** until ready to send messages (avoids registration race condition)
   - Instructions to use `SendMessage` for cross-agent communication
   - The specific task assignment
   - Instructions to report completion to team-lead

4. **Agents communicate** using `SendMessage`:
   - Direct: `SendMessage(to: "sage", message: "Found a permission-evaluation bypass in DocumentNodeStore line 1247")`
   - Broadcast: `SendMessage(to: "*", message: "...")` -- use sparingly
   - Report: `SendMessage(to: "team-lead", message: "Completed architecture review. 3 findings.")`

5. **Agents update tasks** when done:
   ```
   TaskUpdate(task_id: "...", status: "completed", result: "Summary of findings")
   ```

6. **Cleanup** when all work is done: `TeamDelete(team_name: "oak-<purpose>")`

### When to Use Team Sessions vs Plain Agent Calls

| Scenario | Use Team Session? |
|----------|-------------------|
| 2+ agents that need to collaborate or share findings | YES -- TeamCreate |
| User says "team", "agents discuss", "have them review" | YES -- TeamCreate |
| Multi-agent workflow (feature dev, bug fix, PR review, new module) | YES -- TeamCreate |
| Single agent doing independent work | NO -- plain Agent call |
| Quick single-agent research query | NO -- plain Agent call |

### Critical Constraints

- **Always spawn teammates with `mode: "bypassPermissions"`.** The team-lead cannot deliver `permission_response` through `SendMessage` — a child agent that hits a permission prompt hangs indefinitely (observed: 45+ minutes). With `mode: "bypassPermissions"`, the team-lead acts as the policy gate instead of the child.
- **Keep editable AI-config files under `.claude/oak/` and symlink them to `.claude/`.** The `.claude/` directory has hard-coded write protection that survives `mode: "bypassPermissions"` — even explicit `Edit(.claude/**)` allow rules are ignored. Putting the real files in `.claude/oak/` and symlinking makes them editable by team agents. See "Permission Approvals Cannot Be Sent Via SendMessage (and protected paths)" in `.claude/TEAM_WORKFLOW.md`.
- **NEVER use `run_in_background: true`** when dispatching team agents. Background agents' SendMessage calls are silently dropped when the team lead is idle.
- **Dispatch all agents in a single message** as foreground agents. Claude Code runs them in parallel automatically.
- **Include a teammate manifest** in every agent prompt listing all other agents being spawned (names + roles). Agents must NOT read config.json on startup — defer until ready to send messages. See "Teammate Registration Race Condition" in `.claude/TEAM_WORKFLOW.md`.

See `.claude/TEAM_WORKFLOW.md` for detailed workflow patterns, file ownership rules, and agent communication protocols.

## General Guidelines

- When working on a specific area, first read the area-specific documentation before
  making changes (see doc links in the module tables below)
- New code must have >80% test coverage. Security modules (`oak-security-spi`,
  `oak-auth-*`, `oak-authorization-*`) require 100% test coverage
- Do not weaken or remove existing test assertions to make the build pass. If a test
  fails after a change, investigate the root cause. Only modify existing tests when the
  behavioral change is intentional and explicitly requested
- Write self-descriptive, easy-to-read code. Prefer minimal changes — AI tends to add
  more lines than necessary; aim to be concise
- Avoid trivial comments. Only add comments where the logic is complex and not
  obvious from the code itself
- Use feature toggles for non-trivial changes. If the change is a bug fix, the toggle
  should be enabled by default. If the change introduces a new feature, the toggle should
  be disabled by default

## Build Commands

```bash
# Full build (skip tests for speed)
mvn clean install -DskipTests

# Fast build (no tests, no coverage)
mvn clean install -Pfast

# Build a single module (use -DskipTests only for modules you did not change)
mvn clean install -pl oak-core -DskipTests

# Build a module and its dependencies (use -DskipTests only for unchanged dependencies)
mvn clean install -pl oak-core -am -DskipTests

# Rebuild a module and all modules that depend on it (use after SPI/API changes)
mvn clean install -pl oak-store-spi -amd -DskipTests

# Full build with tests
mvn clean install

# Build with integration tests
mvn clean install -PintegrationTesting
```

## Test Commands

```bash
# Run tests for a single module
mvn test -pl oak-core

# Run a specific test class
mvn test -pl oak-core -Dtest=TreeTest

# Run a specific test method
mvn test -pl oak-core -Dtest=TreeTest#testMethodName

# Run integration tests for a module
mvn verify -pl oak-store-document -PintegrationTesting

# Run with code coverage
mvn verify -pl oak-core -Pcoverage -Dskip.coverage=false
```

Always run tests for the modules you modified. Use `-DskipTests` only when building
dependencies that you did not change.

**Test framework:** JUnit 4 (4.13.1) with Mockito 5.x (loaded as Java agent).
Some modules also use EasyMock. Do not introduce JUnit 5 unless explicitly requested.

**Test fixtures:** Tests may run against multiple backend fixtures: `SEGMENT_TAR` (default),
`DOCUMENT_NS` (MongoDB), `DOCUMENT_RDB`, `SEGMENT_AWS`, `SEGMENT_AZURE`.

## Repository Structure

**Note:** Individual submodules may have their own `AGENTS.md` file with module-specific
instructions. When working within a particular module, check for and read its `AGENTS.md`
before making changes.

All modules grouped by area. The **Description** column provides additional context
for understanding each module's role.

### API & Core

| Module | Purpose | Description                                                                                                                                                                                            |
|--------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `oak-api` | Public Oak API | Defines `ContentRepository`, `ContentSession`, `Root`, `Tree` and other core interfaces for accessing repository content. See: `oak-api/README.md`, `oak-doc/src/site/markdown/oak_api/error_codes.md` |
| `oak-core` | Core repository implementation | MVCC-based transactional content model, node state management, commit handling, and internal kernel. See: `oak-doc/src/site/markdown/archigreat l-model.md`                                            |
| `oak-core-spi` | Core SPI interfaces | Service Provider Interfaces for plugins and storage backends (NodeStore, Commit, etc.)                                                                                                                 |
| `oak-commons` | Shared utilities | Common utility classes used across all Oak modules                                                                                                                                                     |
| `oak-jackrabbit-api` | Jackrabbit API extensions | Additional API beyond the JCR spec (e.g., extended session, security). See: `oak-jackrabbit-api/README.md`                                                                                             |
| `oak-jcr` | JCR API binding | Implementation of the javax.jcr (JCR 2.0) specification on top of Oak's content model                                                                                                                  |
| `oak-parent` | Parent POM | Dependency management and shared plugin settings                                                                                                                                                       |

### Node Store (Persistence)

| Module | Purpose | Description |
|--------|---------|-------------|
| `oak-store-spi` | Storage SPI | Interfaces and base classes for implementing custom NodeStore backends. See: `oak-doc/src/site/markdown/nodestore/overview.md` |
| `oak-store-composite` | Composite NodeStore | Combines multiple NodeStores, allowing different subtrees to be backed by different stores. See: `oak-doc/src/site/markdown/nodestore/compositens.md` |
| `oak-store-document` | Document NodeStore | Stores content as documents in MongoDB or relational databases (RDB). Supports clustering, revision management, and branch tracking. See: `oak-doc/src/site/markdown/nodestore/documentmk.md`, `oak-doc/src/site/markdown/nodestore/document/mongo-document-store.md`, `oak-doc/src/site/markdown/nodestore/document/rdb-document-store.md` |
| `oak-segment-tar` | Segment/TarMK storage | Immutable segment-based storage using TAR files. Includes online/offline compaction and generational garbage collection. Default for single-instance deployments. See: `oak-doc/src/site/markdown/nodestore/segment/overview.md`, `oak-doc/src/site/markdown/nodestore/segmentmk.md`, `oak-segment-tar/release-howto.md` |
| `oak-segment-remote` | Remote segment base | Shared base for cloud segment backends (AWS, Azure) |
| `oak-segment-aws` | Segment on AWS S3 | Remote segment store backend storing segments in Amazon S3 |
| `oak-segment-azure` | Segment on Azure Blob | Remote segment store backend storing segments in Microsoft Azure Blob Storage. Includes Azurite test support |

### Blob / Binary Storage

| Module | Purpose | Description |
|--------|---------|-------------|
| `oak-blob` | Blob SPI & base | Core interfaces and base classes for BlobStore (binary large object) implementations. See: `oak-blob/README.md`, `oak-doc/src/site/markdown/plugins/blobstore.md` |
| `oak-blob-cloud` | Cloud blob base | Common functionality for cloud-based BlobStore providers |
| `oak-blob-cloud-azure` | Azure BlobStore | AzureDataStore implementation storing binaries in Azure Blob Storage with direct binary access. See: `oak-doc/src/site/markdown/features/direct-binary-access.md` |
| `oak-blob-plugins` | Blob plugins | Additional BlobStore implementations and cloud integration plugins (S3, etc.) |

### Query & Indexing

| Module | Purpose | Description |
|--------|---------|-------------|
| `oak-query-spi` | Query index SPI | Interfaces for implementing custom query index providers. See: `oak-doc/src/site/markdown/query/query-engine.md` |
| `oak-search` | Search framework | Higher-level search abstraction and query optimization shared by Lucene and Elastic backends. See: `oak-doc/src/site/markdown/query/query.md`, `oak-doc/src/site/markdown/query/indexing.md`, `oak-doc/src/site/markdown/query/index-management.md` |
| `oak-lucene` | Lucene indexing | Embedded Apache Lucene full-text and property indexing with facets, suggestions, spellcheck, and excerpts. See: `oak-doc/src/site/markdown/query/lucene.md`, `oak-doc/src/site/markdown/query/property-index.md`, `oak-doc/src/site/markdown/query/hybrid-index.md` |
| `oak-search-elastic` | Elasticsearch indexing | Distributed full-text search and indexing via external Elasticsearch cluster. See: `oak-doc/src/site/markdown/query/elastic.md` |

### Security

| Module | Purpose | Description |
|--------|---------|-------------|
| `oak-security-spi` | Security SPI | Interfaces for authentication and authorization provider plugins. See: `oak-doc/src/site/markdown/security/overview.md`, `oak-doc/src/site/markdown/security/introduction.md` |
| `oak-auth-external` | External authentication | Framework for external identity providers (SAML, OAuth, etc.) with user/group synchronization. See: `oak-auth-external/README.md`, `oak-doc/src/site/markdown/security/authentication/external/` |
| `oak-auth-ldap` | LDAP authentication | LDAP/Active Directory identity provider with user and group sync. See: `oak-auth-ldap/README.md`, `oak-doc/src/site/markdown/security/authentication/ldap.md` |
| `oak-authorization-cug` | Closed User Groups | CUG-based authorization restricting read access to defined groups on subtrees. See: `oak-doc/src/site/markdown/security/authorization/cug.md` |
| `oak-authorization-principalbased` | Principal-based authz | Access control evaluated by principal rather than by content path. See: `oak-doc/src/site/markdown/security/authorization/principalbased.md` |

### Tools & CLI

| Module | Purpose | Description |
|--------|---------|-------------|
| `oak-run` | CLI toolbox | Runnable JAR with commands for backup, restore, compaction, debug console, index management, migration, and more. See: `oak-run/README.md`, `oak-doc/src/site/markdown/command_line.md`, `oak-doc/src/site/markdown/features/oak-run-nodestore-connection-options.md` |
| `oak-run-commons` | CLI shared code | Base classes shared by oak-run commands |
| `oak-run-elastic` | CLI Elastic tools | Elasticsearch-specific oak-run commands |
| `oak-upgrade` | Migration tools | Upgrades Jackrabbit 2.x repositories to Oak, including node store migration between backends. See: `oak-doc/src/site/markdown/migration.md` |
| `oak-http` | HTTP binding | REST/HTTP server exposing Oak repository operations remotely. See: `oak-http/README.md` |

### Other Modules

- **Benchmarks:** `oak-benchmarks`, `oak-benchmarks-lucene`, `oak-benchmarks-elastic` — performance benchmarks. See: `oak-benchmarks/README.md`
- **Integration tests:** `oak-it`, `oak-it-osgi` — cross-module and OSGi integration tests
- **Examples / training:** `oak-exercise`, `oak-examples`, `oak-pojosr` — learning resources and OSGi test registry
- **Documentation:** `oak-doc` (build with `-Pdoc`) — site source at `oak-doc/src/site/markdown/`

## Feature Toggles

Oak has a built-in feature toggle mechanism for safely rolling out new behavior at runtime.
Toggles are disabled by default and can be enabled without redeployment.

**Core API** (in `oak-core-spi`):
- `Feature` — wrapper for checking toggle state. Create with `Feature.newFeature(name, whiteboard)`,
  check with `feature.isEnabled()`, clean up with `feature.close()`.
  See: `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/spi/toggle/Feature.java`
- `FeatureToggle` — the underlying toggle registered on the OSGi Whiteboard. State is managed
  via `setEnabled(boolean)` using an `AtomicBoolean` (thread-safe).
  See: `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/spi/toggle/FeatureToggle.java`

**Naming convention:** toggle names follow the pattern `FT_OAK-<issue>` or
`FT_<DESCRIPTION>_OAK-<issue>` (e.g., `FT_OAK-11949`, `FT_CLASSIC_MOVE_OAK-10147`).

**How it works:**
1. A component creates a toggle: `Feature ft = Feature.newFeature("FT_OAK-XXXXX", whiteboard)`
2. The toggle is registered on the Whiteboard for runtime discovery
3. Code checks the toggle: `if (feature != null && feature.isEnabled()) { ... }`
4. Admin tooling can discover and flip toggles via the Whiteboard at runtime

**Examples in the codebase:**
- Query engine toggles (`FT_OAK-11949`, `FT_OAK-12007`) registered in `oak-core/.../Oak.java`
- Document store toggles (throttling, full GC, embedded verification) in
  `oak-store-document/.../DocumentNodeStoreBuilder.java`
- Some features also support a system property fallback (e.g., `oak.classicMove`)

## Code Conventions

### Style
- No wildcard imports (import each class individually)
- Follow existing code style in the module you are modifying
- OSGi bundle compliance is required - modules produce OSGi bundles
- Never use regular expression parsing and find / replace for JSON or XML data

### License Header
Every Java file must start with the Apache 2.0 license header. The build enforces this
via the Apache RAT plugin. Use this exact header:

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

### Annotations
- Use `@NotNull` / `@Nullable` from `org.jetbrains.annotations` for API contracts
- Use OSGi annotations (`@Component`, `@Activate`, etc.) for service components

## Git Workflow

- **Main branch:** `trunk` (not master/main). Never commit directly to trunk
- **Issue tracker:** Apache Jira, project key `OAK` (e.g., OAK-12345)
- **Branch naming:** `issue/OAK-<issue_number>` (e.g., `issue/OAK-12345`). If no Jira
  issue is specified, ask the user what the branch name should be
- **Commit message format:** Start with Jira issue key: `OAK-XXXXX: Description of change`
- **PR target:** PRs should target `trunk`
- All changes must be committed to the issue branch, never directly to trunk
- **Code review:** After pushing changes, remind the user to request a review from
  committers who have previously contributed to the affected modules

## CI

- **GitHub Actions:** Runs on PRs and pushes to trunk. Builds with Java 11, runs full
  test suite with `SEGMENT_TAR` and `DOCUMENT_NS` fixtures.
- **Jenkins:** Parallel module testing pipeline.
- **SonarCloud:** Code quality analysis runs after build.
- **Commit checks:** PR commit messages and branch names are validated automatically.

## Common Pitfalls

- Always build `oak-parent` first if dependencies are missing:
  `mvn install -pl oak-parent -DskipTests`
- MongoDB must be running locally (port 27017) for `DOCUMENT_NS` fixture tests
- Some modules have long-running integration tests; use `-DskipTests` or `-Pfast` for
  quick iteration
- OSGi baseline checks may fail if you change a public API - this is intentional to
  prevent accidental breaking changes
