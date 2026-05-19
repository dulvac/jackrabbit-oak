# Audit SPI — Bridge Design (Architect: Ada) — Revised after team review

Companion to `01-architecture.md` and `06-scope-and-flow.md`. Final design for the AEM/Sling-facing bridge over the Path α audit SPI.

This document supersedes the v1 draft of the same name. It reflects the team-review consensus: **Proposal C** — drop the externally-callable producer fire-and-forget API, narrow the bridge to commit-attached-only for v1, defer non-commit and outbound signals to v1.1+ behind concrete trigger conditions.

The route to this design is recorded in `08-bridge-design-history.md` (process artifact) — the short version: the original two-channel design (`recordOnCommit` + `emit`) was challenged by alex on trust-model grounds (commit-attached external `record()` guarantees event *lifecycle* but not event *truthfulness*) and by sage on credential-leak grounds (`security` domain MUST NOT reach OSGi EventAdmin under any configuration). The collapsed v1 shape preserves the user's stated ergonomics for their actual use case (AEM content-fragment publishing — already commit-attached) without shipping a surface area whose security invariants the team cannot defend.

---

## 1. Module placement — `oak-audit-bridge-api` + `oak-audit-bridge` (split)

**Decision: split into two Maven modules.** Unchanged from v1.

| Module | Maven coordinates | Exports | Depends on |
|---|---|---|---|
| `oak-audit-bridge-api` | `org.apache.jackrabbit:oak-audit-bridge-api` | `org.apache.jackrabbit.oak.audit.bridge` | `javax.jcr:jcr`, `org.osgi:osgi.annotation.versioning`, `org.jetbrains:annotations` |
| `oak-audit-bridge` | `org.apache.jackrabbit:oak-audit-bridge` | (none — internal `Impl` package) | `oak-audit-bridge-api`, `oak-security-spi`, `oak-api`, `oak-jcr`, `oak-jackrabbit-api`, OSGi DS annotations |

AEM Maven-depends on `oak-audit-bridge-api` (provided scope) only. Its compile classpath does NOT include `oak-security-spi` — the no-coupling rule is enforced at the compiler, not aspirationally.

Mirrors Oak's existing pattern: `oak-api` + `oak-core`, `oak-jackrabbit-api` + `oak-jcr`. Nothing novel.

---

## 2. Channel topology — commit-attached only; A/B/C taxonomy

**Decision: v1 ships ONE listener method (`onCommit`) fed by TWO commit-attached capture paths** (Oak-internal and bridge-asserted), distinguished at the event level by `AuditEvent.getOriginBundle()`. **No `emit()`. No `onEvent()` SPI extension. No synthetic `CommitInfo`.**

### Channel taxonomy (alex/sage joint framing)

| Channel | Origin | Capture site | Listener method | Trust attestation |
|---|---|---|---|---|
| **A** | Oak-internal commit-attached | `UserManagerImpl.onGroupUpdate`, `AccessControlManagerImpl.setPolicy`, etc. — call `AuditEvents.record(Root, AuditEvent)` | `onCommit` | `event.getOriginBundle().isEmpty()` → Oak-attested |
| **B** | Oak-internal monitor-attached | `LoginModuleMonitor.loginSucceeded`, future `*Monitor` SPIs — bridged to `registry.onEvent(...)` | (`onEvent` — v1.1+ when Channel B lands) | `event.getOriginBundle().isEmpty()` → Oak-attested |
| **C** | Bridge commit-attached, bundle-asserted | AEM bundle calls `AuditEventEmitter.recordOnCommit(Session, BridgeAuditEvent)` → bridge wraps with `originBundle` via OSGi DS prototype-scope binding → `AuditEvents.record(String, AuditEvent)` | `onCommit` | `event.getOriginBundle().isPresent()` → bundle-asserted |

**v1 ships Channel A + Channel C.** Channel B is v1.1+ (deferred per §7.1). Both A and C use the same `onCommit` listener method; listeners differentiate trust via `event.getOriginBundle()` (see §6.3 below).

The v1 listener method signature is unchanged from `01-architecture.md`:
```java
void onCommit(@NotNull NodeState after,
              @NotNull CommitInfo commitInfo,
              @NotNull List<AuditEvent> events);
```
`getOriginBundle()` is a new `default Optional<String>` method on the existing `AuditEvent` interface (see §6.3.3). Default `Optional.empty()` for Oak-internal capture sites; the bridge impl overrides via OSGi DS prototype-scope binding to return the symbolic name of the calling bundle.

### Rejected: externally-callable `emit(BridgeAuditEvent)`

Three named use cases (login, permission-denied, workflow) drove the original two-channel design. All three have better existing channels:

| Use case | Better channel | Cite |
|---|---|---|
| Login audit (success / failure) | `LoginModuleMonitor.loginSucceeded(AuthInfo)` extension; audit bridge subscribes via Whiteboard | `oak-security-spi/.../authentication/LoginModuleMonitor.java:30-69`; capture site `LoginModuleImpl.java:177` |
| Permission denied audit | Sling auth filter at request layer (Oak `PermissionProvider.isGranted` is per-node hot path: 100k+ calls per bulk query) | `oak-core/.../security/authorization/permission/*` |
| Workflow / non-Oak audit | Sling EventAdmin direct; AEM publishes outside Oak's audit pipeline | (not an Oak concept) |

For future Oak-internal non-commit signals (indexing failures, GC, replication): the **typed `*Monitor` SPI is the Oak idiom**. Each signal gets a dedicated Monitor interface; the audit bridge subscribes to all monitors of interest. A generic `emit()` channel would reinvent this pattern in untyped form.

### The killer argument against external `record()` for security-domain events

Even commit-attached external emission of `security`-domain events is unsafe (alex, source-cited):

- The commit-attached lifecycle guarantees: *if* this commit succeeds, the audit event dispatches; *if* it fails, the event discards. It does NOT guarantee the audit event reflects the actual Tree changes in the commit.
- AEM bundle can call `recordOnCommit(session, MemberAddedEvent.of(adminGroup, bob))` while the session mutates a totally unrelated property. The commit succeeds. Audit dispatches. SIEM fires "bob added to admins" — false.
- A trust-flag mitigation (`trusted=false` for non-Oak-bundle origins) doesn't fix this — the flag indicates *origin*, not *truthfulness*. SIEMs that filter on `trusted=true` make AEM-originated events dead-letter; SIEMs that don't filter get a poisoned trail. There's no useful middle ground.
- **Truth-of-claim requires capture at the operation site, not at the caller's discretion.** This is the structural invariant Oak's audit pipeline has historically maintained.

### What `recordOnCommit` is still for

The user's actual use case — AEM publishing audit events for content fragment lifecycle (create / publish / unpublish) — is **not** a security-domain event. Content fragments are JCR-backed: publishing IS a `Root.commit()`. The audit event domain for these is AEM-application-specific (e.g., `"aem.content"`), not `security`. For non-security domains, the truthfulness invariant is weaker — content-fragment audit serves AEM compliance, not Oak security; AEM is the canonical source of truth for AEM content events.

Therefore `recordOnCommit(Session, BridgeAuditEvent)` survives in v1 **for non-security domains** with structural enforcement that `BridgeAuditEvent` instances cannot carry a security-reserved domain (see §4 and §6 below).

### Single-method listener: only `onCommit`

`AuditEventListener.onCommit(NodeState, CommitInfo, List<AuditEvent>)` ships in v1 unchanged from `01-architecture.md`. The earlier-proposed `default void onEvent(List<AuditEvent>)` SPI extension is also dropped:

- Without a v1 fire-and-forget path, no production code dispatches into `onEvent`.
- Without a v1 use case, the default method is dead surface that would require Javadoc, tests, and version-stability commitments.
- When the LoginModuleMonitor → audit-bridge path lands in v1.1+ (alex's roadmap), we add `onEvent` then with a concrete listener consuming it. SPI growth via default method remains backward-compatible.

---

## 3. `AuditEventEmitter` service contract

```java
// oak-audit-bridge-api/src/main/java/org/apache/jackrabbit/oak/audit/bridge/AuditEventEmitter.java
@ProviderType
public interface AuditEventEmitter {

    /**
     * Records an audit event against the underlying Oak session backing
     * the supplied JCR session. The event is buffered until the next
     * successful {@code Root.commit()} on that session, then dispatched
     * synchronously on the merge thread to all matching listeners.
     * Discarded on commit failure or {@code Root.refresh()} /
     * {@code Root.rebase()}.
     *
     * <p>The bridge enforces three runtime checks at the entry point,
     * in order. Any violation throws {@link IllegalArgumentException}
     * synchronously and emits a WARN log identifying the calling bundle
     * via {@code FrameworkUtil.getBundle(event.getClass()).getSymbolicName()}
     * for forensic attribution:
     * <ol>
     *   <li><strong>Reserved-domain check.</strong> The event's domain
     *       MUST NOT be in
     *       {@link BridgeAuditDomains#RESERVED_DOMAINS}. The current
     *       reserved set is {@code "security"}. Closes alex's three
     *       falsification scenarios (forged {@code MemberAddedEvent},
     *       {@code AccessControlPolicyRemovedEvent},
     *       {@code TokenCreatedEvent}) at the entry point.</li>
     *   <li><strong>Instance-type check.</strong> The event MUST be
     *       {@code instanceof BridgeAuditEventImpl}. Third-party
     *       implementations of the {@link BridgeAuditEvent} interface
     *       are rejected. Construction MUST be via a factory method on
     *       {@link BridgeAuditEvent}; bypassing this requires defining
     *       a parallel impl class, which the instance-type check
     *       refuses.</li>
     *   <li><strong>Payload-key credential check.</strong> Payload keys
     *       MUST NOT match {@code (?i)password|secret|token|key$}.
     *       Caller intent (accidental or otherwise) is irrelevant —
     *       the bridge refuses to forward credential-shaped keys.</li>
     * </ol>
     * Audit infrastructure must fail loud on misuse.
     *
     * @throws IllegalArgumentException if the session is not Oak-backed,
     *         has been closed, or the event violates the constraints above.
     */
    void recordOnCommit(@NotNull Session jcrSession, @NotNull BridgeAuditEvent event);

    /**
     * Returns {@code true} when at least one listener is registered for
     * the given domain AND the audit feature toggle is enabled.
     * Callers should gate event construction with this method to avoid
     * allocation when audit is off (matches the
     * {@code AuditEvents.isEnabledFor} pattern).
     */
    boolean isEnabledFor(@NotNull String domain);
}
```

**No `emit(...)` method.** No fire-and-forget. No session-less variant.

The contract is deliberately narrow: AEM may emit audit events for AEM-application-domain JCR mutations (content fragments, replication-state changes, etc.) where AEM IS the canonical capture site. AEM may NOT emit audit events for Oak security-domain mutations — those are owned by Oak's internal capture sites and emitted via `AuditEvents.record(...)` from oak-core code only.

---

## 4. Event class location — typed `BridgeAuditEvent` with structural domain enforcement

**Decision: typed `BridgeAuditEvent` interface in `oak-audit-bridge-api`. Sage's 4-constraint package applied.**

```java
// oak-audit-bridge-api/src/main/java/org/apache/jackrabbit/oak/audit/bridge/BridgeAuditEvent.java
@ProviderType
public interface BridgeAuditEvent {
    @NotNull String getDomain();
    @NotNull String getType();
    long getTimestamp();
    @NotNull Map<String, Object> getPayload();

    /**
     * Factory: event in a non-reserved domain. Throws
     * IllegalArgumentException if {@code domain} is in
     * {@link BridgeAuditDomains#RESERVED_DOMAINS}.
     */
    static BridgeAuditEvent of(@NotNull String domain, @NotNull String type, @NotNull Map<String, Object> payload) {
        if (BridgeAuditDomains.RESERVED_DOMAINS.contains(domain)) {
            throw new IllegalArgumentException(
                "Domain '" + domain + "' is reserved by Oak. " +
                "External callers cannot emit events in this domain.");
        }
        return new BridgeAuditEventImpl(domain, type, payload);
    }
}

// oak-audit-bridge-api/src/main/java/org/apache/jackrabbit/oak/audit/bridge/BridgeAuditDomains.java
public final class BridgeAuditDomains {

    /**
     * Domain names reserved for internal Oak capture sites. External
     * callers using {@link BridgeAuditEvent#of(String, String, Map)}
     * cannot emit events in these domains; the factory rejects them.
     *
     * <p>Mirrors {@code AuditConstants.RESERVED_DOMAINS} from
     * {@code oak-security-spi} as string literals — AEM cannot depend
     * on oak-security-spi, so this Set is duplicated. A JUnit test in
     * oak-audit-bridge-api asserts the two Sets contain the same string
     * values, catching drift at build time.
     *
     * <p>The bridge's runtime check at
     * {@code AuditEventEmitter.recordOnCommit(...)} consults
     * {@code AuditConstants.RESERVED_DOMAINS} (oak-security-spi) as
     * the canonical source of truth — this Set is for AEM-side
     * defensive checks before construction.
     */
    public static final Set<String> RESERVED_DOMAINS = Set.of(
        "security"  // matches AuditConstants.RESERVED_DOMAINS in oak-security-spi
    );

    private BridgeAuditDomains() {}
}
```

`BridgeAuditEventImpl` is package-private in `oak-audit-bridge-api`. It is the **only** public construction path for `BridgeAuditEvent` instances; the bridge's runtime check enforces `event instanceof BridgeAuditEventImpl` at `recordOnCommit(...)` to reject third-party implementations of the interface.

**Single source of truth on the SPI side**: `AuditConstants.RESERVED_DOMAINS` lives in `oak-security-spi` (see §6.3.1). The bridge impl's runtime check imports from there. `BridgeAuditDomains.RESERVED_DOMAINS` is the consumer-facing mirror. The build-time drift test guarantees they stay aligned.

### Future shape (v1.1+ pending the AEM AuditLog forwarder)

Per shannon's research, the AEM `AuditLogEntry` constructor takes typed positional fields: `(String category, Date time, String userid, String path, String type, Map<String,Object> properties)`. When the AEM AuditLog forwarder lands (§7.2), it benefits from `BridgeAuditEvent` exposing `getUserId()` and `getPath()` as top-level methods rather than burying them in payload-map keys (which would force per-event-type translation logic in the forwarder).

The v1 shape above (no typed userId/path) is intentionally minimal — these fields are not yet useful because no v1 outbound forwarder consumes them, and adding them prematurely would commit to a construction contract before the forwarder's needs are concrete. The `@ProviderType` annotation on the interface allows v1.1+ to add `default String getUserId()` and `default String getPath()` as method additions without breaking consumers (consumers cannot implement `@ProviderType` interfaces; Oak provides the impls).

When v1.1+ adds these methods, the factory grows correspondingly:
```java
// future v1.1+ shape (not v1)
static BridgeAuditEvent of(@NotNull String domain, @NotNull String type,
                           @Nullable String path,
                           @NotNull Map<String, Object> payload);
```
`userId` is enriched by the bridge at `recordOnCommit(...)` time from `session.getUserID()` — callers do not provide it (avoids duplicating Session-derived state and avoids spoofing the actor field).

### Sage's 4-constraint package — how each constraint is honored

| # | Constraint | How honored |
|---|---|---|
| 1 | Typed `BridgeAuditEvent` subclasses only; no opaque `(domain, type, Map)` emission path | `recordOnCommit` accepts `BridgeAuditEvent` (typed), not three positional strings/map. The `BridgeAuditEvent.of(...)` factory is the documented construction path. |
| 2 | Domain is `final` on the event class | `BridgeAuditEventImpl` stores `domain` as `final String`. No setter. Factory-set. |
| 3 | Compile-time check that no `BridgeAuditEvent`-shaped class hard-codes a reserved domain | JUnit test in `oak-audit-bridge-api` that uses reflection to scan all `BridgeAuditEvent` instances constructable via the public factories and asserts none return a reserved domain. Simpler infrastructure than an annotation processor; Oak has no annotation-processor precedent. |
| 4 | Runtime check at `recordOnCommit(...)` entry | `AuditEventEmitterImpl.recordOnCommit` does (a) instance-type check (`event instanceof BridgeAuditEventImpl`) to reject forged impls, (b) domain-set check against `RESERVED_DOMAINS`, (c) payload-key regex check for credential-shaped keys. All three throw `IllegalArgumentException` on violation. |

These four constraints together make it structurally impossible for an AEM bundle to emit a `security`-domain audit event through the bridge: the factory rejects construction, the runtime check rejects forged interface impls, the compile-time test rejects new-event-class additions with reserved domains. Three independent layers; bypassing all three requires direct bytecode manipulation, at which point the deployer has already lost the trust battle elsewhere.

---

## 5. Session → sessionId resolution (grace's option D)

**Decision: do not resolve `Session` to `Root` in the bridge. Resolve to `String sessionId` only, and pass through a new `AuditEvents.record(String, AuditEvent)` overload.**

### Why not resolve to `Root`

The bridge only needs a buffer key. `AuditBuffer` (in `oak-core`) is keyed by `sessionId` — the same String returned by `ContentSession.toString()`. Currently `AuditEvents.Sink.record(Root, AuditEvent)` extracts that key inside oak-core via `root.getContentSession().toString()`; the bridge would either need to reach into oak-jcr internals (`SessionImpl.getSessionContext().getSessionDelegate().getRoot()`) or have oak-jcr widen its public API to expose `Root` — both invasive.

Resolving to `sessionId` directly is one extra default method on `JackrabbitSession` and one extra overload on the `AuditEvents.Sink`. Smaller widening, zero `Root` leakage.

### Public surface delta

| Module | Change | Visibility |
|---|---|---|
| `oak-jackrabbit-api` | `+ default @NotNull String JackrabbitSession.getInternalSessionId()` | exported (`@ProviderType` default method, safe addition) |
| `oak-jcr` | `SessionImpl.getInternalSessionId()` override returning `sd.getContentSession().toString()` | private (internal override) |
| `oak-security-spi` | `+ void AuditEvents.Sink.record(@NotNull String sessionId, @NotNull AuditEvent event)` | exported |
| `oak-security-spi` | `+ static void AuditEvents.record(@NotNull String sessionId, @NotNull AuditEvent event)` | exported |
| `oak-audit-bridge-api` | NEW module (`AuditEventEmitter`, `BridgeAuditEvent`, `BridgeAuditDomains`) | full module |
| `oak-audit-bridge` | NEW module (impl) | full module |

### `JackrabbitSession.getInternalSessionId()` Javadoc requirement

```java
/**
 * Returns the underlying Oak session identifier. Format and stability
 * are internal Oak detail; the value is intended only for audit-bridge
 * integration. Consumers other than the audit bridge should not call
 * this method, and the format may change between Oak versions.
 *
 * <p>Implementations backed by Oak return
 * {@code ContentSession.toString()}. The default implementation throws
 * {@link UnsupportedOperationException} for non-Oak {@code Session}
 * implementations.
 */
@NotNull
default String getInternalSessionId() {
    throw new UnsupportedOperationException(
        "getInternalSessionId is implemented only by Oak-backed sessions.");
}
```

### Bridge flow

```
AEM bundle:
    Session session = ...;  // JCR session
    BridgeAuditEvent event = BridgeAuditEvent.of("aem.content", "FragmentPublished", payload);
    auditEventEmitter.recordOnCommit(session, event);
        │
        ▼
AuditEventEmitterImpl.recordOnCommit(Session, BridgeAuditEvent):
    1. Check session.isLive(); throw IAE if not
    2. Cast to JackrabbitSession; throw IAE if not (non-Oak session)
    3. Check event instanceof BridgeAuditEventImpl; throw IAE if not (forged impl)
    4. Check !RESERVED_DOMAINS.contains(event.getDomain()); throw IAE if reserved
    5. Scan event.getPayload().keySet() for (?i)password|secret|token|key$; throw IAE if match
    6. sessionId = jackrabbitSession.getInternalSessionId()
    7. AuditEvent translated = new AuditEventAdapter(event)  // wraps BridgeAuditEvent
    8. AuditEvents.record(sessionId, translated)
        │
        ▼
AuditEvents.Sink.record(String sessionId, AuditEvent event):
    buffer.record(sessionId, event)
        │
        ▼ (standard commit pipeline: SnapshotAuditBufferHook → DispatchAuditEventsHook → listeners)
```

The existing `AuditEvents.record(Root, AuditEvent)` and `AuditEvents.Sink.record(Root, AuditEvent)` paths stay unchanged for in-tree capture sites (UserManagerImpl, AccessControlManagerImpl, etc.). The new String-keyed overloads are additive.

---

## 6. Sensitive-field defense (sage's three additions, all adopted)

### 6.1 Compile-time scan via JUnit test in `oak-security-spi`

A reflection-based JUnit test scans all `AuditEvent` subclasses for declared field names matching the credential pattern `(?i)password|secret|token|key$`. Test fails if any match.

Sage's original proposal was an annotation processor. Oak has no annotation-processor precedent; a test achieves the same coverage with simpler infrastructure. The test runs on every build (Surefire), so the gate is equivalent in practice.

Test location: `oak-security-spi/src/test/java/org/apache/jackrabbit/oak/spi/security/audit/AuditEventCredentialFieldsTest.java`. Scans the `org.apache.jackrabbit.oak.spi.security.audit` package and any registered event subclasses.

### 6.2 Runtime payload-key check in `oak-audit-bridge`

`AuditEventEmitterImpl.recordOnCommit(...)` rejects events whose payload contains a key whose lowercase form is in `AuditConstants.FORBIDDEN_PAYLOAD_KEYS` (canonical set in `oak-security-spi`, see §6.3.1). Throws `IllegalArgumentException` with a clear message naming the offending key.

```java
private void rejectIfCredentialShaped(@NotNull BridgeAuditEvent event) {
    for (String key : event.getPayload().keySet()) {
        if (AuditConstants.FORBIDDEN_PAYLOAD_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                "Payload key '" + key + "' is a forbidden credential name. "
                    + "Identifier fields (tokenPath, tokenNodeId, policyKey, etc.) are "
                    + "permitted — only literal credential names are blocked.");
        }
    }
}
```

The canonical Set lives in `oak-security-spi/.../AuditConstants.FORBIDDEN_PAYLOAD_KEYS` (§6.3.1). The bridge IMPL (`oak-audit-bridge`) imports it directly — no mirror is needed in `oak-audit-bridge-api` because the factory does NOT check forbidden keys at construction; only the IMPL's runtime check at `recordOnCommit(...)` consults the Set. AEM learns about forbidden keys via runtime `IllegalArgumentException`, not at construction. Credential-key names are not a deliberate-misuse vector; the check defends against bug-triggered surprise (caller accidentally includes a sensitive field).

**Why equals-match (Set) and NOT substring match (regex)** — grace caught a real defect in the earlier regex `(?i)password|secret|token|key$`:

| Legitimate v1.1+ payload key | Source | Regex behavior | Set behavior |
|---|---|---|---|
| `tokenNodeIdentifier` | `TokenCreatedEvent` per `06-scope-and-flow.md` ("only token-node identifier") | matches `token` substring → REJECTED (false positive) | not in Set → ALLOWED |
| `policyKey` | hypothetical principal-id key in `AccessControlPolicySetEvent` | matches `key$` anchored → REJECTED (false positive) | not in Set → ALLOWED |
| `apiKeyId` | hypothetical config-rotation event | regex behavior depends on anchor | not in Set → ALLOWED |
| `password` | obvious credential | matches → REJECTED ✓ | in Set → REJECTED ✓ |
| `userPassword` | hypothetical credential field | matches (substring) → REJECTED ✓ | not in Set → ALLOWED (would need explicit addition if it should reject) |

The regex's substring matching produces false positives on legitimate v1.1+ fields explicitly documented in our own scope doc (`tokenNodeIdentifier`). Shipping it would silently break legitimate v1.1+ token-audit emissions 100% of the time. The Set-based equals-match avoids the false positive at the cost of explicit maintenance: every new credential shape requires explicit Set membership. That's a feature (explicit > implicit) and matches how `TokenConstants.RESERVED_ATTRIBUTES` is maintained.

Defense in depth: the §6.1 compile-time test catches static field declarations on event classes (different threat — credential-leak via accidentally-named fields); the §6.2 runtime check catches dynamic payload-map construction by callers (different threat — credential-leak via dynamically-keyed payload).

### 6.3 SPI additions for the v1 trust model (joint sage+alex)

Five small additions that together make the A/C trust model machine-checkable and contractually visible to listener implementers:
- §6.3.1: new `AuditConstants` interface in `oak-security-spi` (canonical source of `RESERVED_DOMAINS` and `FORBIDDEN_PAYLOAD_KEYS`)
- §6.3.2: new `BridgeAuditDomains` class in `oak-audit-bridge-api` (string-literal mirror, preserves §1 no-coupling invariant)
- §6.3.3: new `default Optional<String> AuditEvent.getOriginBundle()` method on the existing SPI interface
- §6.3.4: class-level Trust Model Javadoc on `AuditEventListener` in `oak-security-spi`
- §6.3.5: tightened `@param commitInfo` Javadoc on `AuditEventListener.onCommit`

**Historical note**: an earlier draft of §6.3.3 was withdrawn after a brief sage-initiated reversal proposed dropping `getOriginBundle()` in favor of pure domain-based discrimination. Alex caught a §7.6 cross-reference conflict in the reversal's Trust Model Javadoc; sage then withdrew the reversal and confirmed Option A (typed method) is the cleaner SPI surface. The team's final convergence is Option A as documented in §6.3.3 below; the reversal sidebar is preserved in inbox history but does not appear in this doc.

#### 6.3.1 New `AuditConstants` interface (oak-security-spi)

Canonical home for trust-model constants. Houses both `RESERVED_DOMAINS` (domain-level forgery prevention) and `FORBIDDEN_PAYLOAD_KEYS` (payload-level credential-leak prevention) — single source of truth so the compile-time test (§6.1) and runtime check (§6.2) reference the same constant.

```java
// oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/AuditConstants.java
@ProviderType
public interface AuditConstants {

    /**
     * Domain names reserved for internal Oak emission. External callers
     * MUST NOT emit events claiming any of these domains via the bridge
     * service {@code AuditEventEmitter}. Enforcement at the write boundary
     * (bridge service {@code recordOnCommit(...)}) AND via a unit test
     * of the {@code BridgeAuditEvent} factory in
     * {@code oak-audit-bridge-api}.
     * <p>
     * For v1 this set is hardcoded; v1.1 may introduce an append-only
     * {@code ReservedDomainContributor} SPI for other Oak security
     * modules (e.g., {@code oak-auth-external} to reserve
     * {@code "external-identity"}).
     */
    Set<String> RESERVED_DOMAINS = Set.of(SecurityAuditDomain.NAME);

    /**
     * Payload-map keys whose presence in a {@code BridgeAuditEvent}
     * payload indicates a credential value. The bridge rejects events
     * whose payload contains any of these keys (case-insensitive
     * equals-match on the lowercased key).
     *
     * <p>This is an <strong>equals-match</strong> allowlist, NOT a
     * substring or regex match. Identifier fields whose names contain
     * credential-related substrings (e.g. {@code tokenPath},
     * {@code tokenNodeId}, {@code policyKey}, {@code apiKeyVersion})
     * are NOT blocked — only field names that are themselves credential
     * tokens.
     *
     * <p>For v1 this set is hardcoded; v1.1+ may extend it as new
     * credential classes emerge. Specific membership is reviewed by
     * sage at impl time; the v1 set below is the working content from
     * the design-review thread.
     */
    Set<String> FORBIDDEN_PAYLOAD_KEYS = Set.of(
        "password", "passwd", "pwd",
        "secret", "clientsecret",
        "token", "accesstoken", "refreshtoken",
        "apikey", "privatekey", "publickey",
        "credentials",
        "authorization", "auth",
        "session", "sessionid",
        "salt", "passwordsalt",
        "jwt", "jwttoken", "oauthtoken", "bearertoken", "idtoken",
        "signingkey", "keystorepassword"
    );
}
```

Mirrors the pattern of `org.apache.jackrabbit.oak.spi.security.authentication.token.TokenConstants.RESERVED_ATTRIBUTES` — a hardcoded, immutable Set of names off-limits to external callers.

The `RESERVED_DOMAINS` and `FORBIDDEN_PAYLOAD_KEYS` constants ARE NOT mirrored on the bridge-api side (unlike `BridgeAuditDomains.RESERVED_DOMAINS` — which IS mirrored because the bridge factory in `oak-audit-bridge-api` references it). `FORBIDDEN_PAYLOAD_KEYS` is consulted only by the bridge IMPL at runtime (oak-audit-bridge has the oak-security-spi dependency), never by AEM. AEM learns about forbidden keys only via runtime `IllegalArgumentException` from `recordOnCommit(...)`. This is acceptable because credential-key names are unlikely to be intentionally constructed; the failure mode is the bug-triggered surprise, not deliberate misuse.

#### 6.3.2 `BridgeAuditDomains` string-literal mirror (oak-audit-bridge-api)

A separate Set in `oak-audit-bridge-api`, hard-coded as string literals because **`oak-audit-bridge-api` does NOT depend on `oak-security-spi`** (per §1's no-coupling invariant — AEM's compile classpath must not transitively include `oak-security-spi` types). The bridge factory `BridgeAuditEvent.of(...)` references the local `BridgeAuditDomains.RESERVED_DOMAINS`; the bridge IMPL (`oak-audit-bridge`, which does depend on `oak-security-spi`) references the canonical `AuditConstants.RESERVED_DOMAINS` for its runtime check.

A test-scope JUnit test in `oak-audit-bridge-api/src/test/` declares a test-scope dependency on `oak-security-spi` and asserts:
```java
assertEquals(AuditConstants.RESERVED_DOMAINS, BridgeAuditDomains.RESERVED_DOMAINS);
```
The test-scope dependency does not propagate to consumers of `oak-audit-bridge-api`. AEM's compile classpath stays clean; drift between the two Sets is caught at build time.

This two-constant arrangement is defense in depth: the bridge factory rejects at construction (using the AEM-facing mirror); the bridge IMPL re-checks at `recordOnCommit(...)` entry (using the canonical source); the drift test guarantees they cannot diverge. Sage proposed deleting `BridgeAuditDomains` and having `oak-audit-bridge-api` import `AuditConstants` directly — this conflicts with §1's invariant (would force AEM to transitively see `oak-security-spi` on its compile classpath). The mirror+drift-test arrangement preserves §1.

#### 6.3.3 `AuditEvent.getOriginBundle()` typed method

```java
// oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/AuditEvent.java
@ProviderType
public interface AuditEvent {
    // ... existing methods (getDomain, getType, getTimestamp, getPayload) ...

    /**
     * Returns the bundle that originated this event, if it was emitted
     * via the {@code oak-audit-bridge} {@code AuditEventEmitter}
     * service.
     *
     * <p>For events captured by Oak's own internal sites
     * (e.g. {@code UserManagerImpl.onGroupUpdate}, monitor SPIs), this
     * returns {@link Optional#empty()}.
     *
     * <p>For events emitted via the bridge, this returns a non-empty
     * Optional. In OSGi deployments the value is the symbolic name of
     * the bundle that invoked
     * {@link org.apache.jackrabbit.oak.audit.bridge.AuditEventEmitter#recordOnCommit};
     * in non-OSGi test fixtures the value is the literal sentinel
     * {@code "(non-osgi)"}. The bridge-side guarantee is that
     * {@code .isPresent()} returns {@code true} for ALL bridge-emitted
     * events, with no further conditions.
     *
     * <p>This invariant makes {@code event.getOriginBundle().isPresent()}
     * a total discriminator between bridge-emitted events and
     * Oak-internal events; listeners can rely on this for trust-model
     * branching (see {@link AuditEventListener} class-level Javadoc)
     * without additional conditions.
     *
     * <p>The value is set by the bridge from the OSGi
     * {@code ComponentContext.getUsingBundle()} binding context at
     * service-activation time; callers cannot forge or override it.
     *
     * @return non-null Optional. {@link Optional#empty()} for Oak-internal
     *         events; {@link Optional#of(Object)} for bridge-emitted events.
     */
    @NotNull
    default Optional<String> getOriginBundle() {
        return Optional.empty();
    }
}
```

The default `Optional.empty()` makes the addition backward-compatible for the existing `SecurityAuditEvent` subclasses and for any future event class that doesn't go through the bridge. The bridge's internal `BridgeAuditEventAdapter implements AuditEvent` overrides this method to return the symbolic name captured at `@Component(scope = ServiceScope.BUNDLE)` activation time via `ComponentContext.getUsingBundle().getSymbolicName()` — with a `"(non-osgi)"` sentinel for non-OSGi test fixtures where `getUsingBundle()` returns null. The bridge mechanism is `ComponentContext.getUsingBundle()` — NOT `FrameworkUtil.getBundle(callerClass)` + stack walking — per the alex/sage joint recommendation (stack walking is fragile under proxies, reflection, and thread-pool dispatch).

**Why typed method, not payload key:**
- Caller cannot forge — no setter on the interface; the bridge sets it via the service-injection context, which the AEM caller cannot reach.
- Compile-time visible in IDE and Javadoc.
- Single-line listener check: `event.getOriginBundle().isPresent()`.

**Why the `"(non-osgi)"` sentinel rather than `Optional.empty()` for non-OSGi**: keeping the bridge always non-empty is what makes `.isPresent()` a TOTAL discriminator. If non-OSGi returned `Optional.empty()`, listeners would have to chain `.isPresent()` checks with additional conditions (e.g., domain heuristics) to differentiate "Oak-internal" from "bridge-in-test-fixture" — that's a probabilistic discriminator, and the security risk is that a listener that "fails open" (treats `.isEmpty()` as "Oak-attested, trust unconditionally") would treat a bridge-in-non-OSGi event as Oak-attested. The sentinel forecloses that interpretation. Test code can assert `.equals("(non-osgi)")` if it needs to distinguish OSGi from non-OSGi origins.

#### 6.3.4 `AuditEventListener` class-level Trust Model Javadoc

Add this `<h3>Trust model</h3>` section to the class-level Javadoc of `AuditEventListener` (after the existing dispatch-order paragraph). Joint sage+alex authored, discriminating by `getOriginBundle()`:

```java
 * <h3>Trust model</h3>
 *
 * Each {@link AuditEvent} carries a
 * {@linkplain AuditEvent#getOriginBundle() trust attribution}.
 * Listeners distinguish two event classes:
 *
 * <ul>
 *   <li><strong>Oak-attested</strong>
 *       ({@code event.getOriginBundle().isEmpty()}):
 *       the event was produced by Oak's own capture sites
 *       (e.g. {@code UserManagerImpl.onGroupUpdate}). Oak guarantees:
 *       <ol>
 *         <li>rollback-discard if the commit fails;</li>
 *         <li>the event reflects an actual Oak operation;</li>
 *         <li>the domain is genuinely Oak's own.</li>
 *       </ol>
 *   </li>
 *   <li><strong>Bundle-asserted</strong>
 *       ({@code event.getOriginBundle().isPresent()}):
 *       the event was produced by an external bundle via
 *       {@code AuditEventEmitter.recordOnCommit(...)}.
 *       Oak guarantees:
 *       <ol>
 *         <li>rollback-discard if the commit fails;</li>
 *         <li>the domain is non-reserved (no security-domain forgery
 *             is possible — compile-time + runtime enforcement).</li>
 *       </ol>
 *       Oak does <strong>not</strong> guarantee that the event reflects
 *       a real operation in the bundle's domain — that is the bundle's
 *       own attestation.
 *   </li>
 * </ul>
 *
 * Compliance-grade listeners should treat bundle-asserted events as
 * caller claims and may apply additional verification (e.g. correlate
 * against {@link NodeState} changes) when truth-of-claim matters.
```

This Javadoc closes the gap alex flagged in refinement 1: listeners reading only `AuditEventListener` Javadoc previously had to deduce the trust model from §3 + §4 of this design doc. With the class-level addition, the trust model is contractually visible at the SPI surface.

#### 6.3.5 `@param commitInfo` tightening (the OAK_UNKNOWN binding)

Joint sage+alex recommendation. Replace the descriptive Javadoc on `commitInfo` (current skeleton lines 86-91) with a prescriptive contract:

```java
 * @param commitInfo the {@link CommitInfo} associated with the commit;
 *                   {@link CommitInfo#getSessionId()} and
 *                   {@link CommitInfo#getUserId()} are guaranteed
 *                   non-null. For system commits,
 *                   {@link CommitInfo#getUserId()} returns
 *                   {@link CommitInfo#OAK_UNKNOWN} ({@code "oak:unknown"}).
 *                   This is a deliberate anonymity boundary for
 *                   repository-internal operations. <strong>Listeners
 *                   MUST NOT attempt to resolve {@code OAK_UNKNOWN} to a
 *                   real underlying user.</strong> Audit consumers that
 *                   need to distinguish system from user operations
 *                   should match on the literal {@code OAK_UNKNOWN}
 *                   constant.
```

Three reasons (per sage's note):
1. **Compliance integrity**: a SIEM forwarder that "resolves" `OAK_UNKNOWN` to the OS user or active subject creates a misleading audit record — worse than no data.
2. **Threat-model integrity**: system commits exist for repository-internal reasons (compaction, GC, indexes). Surfacing a fake user identity hides which operations are user-driven vs. internal; an attacker exploiting a system-commit path then appears to "be" a real user in the audit log.
3. **Contract enforceability**: without the explicit MUST NOT, a future listener author writes a "helpful" resolver and reviewers can't cite the SPI contract to reject it.

---

## 7. Decisions deferred to v1.1+

Each item below is documented with a one-line **trigger condition** — the concrete event in the project that will cause that work to start. Future maintainers can grep for these markers to find the right starting point.

### 7.1 Login event audit (alex's roadmap)

**Trigger**: a concrete listener implementation lands that wants login-success / login-failure events.

**Implementation sketch** (per alex's source-cited proposal):
- Add `default void loginSucceeded(@NotNull AuthInfo authInfo) {}` to `LoginModuleMonitor` (`oak-security-spi/.../authentication/LoginModuleMonitor.java:30-69`). Default empty for backward compatibility.
- Insert `getLoginModuleMonitor().loginSucceeded(authInfo)` at:
  - `oak-core/.../security/authentication/user/LoginModuleImpl.java:177` (just before `return true` in `commit()`)
  - `oak-core/.../security/authentication/token/TokenAuthentication.java` (success branch of `authenticate(...)`)
  - `oak-auth-external/.../impl/ExternalLoginModule.java` (success branch of `commit()`)
- New `LoginAuditBridge` in oak-core registered as a separate `@Component(service = LoginModuleMonitor.class)`. It implements `LoginModuleMonitor` and translates monitor callbacks to `AuditEvent` instances.
- This requires the `default void onEvent(List<AuditEvent>)` SPI extension on `AuditEventListener` that v1 declines to ship. Add it then with a concrete listener consuming it.
- Login-failure events: never include credential values in payload — only the credentials class name (precedent: `LoginModuleMonitorImpl.java:74-82`).

### 7.2 Outbound forwarding — pluggable, AEM AuditLog primary

**Trigger**: a non-security audit domain ships (e.g., indexing telemetry, query metrics) — the first domain whose events are bridgeable.

**Why deferred for v1**: security is the only v1 domain. Sage's hard rule says `domain=security` MUST NOT reach OSGi EventAdmin under any configuration. Therefore a v1 EventAdmin outbound listener would forward zero events — dead code. AEM AuditLog forwarding is safe for `security`-domain events (channel is JCR-ACL-gated; not open broadcast) but requires the typed userId/path fields on `BridgeAuditEvent` (§4) which we're deferring.

**Architectural shape (per shannon's research)**: do NOT hard-wire any single outbound destination. Define an `AuditEventForwarder` SPI in `oak-audit-bridge-api` and ship three reference implementations as **separate bundles** so each has independent OSGi enablement, configuration, and dependency surface:

| Bundle (proposed) | Wraps | Domain policy | Default state |
|---|---|---|---|
| `oak-audit-bridge-forwarder-aem-auditlog` | `com.day.cq.audit.AuditLog#add(AuditLogEntry)` | All domains (including `security`) — channel is JCR-ACL-gated under `/var/audit/`, durable, persists across restarts | Recommended primary for AEM |
| `oak-audit-bridge-forwarder-sling-jobs` | `JobManager#addJob(AuditLogEvent.JOB_TOPIC, props)` carrying `AUDIT_EVENT_PROPERTY` | All domains; at-least-once durability via `/var/eventing/jobs` | Durable alternative for non-AEM Sling deployments |
| `oak-audit-bridge-forwarder-eventadmin` | `EventAdmin#postEvent(...)` (async, ordered per-thread) | **Excludes `security` domain** at code level; non-security only | Disabled by default; opt-in for fire-and-forget notification consumers only |

**Why EventAdmin is the tertiary option, not primary** (defending the open-debate position from shannon's report):
- No durability — JVM crash drops in-flight events. Audit must survive crashes.
- Felix handler blacklisting — slow handlers (e.g., SIEM forwarder doing a network round-trip > 5s) are silently killed. Audit must not silently drop.
- Open broadcast — any bundle with `ServicePermission(EventHandler, REGISTER)` and the topic filter receives every event. No payload-level access control. `TopicPermission` requires `SecurityManager` (inert in AEM/Sling deployments).
- Wrong semantic model — EventAdmin is observation/notification, not durable audit. Sling already deprecated EventAdmin for resource events in favor of `ResourceChangeListener`.

**Implementation sketch (for whichever forwarder bundle lands first)**:
- Forwarder registers as `AuditEventListener` in its own bundle. The bridge impl receives the dispatched event, translates `AuditEvent → BridgeAuditEvent` (now with typed userId/path per §4 future shape), passes to the forwarder.
- Code-level `NEVER_FORWARD_DOMAINS = Set.of("security")` in the **EventAdmin forwarder** only — not in the AEM AuditLog forwarder or Sling Jobs forwarder. The blacklist is a per-forwarder concern: EventAdmin's open-broadcast nature makes it unsafe for security; AuditLog's ACL-gated channel makes it safe.
- For EventAdmin forwarder: mandatory `AuditEventSanitizer` Whiteboard service; fail-closed if none registered. Topic shape `org/apache/jackrabbit/oak/audit/<domain>/<type>` (verified no Sling convention conflict — shannon).
- For AEM AuditLog forwarder: typed `@Reference AuditLog` (cardinality OPTIONAL — AEM may not be in the runtime). Direct `AuditLog.add(AuditLogEntry)` call. The five concerns sage flagged (§7.5) apply.
- For Sling Jobs forwarder: typed `@Reference JobManager` (cardinality OPTIONAL). `JobManager.addJob(AuditLogEvent.JOB_TOPIC, props)` with payload as a property. **Loop concern bounded**: Sling Jobs write through `ResourceResolver` to `/var/eventing/jobs`, which doesn't touch security capture sites (UserManagerImpl, AccessControlManagerImpl) — so jobs don't loop into the audit pipeline.

### 7.3 Non-security domains

**Trigger**: a concrete capture site outside the `security` domain identifies a need for an audit event class.

**Pre-emptive design constraint**: any new domain MUST be added to `BridgeAuditDomains.RESERVED_DOMAINS` if it's Oak-internal (i.e., the AEM bridge cannot originate events in that domain), and added to `oak-audit-bridge`'s `NEVER_FORWARD_DOMAINS` if outbound forwarding is unsafe for it. Default for new Oak-internal domains is "reserved" — explicit opt-out required.

### 7.4 Compile-time annotation processor — considered and REJECTED as redundant

**Status: rejected, not deferred.** Initially this design proposed a compile-time annotation processor in `oak-security-spi` to reject `AuditEvent` subclasses with credential-shaped field names, then was downgraded to a JUnit-test substitute. Turing's verification of in-tree precedent and sage's concession converged on a stronger position: **the annotation processor is unnecessary at all levels.**

Rationale (joint sage + turing):
- The factory `BridgeAuditEvent.of(...)` already throws `IllegalArgumentException` on reserved-domain construction at first instantiation. A unit test of the factory's rejection path covers the structural guarantee.
- A PIT mutation test (sage's earlier suggestion to prove the gate is on every dispatch path) is replaced by an **architectural-funneling pattern** in the bridge impl: all dispatch overloads route through a single `recordOnCommitChecked(...)` private helper. One unit test of the helper proves the gate fires on every dispatch path.
- Oak has no in-tree precedent for annotation processors or PIT mutation testing. Introducing either for a problem already covered by JUnit + Mockito is over-engineering.

**Re-trigger condition**: a future-state where the factory rejection path can be bypassed at runtime (e.g., a new code path adds an event-construction route that doesn't go through `BridgeAuditEvent.of(...)`). At that point, the architectural-funneling invariant has been broken and we re-evaluate whether to introduce an annotation processor — but the more likely fix is to refactor back to the funneling pattern.

### 7.5 Sage's five operational concerns on AuditLog forwarding

**Note (two-audit-channel distinction, per shannon's research)**: AEM ships two audit channels that earlier discussions had been conflating:
- `com.day.cq.audit.AuditLog` — JCR-backed, persistent under `/var/audit/`, ACL-gated reads. **This is the bridge's v1.1+ outbound target.**
- `useraudit.log` — SLF4J appender, filesystem `error.log`. NOT a bridge target; this channel is filesystem-only and has a different mechanism for plumbing.

Per shannon: "The bridge forwards events that crossed Oak's commit boundary (or its complementary Monitor SPI for non-commit security signals). It is NOT a replacement for the AEM `useraudit.log` filesystem appender, which captures events that happen below Oak's SPI surface (e.g. JAAS login internals before `LoginContext.login()` returns). Operators should expect both channels to coexist post-v1.1+."

When 7.2 lands, the AuditLog outbound path (the FIRST channel above) must handle:

1. **Loop risk** — the bridge's own AuditLog writes are themselves Oak commits. Bridge MUST filter by `CommitInfo.getSessionId()`: events from the bridge's own service-session ID are dropped.
2. **Service-user least privilege + distinct attribution** — bridge writes via a dedicated system user with `jcr:write` only on `/var/audit/<bridge-prefix>/`. **This user MUST NOT be the same as `cq-audit`'s service user** — if both share an identity, the audit trail can't distinguish "bridge wrote because AEM emitted" from "cq-audit's internal write." Distinct attribution is a separate concern from least-privilege.
3. **Payload type fidelity** — explicit mapping policy for AuditLog `properties`; unmappable values reject (and log WARN), not silently drop.
4. **Metadata key preservation** — underscore-prefixed payload keys (`_originBundle`, `__pii.*`) MUST survive translation to AuditLog `properties`. Document in the translator's Javadoc.
5. **AEM API version coupling** — POM declares `com.day.cq.audit` as `provided` + minimum version. Document the minimum AuditLog API version in `oak-audit-bridge/AGENTS.md`.

**Deployer verification checklist** — to live in `oak-audit-bridge/AGENTS.md` (operator-facing, not architect-facing) when 7.2 lands:

1. `/var/audit` ACLs: verify NOT readable by `everyone` (anonymous group) or `content-authors` group — operator confirms via CRX/DE permissions view.
2. Service-user distinction: bridge's audit-write service user MUST NOT be the same as `cq-audit`'s service user.
3. Compliance-dashboard read groups: if `aem-audit-readers` (or similar) exists, document that it has read-only access and CANNOT trigger the bridge's write path.

Exact default ACL values (which service user, which read group) live in `cq-audit`'s `repoinit` script, which we'd pull at v1.1+ implementation time per shannon's offer to source the definitive ACL appendix from a fresh Cloud Service SDK install.

### 7.6 Bundle-identity provenance — three-control framing

**Status: two of three controls ship in v1; one is deferred.**

Sage's three-control framing — the design intent is NOT to amalgamate bundle-attribution into a single "bundle stamp" mechanism. Each control addresses a different threat with different semantics:

| Control | Purpose | Mechanism | v1 or deferred |
|---|---|---|---|
| **Source-bundle TAG** (`AuditEvent.getOriginBundle()`) | Post-fact attribution: "which bundle emitted this event?" | OSGi DS prototype-scope at injection time; per-instance `Bundle` reference cached at construction; surfaced via the typed method on `AuditEvent` | **v1** (§6.3.3) |
| **Reserved-domain registry** (`AuditConstants.RESERVED_DOMAINS`) | Prevention: "this domain cannot be claimed by any external emitter" | Hardcoded `Set` in oak-security-spi, mirrored as hard-coded string literals in `oak-audit-bridge-api.BridgeAuditDomains`; build-time, not runtime config | **v1** (§6.3.1 + §6.3.2) |
| **Source-bundle ALLOWLIST** | Prevention: "is this bundle allowed to emit at all?" | OSGi config `acceptInboundFromBundles` (default empty = deny all) + optional `BridgeInboundPolicy` Whiteboard service for richer rules | **v1.1+** |

These three controls address three distinct threats:
- **Honest-mistake** (bundle X has a bug in its emit call) → TAG identifies the bug source via `event.getOriginBundle()` (v1, structured); the WARN log at the bridge's IAE rejection path also captures it for forensic forensics (`FrameworkUtil.getBundle(event.getClass()).getSymbolicName()` — different mechanism, same forensic value).
- **Hostile-bundle-on-allowlist** (bundle Y is allowed but emits forged non-security content) → reserved-domain prevents security-domain forgery (v1); typed-event-class instance check bounds non-security forgery (v1); TAG identifies the originating bundle for post-hoc analysis (v1).
- **Hostile-bundle-not-on-allowlist** (bundle Z is malicious, not allowed) → ALLOWLIST blocks at API entry (v1.1+); without ALLOWLIST in v1, prevention relies on reserved-domain + AEM operator's choice of which bundles to install. TAG (v1) does NOT block emission — but provides post-fact attribution.

**Trigger for v1.1+ ALLOWLIST**: a deployment requires "only these named bundles may emit audit events" (typical in regulated environments). For v1, the absence of an allowlist is acceptable because the reserved-domain registry blocks the high-stakes forgery (security domain), TAG provides forensic attribution, and AEM operators control which bundles are installed at deployment time.

**Cross-reference to §7.3** (per alex's refinement 2): the ALLOWLIST control is structurally unnecessary in v1 because §7.3's invariant holds — Oak-internal and external-callable domains are disjoint. The ALLOWLIST work in v1.1+ only becomes necessary if §7.3's invariant is relaxed (e.g., a future domain has BOTH Oak-internal and external producers, where domain-discrimination alone can't distinguish them and TAG attribution becomes important for active filtering). The TAG remains useful even under §7.3's invariant because it makes the bridge-asserted attribution visible to listeners without forcing them to encode domain heuristics.

**Implementation note** — the v1 TAG mechanism uses OSGi `ServiceFactory<T>` / DS prototype scope (framework hands the bridge the requesting bundle at lookup time). The bridge MUST NOT use `FrameworkUtil.getBundle(callerClass)` + stack walking — it is fragile under proxies, reflection, and thread-pool dispatch. The class-loader-based `FrameworkUtil.getBundle(event.getClass()).getSymbolicName()` used in §3's WARN log on rejection is acceptable for that path because (a) it's a logging-only side effect, not a decision input, and (b) the rejected event's class was loaded by the emitting bundle's class loader, which is a reliable forensic signal.

---

## 8. Test coverage requirements

`oak-audit-bridge` is security-adjacent (it handles `security`-domain events even though it rejects external production of them). It falls under Oak's 99/100 coverage rule.

POM additions:
```xml
<minimum.line.coverage>0.99</minimum.line.coverage>
<minimum.branch.coverage>1.00</minimum.branch.coverage>
```

Documented in `oak-audit-bridge/AGENTS.md` with the rationale. No JaCoCo exclusions for inbound filtering classes (`AuditEventEmitterImpl`, `BridgeAuditDomains`, `BridgeAuditEventImpl`).

---

## 9. Summary of v1 decisions

| Topic | Decision | Citation |
|---|---|---|
| Module placement | Two-module split: `oak-audit-bridge-api` + `oak-audit-bridge` | §1; mirrors `oak-api`+`oak-core`, enforces no-`oak-security-spi`-coupling at the compiler |
| Channel topology | One listener method (`onCommit`) fed by Channels A (Oak-internal commit-attached) + C (bridge commit-attached bundle-asserted). Channel B (monitor-attached) deferred to v1.1+. | §2; alex/sage joint A/B/C taxonomy |
| `AuditEventEmitter` contract | `recordOnCommit(Session, BridgeAuditEvent)`, `isEnabledFor(String)` — two methods | §3 |
| Event class | Typed `BridgeAuditEvent` interface in `oak-audit-bridge-api`; sage's 4-constraint package applied | §4 |
| Reserved domains | `AuditConstants.RESERVED_DOMAINS = Set.of(SecurityAuditDomain.NAME)` in oak-security-spi (canonical); `BridgeAuditDomains.RESERVED_DOMAINS` in oak-audit-bridge-api (string-literal mirror — preserves §1 no-coupling invariant); test-scope drift test asserts equality | §4 + §6.3.1 + §6.3.2 |
| Origin-bundle attribution | `Optional<String> AuditEvent.getOriginBundle()` default method on the existing SPI interface; bridge impl overrides via OSGi DS prototype-scope injection. **v1** (was withdrawn briefly per sage's composite revision; sage then withdrew that withdrawal, confirming v1 ships the typed method). | §6.3.3 |
| Listener trust model | Class-level Javadoc `<h3>Trust model</h3>` section on `AuditEventListener` distinguishing Oak-attested (`event.getOriginBundle().isEmpty()`) from bundle-asserted (`event.getOriginBundle().isPresent()`) events. Bundle-attribution-based discrimination. | §6.3.4 |
| `OAK_UNKNOWN` listener binding | Tightened `@param commitInfo` Javadoc with explicit `<strong>MUST NOT resolve OAK_UNKNOWN</strong>` clause | §6.3.5 |
| Session resolution | `String sessionId` via new `JackrabbitSession.getInternalSessionId()` default method + new `AuditEvents.{Sink,}.record(String, AuditEvent)` overload — NOT `Root` resolution | §5; grace's option D, smallest total surface widening |
| Credential field defense | (a) compile-time JUnit test scanning AuditEvent subclasses for credential-shaped field names, (b) runtime payload-key check via case-insensitive equals-match against `AuditConstants.FORBIDDEN_PAYLOAD_KEYS` (NOT regex — grace caught a false-positive defect with substring matching). Canonical Set lives in oak-security-spi (§6.3.1). | §6.1 + §6.2 |
| Considered & rejected | Compile-time annotation processor for credential field rejection — superseded by §6.1 JUnit test + §3 factory rejection. PIT mutation testing — superseded by architectural-funneling pattern (single `recordOnCommitChecked` helper). | §7.4 |
| Coverage gates | 99% line / 100% branch on `oak-audit-bridge` POM | §8 |
| Deferred to v1.1+ | Login event audit (Channel B), pluggable outbound forwarders (AEM AuditLog primary, Sling Jobs secondary, EventAdmin tertiary), non-security domains, source-bundle ALLOWLIST (`acceptInboundFromBundles` config), typed `getUserId`/`getPath` on `BridgeAuditEvent` | §7 |

---

## 10. Acknowledgments

The final shape of this design is the product of substantive team debate:

- **alex** identified the truth-of-claim invariant that killed the trust-flag mitigation; provided the source-cited `LoginModuleMonitor` extension path; co-authored the v1.1+ login-audit roadmap; proposed the A/B/C channel taxonomy now in §2; identified the §7.6 ↔ §7.3 deferral-logic cross-reference; caught a §7.6 cross-reference conflict in a sage-proposed Trust Model Javadoc reversal, prompting sage to withdraw the reversal and confirm Option A (typed `getOriginBundle()` in v1) as the team's convergence point.
- **sage** identified that `domain=security` must NEVER reach OSGi EventAdmin under any configuration; provided the 4-constraint package on `BridgeAuditEvent`; flagged the five operational concerns on AuditLog forwarding; proposed the v1 SPI delta (`AuditConstants` for both `RESERVED_DOMAINS` and `FORBIDDEN_PAYLOAD_KEYS`, `AuditEvent.getOriginBundle()`, `AuditEventListener` Trust Model class Javadoc, OAK_UNKNOWN binding); briefly proposed a composite revision dropping `getOriginBundle()` after alex flagged the §7.6 conflict, then withdrew the reversal upon recognizing that v3-final's typed-method approach + drift-test mirror was the cleaner integration; conceded the architect's pushback on item 5 (deleting `BridgeAuditDomains`) after the §1 no-coupling-invariant counter-argument; surfaced the `FORBIDDEN_PAYLOAD_KEYS` migration from bridge impl to `AuditConstants` after grace+turing's regex-defect catch.
- **grace** identified that resolving Session→Root in the bridge is more invasive than resolving Session→sessionId; provided the option D mechanics that minimize total surface widening; caught a real defect in the credential-key regex (`tokenNodeIdentifier`, `tokenPath`, `policyKey` would all be falsely rejected) jointly with turing during implementation stress-testing, motivating §6.2's switch from regex to case-insensitive equals-match against `AuditConstants.FORBIDDEN_PAYLOAD_KEYS`.
- **turing** identified that v1 outbound EventAdmin forwarding is dead code given the security-only v1 domain and the EventAdmin blacklist; pushed back with empirical evidence on annotation-processor and PIT mutation-test proposals (no Oak precedent for either), prompting the §7.4 "rejected as redundant" framing and the architectural-funneling pattern in the bridge impl.
- **shannon** confirmed no existing Sling audit-event topic convention exists (so the proposed `org/apache/jackrabbit/oak/audit/...` is fine standalone), identified the AEM `AuditLogEntry` typed shape `(category, time, userid, path, type, properties)` that motivates the §4 future-shape for `BridgeAuditEvent`, recommended pluggable forwarders (§7.2), made the case for AEM AuditLog and Sling Jobs over EventAdmin (no durability, handler blacklisting, open broadcast), and surfaced the two-audit-channel distinction (`com.day.cq.audit.AuditLog` vs `useraudit.log`) plus the deployer verification checklist now in §7.5.

The original two-channel design with externally-callable `emit()` was the architect's first draft. Folding in the team's findings produced a smaller, defensible v1 that preserves the user's actual ergonomic intent (AEM emits audit events for AEM-application-domain commit-attached operations) without shipping a producer API whose security invariants the team could not defend.
