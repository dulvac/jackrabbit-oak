# Review PRs

Browse and review open pull requests against `apache/jackrabbit-oak` (the Apache Jackrabbit Oak project on github.com).

## Usage

- `/review-prs` — List all open PRs, pick one interactively, dispatch team review
- `/review-prs <url-or-number>` — Directly review a specific PR by URL or number

## Workflow

### Step 1: Check if a PR identifier was provided

If the user provided a PR URL or number as an argument, skip to Step 3.

### Step 2: List open PRs

```bash
gh pr list --repo apache/jackrabbit-oak --state open --limit 20
```

Display results as a numbered table:

| # | PR | Title | Author | Age | Branch |
|---|-----|-------|--------|-----|--------|

Ask the user to pick a PR number.

### Step 3: Dispatch team review

Once a PR is selected, invoke `/team-review <pr-url-or-number>` to dispatch the review team.
