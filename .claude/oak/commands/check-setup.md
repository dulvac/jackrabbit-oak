# Check Setup

Validate that all prerequisites for working on Apache Jackrabbit Oak are installed and configured.

## Checks

Run these checks and report status for each:

### Required Tools

| Tool | Check Command | Required Version |
|------|---------------|------------------|
| Java | `java -version` | 11+ |
| Maven | `mvn -version` | 3.x |
| Git | `git --version` | any |
| gh CLI | `gh --version` | any |
| MongoDB (optional) | `mongod --version` | any 4.x+ — only needed for `DOCUMENT_NS` fixture tests |

### GitHub Authentication

```bash
gh auth status
```

Verify the user is authenticated against `github.com` (Apache Jackrabbit Oak lives at `apache/jackrabbit-oak`).

### Build Verification (Fast)

```bash
mvn clean install -Pfast
```

Verifies the multi-module build completes without running tests. Equivalent to `-DskipTests` plus disabled coverage. See `AGENTS.md` "Build Commands" for full options.

### Build Verification (Full)

```bash
mvn clean install
```

Runs the full test suite. Substantially slower; recommend only when needed.

### Single-Module Build

```bash
mvn clean install -pl oak-core -am -DskipTests
```

Builds `oak-core` and its dependencies. Use to verify a module-targeted change compiles.

### Test Run

```bash
mvn test -pl oak-core
```

Runs the test suite for a single module.

### Local MongoDB (DOCUMENT_NS fixture)

```bash
nc -z localhost 27017 && echo "MongoDB reachable on 27017"
```

If you plan to run `DOCUMENT_NS` fixture tests, MongoDB must be running on `localhost:27017`.

### Apache RAT (License Header)

```bash
mvn -pl oak-core apache-rat:check
```

Verifies that all source files have the Apache 2.0 license header. Required for new files (see `AGENTS.md` "License Header").

### MCP Servers

Check that Context7 MCP server is configured and accessible (used for fetching current Oak / JCR / Lucene / OSGi / library docs).

## Output

Report results as a table:

| Check | Status | Notes |
|-------|--------|-------|
| Java 11+ | PASS/FAIL | version found |
| Maven 3.x | PASS/FAIL | version found |
| gh CLI | PASS/FAIL | version found |
| GitHub auth | PASS/FAIL | account info |
| Fast build | PASS/FAIL | error details |
| `oak-core` test | PASS/FAIL | test counts |
| MongoDB (optional) | PASS/SKIP | reachable on 27017 |
| Apache RAT | PASS/FAIL | header violations |
| Context7 MCP | PASS/FAIL | connection status |
