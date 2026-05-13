# Apache Jackrabbit Oak — Claude Code Project Overview

This file is the agent-facing project overview. The authoritative project guide is `AGENTS.md` at the repository root (imported by `CLAUDE.md`); this document complements it with team and tooling context.

## What Oak Is

Apache Jackrabbit Oak is a scalable, high-performance hierarchical content repository implementing the JCR (Java Content Repository) specification. It is a multi-module Maven project (~47 modules) written in Java 11.

For the full module map (NodeStore, Blob, Query, Security, Tools, etc.), see "Repository Structure" in `AGENTS.md`.

## Repository

- **Upstream**: `apache/jackrabbit-oak` on github.com (Apache Software Foundation project)
- **Issue tracker**: Apache JIRA, project key `OAK`. Tickets at `https://issues.apache.org/jira/browse/OAK-<number>`.
- **Default branch**: `trunk` (not `main` or `master`).
- **Java baseline**: 11+ (no records, no sealed types, no pattern matching for switch).
- **Build**: Maven 3.x.

## GitHub Authentication

| Context | Host | Notes |
|---------|------|-------|
| **Apache Jackrabbit Oak** | `github.com` | Default `gh` host. Run `gh auth status` to verify. |

## MCP Servers

- **Context7** — Up-to-date library documentation. Use `resolve-library-id` then `query-docs` for Oak / JCR / OSGi / Lucene / MongoDB / Elasticsearch / SLF4J / Tika docs before implementing.

(Other MCP servers can be added in `.mcp.json` if needed.)

## Agent Team

Agent teams are enabled for this project. Custom agent definitions live in `.claude/oak/agents/` and are exposed via the `.claude/agents/` symlink (see `README.md`).

| Agent | Name (ID) | Role | Focus |
|-------|-----------|------|-------|
| **Ada** | `ada` | Software Architect | Module boundaries, SPI/API/impl layering, OSGi lifecycle, NodeStore commit-path correctness |
| **Grace** | `grace` | Java Developer | Java 11 implementation, OSGi DS, JUnit 4 + Mockito 5, license headers, NodeStore/Tree API use |
| **Sage** | `sage` | Security Specialist | `oak-security-spi`, `oak-auth-*`, `oak-authorization-*` review; data exposure; dependency CVEs; coverage rules |
| **Alex** | `alex` | Oak Security Expert | Deep Oak security internals — composite authz, principals, permissions, tokens, CUG; source-cited |
| **Turing** | `turing` | QA / Testing | JUnit 4 / Mockito / EasyMock; multi-fixture testing (SEGMENT_TAR, DOCUMENT_NS); coverage; benchmarks |
| **Eliza** | `eliza` | AI-Native Specialist | `CLAUDE.md` / `AGENTS.md` / `.claude/**` / `.mcp.json`; agent definitions; tooling |
| **Shannon** | `shannon` | Research Specialist | `oak-doc/`, JCR spec, library docs via Context7, JIRA archaeology, git history |

### Team Orchestration (MANDATORY)

**Multi-agent work MUST use team sessions.** Without `TeamCreate`, agents are isolated and `SendMessage` calls are silently dropped. See `AGENTS.md` for the complete orchestration steps and `.claude/TEAM_WORKFLOW.md` for detailed patterns.

**Quick reference for the main agent (orchestrator):**
1. `TeamCreate(team_name: "oak-<purpose>")`
2. `TaskCreate(...)` for each work item
3. `Agent(name: "...", subagent_type: "...", team_name: "oak-<purpose>", mode: "bypassPermissions", prompt: "...")` — all agents in one message. **Always pass `mode: "bypassPermissions"`** (team-lead is the policy gate; otherwise child agents hang on permission prompts). **Include a teammate manifest** in each prompt. Tell agents to defer `config.json` read.
4. Agents use `SendMessage` to collaborate, `TaskUpdate` to track progress
5. `TeamDelete(team_name: "oak-<purpose>")` when done

**Protected paths (`.claude/`, `AGENTS.md`, `PROJECT.md`, `CLAUDE.md`, `.mcp.json`)** cannot be edited by team agents directly even with `mode: "bypassPermissions"`. Either (a) edit these as team-lead, or (b) use the symlink pattern (keep editable files under `.claude/oak/` and symlink to `.claude/`). See `.claude/TEAM_WORKFLOW.md` → "Permission Approvals Cannot Be Sent Via SendMessage (and protected paths)".

### Team Communication (For Agents in a Team Session)

When running in a team session, **proactively communicate with your teammates.** Don't wait to be told — if you have findings, questions, or feedback relevant to another agent's work, send it.

**On startup in a team session:**
1. Note the **teammate manifest** from your prompt (lists all agents + roles) — do NOT read `config.json` yet
2. Use `TaskList` to check your assigned tasks
3. Start working on your task
4. Read `~/.claude/teams/<team-name>/config.json` only when ready to send your first message (by then all agents have registered)

**Communication patterns:**
- **Direct message:** `SendMessage(to: "<name>", message: "...", summary: "...")` — for findings relevant to one agent
- **Broadcast:** `SendMessage(to: "*", message: "...", summary: "...")` — use sparingly, costs scale with team size
- **Report to lead:** `SendMessage(to: "team-lead", message: "...", summary: "...")` — for completion, blockers, summaries

**When to message whom:**
- Security risk found → message **sage**
- Oak security API question (composite authz, principals, permissions) → message **alex**
- Architecture concern → message **ada**
- Test needed → message **turing**
- Code fix needed → message **grace**
- Research needed (docs, JIRA, library APIs) → message **shannon**
- AI config change needed → message **eliza**

Your text output is NOT visible to teammates — you MUST use `SendMessage` to communicate with them.

### Collaborative Debate (EXPECTED)

**Technical excellence comes from rigorous discourse, not consensus-seeking.** This team is built from experts who respect each other enough to disagree honestly. When a teammate shares a proposal, finding, or design, your default response should be critical evaluation — not passive acceptance. Challenge assumptions. Ask probing questions. If you disagree with an approach, say so and explain why. Polite disagreement is expected and valued. See `.claude/TEAM_WORKFLOW.md` for the full Collaborative Debate Protocol, including the propose-challenge-defend-converge deliberation pattern.

## AI Config Gate — Eliza Required

Any changes to the following files MUST be either **delegated to Eliza** or **reviewed by Eliza** before completion:

- `CLAUDE.md`, `AGENTS.md`
- `.claude/**` (agents, settings, commands, `TEAM_WORKFLOW.md`, `PROJECT.md`)
- `.mcp.json`

**When to delegate vs. review:**
- **Delegate** when the task is primarily about AI config (e.g., "update agent definitions", "add a new command")
- **Review** when AI config changes are a side effect of other work (e.g., a new module that needs `AGENTS.md` updated) — the domain agent does the work, then Eliza reviews the AI config portions

## Build & Test (Quick Reference)

For the full set of build/test commands, see `AGENTS.md` "Build Commands" and "Test Commands".

```bash
# Fast build (no tests, no coverage)
mvn clean install -Pfast

# Build a single module + its dependencies, skipping tests for unchanged deps
mvn clean install -pl oak-core -am -DskipTests

# Rebuild a module and all modules that depend on it (after SPI/API change)
mvn clean install -pl oak-store-spi -amd -DskipTests

# Run tests for one module
mvn test -pl oak-core

# Run a specific test class
mvn test -pl oak-core -Dtest=TreeTest
```

## Conventions Quick Reference

- **Branch naming**: `issue/OAK-<num>` (e.g., `issue/OAK-12345`). Ask the user for the JIRA number if not specified.
- **Commit format**: `OAK-XXXXX: Description of change`
- **PR target**: `trunk`
- **License header**: required on every Java file (Apache RAT enforces). See `AGENTS.md` "License Header" for the canonical block.
- **Tests**: JUnit 4 + Mockito 5 (some modules use EasyMock). Do NOT introduce JUnit 5 unless asked.
- **Coverage**: >80% for new code; **100%** for `oak-security-spi`, `oak-auth-*`, `oak-authorization-*`.
- **Imports**: no wildcard imports.
- **Annotations**: `@NotNull` / `@Nullable` from `org.jetbrains.annotations`.
- **Feature toggles**: name `FT_OAK-<issue>` or `FT_<DESCRIPTION>_OAK-<issue>`. Bug fixes default ON; new features default OFF.

See `CONVENTIONS.md` (imported via `@.claude/CONVENTIONS.md`) for the full set.

## Key Architectural Pillars

1. **MVCC content model** — `NodeState` is immutable; mutations go through `Tree`/`Root` and produce a new revision on commit.
2. **Layered SPI/API/impl** — Public API in `oak-api`/`oak-jackrabbit-api`; SPIs in `oak-*-spi`; default implementations in `oak-core` and store/index modules.
3. **Composite security** — `SecurityProvider` aggregates `SecurityConfiguration`s; `CompositeAuthorizationConfiguration` evaluates with explicit AND vs OR semantics.
4. **Pluggable NodeStores** — Document (Mongo/RDB), Segment (TarMK + remote), Composite. Many features are NodeStore-agnostic but must work across fixtures.
5. **Pluggable indexes** — Property indexes, Lucene (embedded), Elasticsearch (external), hybrid; query optimizer selects per cost.
6. **Feature toggles for safe rollout** — `FT_OAK-<issue>` toggles registered on the Whiteboard, flippable at runtime.
