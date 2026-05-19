# Session → Oak Root — Survey & Minimal Addition for the Bridge

Companion to `06-scope-and-flow.md`. Answers: *how does the bridge translate a JCR `Session` into something the audit pipeline can buffer against, with the smallest possible widening of Oak's public surface?*

Owner: **grace** (Java/OSGi). Coordinated with **ada** (architecture).

---

## TL;DR

- A JCR `Session` is mapped to an Oak `Root` today **only through oak-jcr internals**. None of the path is publicly exported.
- The bridge does NOT need a `Root` — it needs the **session id** (a `String`, the AuditBuffer's primary key) plus the JCR-standard `getUserID()`.
- **Minimal public widening:** add one default method to `JackrabbitSession`:
  `String getInternalSessionId()`. Returns an opaque, stable-for-the-session-lifetime identifier. SessionImpl in oak-jcr overrides it.
- No need to widen oak-jcr's exports. No need to expose `Root`, `ContentSession`, `SessionDelegate`, or `SessionContext`.

---

## 1. Today's wiring

```text
javax.jcr.Session
   └── org.apache.jackrabbit.api.JackrabbitSession     (oak-jackrabbit-api  — EXPORTED, AEM compiles against this)
         └── org.apache.jackrabbit.oak.jcr.session.SessionImpl   (oak-jcr  — NOT exported)
               ├── private SessionDelegate sd;
               └── private SessionContext  sessionContext;        (NOT exported)
                     └── public SessionDelegate getSessionDelegate()  (NOT exported)
                           ├── public Root          getRoot()         (NOT exported)
                           └── public ContentSession getContentSession() (NOT exported)
                                 └── public String  toString()  ⇒  "session-N"      ← AuditBuffer key
```

`oak-jcr`'s `Export-Package` (from `oak-jcr/pom.xml`):
```
org.apache.jackrabbit.oak.jcr,
org.apache.jackrabbit.oak.jcr.observation.filter
```

Everything in `org.apache.jackrabbit.oak.jcr.session.*` and `.delegate.*` is private. There is **no public path from a `Session` to an Oak `Root`** today.

`ContentSession.toString()` (from `oak-core/.../ContentSessionImpl.java:84-87, 125-127`):
```java
this.sessionName = "session-" + SESSION_COUNTER.incrementAndGet();
...
public String toString() { return sessionName; }
```

This `"session-N"` string is what `AuditBuffer.record(@NotNull String sessionId, ...)` (per `03-skeleton/oak-core__AuditBuffer.java:48-49, 68`) uses as its primary key. That is the **only** identifier the audit pipeline cares about.

## 2. What the bridge actually needs

| Bridge channel | What it needs from `Session` | Why |
|---|---|---|
| `recordOnCommit` | session id (String) | Buffer key — events are looked up on the same thread at commit time. |
| `recordImmediate` | session id + user id | Synthesize a `CommitInfo` (`new CommitInfo(sessionId, userId, info, false)`). The synthesized `CommitInfo` flows into `AuditEventListener.onCommit(after, info, events)`. |

Both channels are satisfied by `(String sessionId, String userId)`. The user id is already available via `Session.getUserID()` (JCR-standard). Only the session id needs new plumbing.

The bridge does **NOT** need:
- An Oak `Root`. Events are buffered by sessionId; the commit-attached hook does its own `peek(sessionId)`. The immediate channel doesn't touch the buffer at all.
- A `ContentSession`. Same reason.
- Anything from `oak-jcr.session.*` or `.delegate.*`.

## 3. Options considered

### A. Reflection
Cast `Session` → `SessionImpl`, reach into `sessionContext`, call `getSessionDelegate().getContentSession().toString()`.
- **Reject.** Brittle, breaks under any rename, can't survive shading.

### B. Expose `SessionImpl.getRoot()` publicly
Add `Export-Package: org.apache.jackrabbit.oak.jcr.session` and make `SessionImpl` carry a public `Root getRoot()`.
- **Reject.** Largest possible widening. Drags every internal type in `oak-jcr.session.*` into the public surface. Couples downstream callers to Oak's internal API (`org.apache.jackrabbit.oak.api.Root`).

### C. Static helper in already-exported `org.apache.jackrabbit.oak.jcr` package
Add `public final class JcrSessionExtensions { public static String getInternalSessionId(Session s); }` in oak-jcr.
- **Reject.** Forces AEM to add `oak-jcr` as a *compile* dependency. AEM today compiles only against `oak-jackrabbit-api`; widening AEM's compile deps is worse than widening Oak's API by one tiny method.

### D. New default method on `JackrabbitSession` — *recommended*
```java
// oak-jackrabbit-api: org.apache.jackrabbit.api.JackrabbitSession
@NotNull
default String getInternalSessionId() {
    // Default for non-Oak JCR implementations: fall back to toString().
    // Stable-for-session-lifetime is the only contract. Oak's SessionImpl
    // overrides this to return the underlying ContentSession's id.
    return toString();
}
```
And in `oak-jcr.session.SessionImpl`:
```java
@Override @NotNull
public String getInternalSessionId() {
    return sd.getContentSession().toString();
}
```
- **Accept.** Smallest possible widening: one method, one `String` return type, no Oak SPI types exposed. `@ProviderType` on `JackrabbitSession` means consumers MUST NOT implement it — so a default method is safe to add without breaking anyone.

### E. Make the bridge accept a `String sessionId` directly (user's debate position)
Bridge API: `void recordOnCommit(String sessionId, AuditEventInput input)`. AEM extracts the id itself: `((JackrabbitSession) session).getInternalSessionId()`.
- **Discussed below.** Same widening as (D) but pushes the cast/extraction onto every AEM caller.

## 4. The Session-vs-sessionId debate

The team-lead's prompt set up the position:

> *"AuditEventEmitter must NOT take a JCR `Session` in its API. Coupling oak-jcr to the bridge forces SessionImpl to expose `getRoot()` publicly, widening the public API surface. The emitter should take `sessionId` (a String, opaque to AEM)."*

**Partial concurrence and a counter-proposal.**

Agreed: the bridge MUST NOT expose `Root`. The emitter must not bind AEM to oak-api.

Disagreed: making the bridge take `Session` does **not** force `SessionImpl.getRoot()` public. With option (D) we add **one String accessor** to `JackrabbitSession` — `String` is opaque to AEM, and `JackrabbitSession` is already AEM's compile-time view of Oak. That widening is unavoidable in *every* option (B, C, D, E) — all of them require some new way for AEM to identify the session.

What changes between (D) and (E) is **who** calls the new accessor:

| | (D) Bridge takes `Session` | (E) Bridge takes `String sessionId` |
|---|---|---|
| New JackrabbitSession method | Yes (`getInternalSessionId`) | Yes (same) |
| Public widening | One method | One method |
| Cast `Session → JackrabbitSession` | Inside the bridge impl, once | At every AEM call site |
| AEM caller mistakes | Hard to make | Easy (forget cast, pass arbitrary string, leak session id) |
| Type-safety | Session is a real object — emitter validates it is open and Oak-backed | String is just a string — emitter cannot validate provenance |
| Symmetry with JCR ergonomics | Matches every other Oak/JCR API (`AccessControlManager.getApplicablePolicies(Session)`, etc.) | Asymmetric |

**Recommendation: option (D).** The new accessor on `JackrabbitSession` is the unavoidable widening; once we've paid that cost we may as well give AEM the better API. The "no `Root` in the API" goal is met — the bridge takes `Session`, not `Root`.

**Concession to (E):** if security review (sage/alex) finds a spoofing risk where a malicious caller could fabricate a `sessionId` and slip events under another user's audit trail, then bridging via `Session` is actually *safer* than `String` — the bridge can sanity-check `session.isLive()` and verify the extracted id matches the live session before dispatch. This further argues for (D).

## 5. Failure modes covered by option (D)

Per Ada's `07-bridge-design.md` §3 — non-Oak / closed sessions throw `IllegalArgumentException`, not silent no-op. Silent failures hide audit gaps.

| Caller passes... | Bridge behavior |
|---|---|
| Null session | NPE per `@NotNull` contract — caller bug, fail fast. |
| Foreign `Session` (not `JackrabbitSession`) | `IllegalArgumentException` with the class name. |
| Closed `JackrabbitSession` | `session.isLive() == false`; `IllegalArgumentException` with the sessionId. |
| `JackrabbitSession` from a different repository instance | `recordOnCommit` writes to the per-session buffer keyed by the foreign sessionId; the local commit never drains it. The buffer entry leaks until the closing thread cleans up. **Mitigation deferred to v1.1** — bridge could maintain a weak set of known `ContentRepository` instances and reject foreign sessions, but the cost-benefit is not worth it for v1. |
| Wrong thread (caller A, save thread B) | `recordOnCommit` writes to thread A's ThreadLocal; thread B's commit never sees it; the event is silently dropped. Documented in `AuditEventEmitter` JavaDoc. **No way to detect cheaply at runtime — accepted v1 trade-off.** |
| `emit(BridgeAuditEvent)` (fire-and-forget) | No Session argument; no per-session buffer touched. Listeners that overrode `onEvent` receive the event directly. Spoofing-related failure modes (bundle-identity stamping, OSGi permission) are sage/alex territory — see Ada's bridge-design Q1. |

## 6. Public-surface delta — final (aligned with Ada's bridge-design)

| Module | File | Change |
|---|---|---|
| oak-jackrabbit-api | `org.apache.jackrabbit.api.JackrabbitSession` | Add `default String getInternalSessionId()` returning `toString()`. **Awaiting Ada sign-off.** |
| oak-jcr | `org.apache.jackrabbit.oak.jcr.session.SessionImpl` | Override `getInternalSessionId()`. No package export change. |
| oak-security-spi | `AuditEventListener` | Add `default void onEvent(List<AuditEvent>)` (per Ada §2). |
| oak-security-spi | `AuditEvents.Sink` | Add `void dispatchFireAndForget(AuditEvent)` (per Ada §2). |
| oak-security-spi | `AuditEvents.Sink` | Add `void record(String sessionId, AuditEvent)` sibling overload. **Awaiting Ada sign-off vs SessionImpl unwrap path.** |
| oak-security-spi | `AuditEvents` (static façade) | Add `static dispatchFireAndForget(AuditEvent)` + `static record(String, AuditEvent)`. |
| oak-audit-bridge-api (NEW) | `org.apache.jackrabbit.oak.audit.bridge.{AuditEventEmitter, BridgeAuditEvent, BridgeAuditDomains}` | New module, three exported types + one package-private impl. |
| oak-audit-bridge (NEW) | `org.apache.jackrabbit.oak.audit.bridge.impl.{AuditEventEmitterImpl, JackrabbitSessionAccessor, DefaultJackrabbitSessionAccessor, BridgeAuditEventAdapter}` | New module, internal impl only (no exported packages). |
| oak-security-spi | `org.apache.jackrabbit.oak.spi.security.audit.AuditEventDispatcher` | New SPI interface (see `bridge/AuditEventDispatcher.java`). Implemented by `WhiteboardAuditEventListenerRegistry`. |
Net new public Oak types (across all modules): 5 — one method on `JackrabbitSession`, one default method on `AuditEventListener`, two static methods on `AuditEvents`, three bridge-API classes (`AuditEventEmitter`, `BridgeAuditEvent`, `BridgeAuditDomains`). Plus internal types in the bridge impl module (no exports).

Net new public oak-jcr exports: **0**. The oak-jcr internals stay private; the SessionImpl override is package-private.

---

## 7. Final resolution — all questions closed

All design questions resolved by Ada per `07-bridge-design.md`:

1. **Module placement** — RESOLVED: two-module split `oak-audit-bridge-api` + `oak-audit-bridge`. Mirrors `oak-api` + `oak-core`. §1.
2. **Fire-and-forget channel** — RESOLVED: **DROPPED**. v1 is commit-attached only. The "synthesize CommitInfo lie" and "broad emit() with no producer-trust check" issues were unanswerable; non-commit signals deferred to v1.1+ Monitor SPI extensions. §2 + §7.1. No `onEvent`, no `dispatchFireAndForget`, no `AuditEventDispatcher` SPI. All of grace's earlier proposals on this branch — withdrawn.
3. **Emitter signature** — RESOLVED: `recordOnCommit(Session, BridgeAuditEvent)` + `isEnabledFor(String)`. No `emit(...)`. §3.
4. **Event type** — RESOLVED: `BridgeAuditEvent` interface with `of(domain, type, payload)` factory (rejects reserved domains). Package-private `BridgeAuditEventImpl` is the single legitimate construction path. Runtime instance-type check at emitter entry. §4.
5. **`Sink.record(String, AuditEvent)` overload** — RESOLVED: **ACCEPTED**. Grace's option D. Smaller total widening than the Root-unwrap alternative. §5.
6. **`JackrabbitSession.getInternalSessionId()` default method** — RESOLVED: **APPROVED**. Default throws `UnsupportedOperationException`. SessionImpl overrides to return `sd.getContentSession().toString()`. Javadoc text fixed by Ada (warns consumers other than the audit bridge not to call this method). §5.

## 8. Final public-surface delta (v5-final, aligned with `07-bridge-design.md`)

| Module | Change | Visibility |
|---|---|---|
| `oak-jackrabbit-api` | `+ default String JackrabbitSession.getInternalSessionId()` (default throws UOE) | exported |
| `oak-jcr` | `SessionImpl.getInternalSessionId()` override returns `sd.getContentSession().toString()` | private |
| `oak-security-spi` | `+ void AuditEvents.Sink.record(String sessionId, AuditEvent event)` | exported |
| `oak-security-spi` | `+ static void AuditEvents.record(String, AuditEvent)` | exported |
| `oak-security-spi` | `+ AuditConstants.RESERVED_DOMAINS` (canonical source-of-truth for reserved-domain enforcement) | exported |
| `oak-security-spi` | `+ AuditConstants.FORBIDDEN_PAYLOAD_KEYS` (canonical 25-entry set; shared between bridge runtime check and compile-time `AuditEventCredentialFieldsTest`) | exported |
| `oak-security-spi` | `+ default Optional<String> AuditEvent.getOriginBundle()` (returns `Optional.empty()` by default; bridge overrides to `Optional.of(...)`) | exported |
| `oak-audit-bridge-api` (NEW) | `AuditEventEmitter`, `BridgeAuditEvent`, `BridgeAuditDomains` (RESERVED_DOMAINS mirror) | exported |
| `oak-audit-bridge` (NEW) | impl-only (no exports): `AuditEventEmitterImpl` (ServiceScope.BUNDLE), `JackrabbitSessionAccessor`, `DefaultJackrabbitSessionAccessor`, `BridgeAuditEventAdapter` (overrides `getOriginBundle()`) | private |

Plus internal-only constants and types inside `oak-audit-bridge-api`: `BridgeAuditEventImpl` (package-private).

### v5-final design rationale (after v3→v4→v5 oscillation)

The team converged on Option A through bouncing through Option B:
- **v3 (Option A)**: `getOriginBundle()` IN; Trust Model dual-signal (originBundle + RESERVED_DOMAINS).
- **v4 (Option B)**: `getOriginBundle()` OUT (sage's composite revision withdrew it temporarily); listeners discriminate via domain alone.
- **v5 (Option A again)**: `getOriginBundle()` BACK IN (sage withdrew their composite; alex confirmed Option A independently).

The v5 dual-signal Trust Model gives listeners two independent discriminators — an XOR invariant (Oak-attested ⇔ originBundle.isEmpty() ⇔ domain ∈ RESERVED_DOMAINS) that catches misattribution bugs structurally.

### What still did NOT make v1

| Item | Status | Replacement |
|---|---|---|
| `AuditEventListener.onEvent(List<AuditEvent>)` | **NOT IN v1** | Fire-and-forget eliminated entirely; non-commit signals → v1.1+ Monitor SPI extensions (`07-bridge-design.md` §7.1) |
| `AuditEventDispatcher` SPI | **NOT IN v1** | Grace's earlier proposal; withdrawn after fire-and-forget eliminated |

### Net widening across existing modules

3 methods/constants on `oak-security-spi` (`Sink.record(String, AuditEvent)`, `AuditEvents.record(String, AuditEvent)`, `AuditConstants` with `RESERVED_DOMAINS` + `FORBIDDEN_PAYLOAD_KEYS`) + 1 default method each on `AuditEvent` (`getOriginBundle()`) and `JackrabbitSession` (`getInternalSessionId()`). Zero new oak-jcr exports. Zero `Root` leakage.

Skeletons at `.claude/oak/plans/audit-spi/03-skeleton/bridge/` reflect this v5-final design.
