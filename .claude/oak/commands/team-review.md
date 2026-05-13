# Team Review

Comprehensive team review — either a full project review or a PR-focused review for Apache Jackrabbit Oak.

## Usage

- `/team-review` — Full project / area review
- `/team-review <pr-url-or-number>` — Review a specific PR
- `/team-review <focus>` — Project review focused on a specific area (e.g., `oak-store-document`, `security`, `query`)

## Mode Detection

- If a PR URL or number is provided → **PR Review Mode**
- Otherwise → **Project / Area Review Mode**

---

## PR Review Mode

### Step 1: Fetch PR

Extract repo from the URL (or default to `apache/jackrabbit-oak`) and the PR number. Fetch PR details and diff:

```bash
gh pr view <number> --repo apache/jackrabbit-oak --json title,body,files,additions,deletions,author,baseRefName,headRefName
gh pr diff <number> --repo apache/jackrabbit-oak
```

Identify which Oak modules the PR touches by inspecting the file paths.

### Step 2: Create team and dispatch reviewers

Create a team session: `TeamCreate(team_name: "oak-review-pr<number>")`.

Create a `TaskCreate` per reviewer assignment. Dispatch reviewers **in parallel** (single message, foreground, `mode: "bypassPermissions"`):

1. **grace** — Java implementation review
   - *Focus:* correctness, OSGi DS lifecycle, Oak NodeStore/Tree API usage, JUnit 4 conformance, license header on new files, no JUnit 5 introduced.
   - *Output:* Post at most 1 PR comment (≤5 actionable bullets), prefixed `**[Java Review — Grace]**`. Skip if no actionable feedback.

2. **ada** — Architecture review
   - *Focus:* module boundaries (SPI/API/impl), feature toggle use for non-trivial changes, OSGi component design, dependency direction.
   - *Output:* Post at most 1 PR comment (≤5 actionable bullets), prefixed `**[Architecture Review — Ada]**`.

3. **sage** — Security review (always for `oak-security-spi`, `oak-auth-*`, `oak-authorization-*`; otherwise scoped to dependency / data exposure concerns)
   - *Focus:* permission-evaluation correctness, sensitive property handling, dependency CVEs, OSGi `Export-Package` minimality, 100% coverage on security modules.
   - *Output:* Post at most 1 PR comment (≤5 actionable bullets, severity-rated), prefixed `**[Security Review — Sage]**`.

4. **alex** — Oak security expert (only when the PR touches security modules or security APIs)
   - *Focus:* correct use of public Oak security SPI, composite authorization semantics, principal/permission evaluation order, source-cited verification.
   - *Output:* Post at most 1 PR comment (≤5 actionable bullets with `file:line` citations), prefixed `**[Oak Security Review — Alex]**`.

5. **turing** — Testing review
   - *Focus:* coverage rules (>80% general; 100% security), fixture coverage where the change is fixture-sensitive, JUnit 4 framework consistency, no weakened assertions.
   - *Output:* Post at most 1 PR comment (≤5 actionable bullets), prefixed `**[Testing Review — Turing]**`.

### Step 3: Collect and summarize

After all agents complete, produce a consolidated summary:

```
## Review Summary

**PR:** #<number> — <title>
**Modules touched:** oak-<a>, oak-<b>
**Reviewers:** Grace, Ada, Sage, Turing[, Alex]

### Findings
- [list key findings from each reviewer]

### Verdict
- [ ] Approve / Request Changes / Comment
```

### Step 4: Post to GitHub (with user confirmation)

Ask the user if they want to post the reviews as PR comments. Only post after explicit approval.

---

## Project / Area Review Mode

### Step 1: Gather project / area state

If a focus area is specified (e.g., `query`, `security`, `oak-store-document`), narrow the review to those modules.

- Run `mvn -pl <focus-module> -am clean install` (or `-Pfast` for speed) to verify build health
- Review `git log --oneline -20 -- <focus-paths>` for recent changes
- Read the relevant area docs from `oak-doc/src/site/markdown/`

### Step 2: Create team and dispatch reviewers

Create a team session and dispatch agents in parallel (single message). Compose the team based on the focus:

- Always: **ada** (architecture), **grace** (implementation), **turing** (testing)
- For security focus: add **sage** + **alex**
- For documentation/area survey: add **shannon**

### Step 3: Consolidated report

Produce a consolidated report organized by severity, with module/file references.
