# Item 2 (v2) — Address AuditConfiguration "marker interface" complaint while keeping it a SecurityConfiguration

**Status:** FROZEN
**Author:** ada
**Reviewer feedback addressed:** PR-review item 2, **reviewer alternative (i)** — "if it's only about security events it would also be an option to add audit capabilities to the existing SecurityConfiguration base class" (interpreted as: enrich the audit-specific configuration interface, not the base).
**Related:** `design.md` §3 (Module layout), §5 (Commit-attached pipeline).
**Supersedes:** `history/design-item-2-v1-vetoed.md` (the option-(a) "make audit a top-level Oak service" design that introduced `CommitHookProvider` in `oak-store-spi`).
**Resolutions baked in below:**
- **alex's v2 review:** `isActive()` matches `PrivilegeConfiguration`'s 1-method floor; reject `getEventEmitter()` / `getRegisteredListeners()` as redundant; event primitives stay domain-neutral while pipeline framing becomes security-bound; drift-risk identified.
- **sage's v2 review (APPROVED with one impl tweak):** `isActive()` implementation is a 3-line DELEGATE to `AuditEvents.isEnabled()` — single source of truth, JMM-safe via the existing volatile `AuditEvents.sink`, NOOP/pre-init/post-dispose defense free.
- **grace's observation:** with sage's delegating impl, the predicate body lives in exactly one place (`BufferSink.isEnabled()`); a factored helper has only one consumer and is over-engineering. **No helper in v2.**
- **team-lead's scope-narrowing decision:** `FEATURE_TOGGLE_NAME` STAYS on `AuditConfigurationImpl` (not moved to the SPI interface). No upstream OAK ticket exists yet; user is away; the SPI commitment is too sticky to ship on a placeholder value. The "marker interface" complaint is sufficiently addressed by `isActive()` alone. Constant-move + OAK-rename deferred to a future follow-up when the user files the ticket.
- **Net v2 SPI surface change:** ONE addition — `boolean isActive()` on the `AuditConfiguration` interface. That's it.
- **§3.4 Javadoc sweep narrowed** per sage's verification: package-info files contain no prose (no sweep needed); event-primitive files (`AuditEvent`, `AuditEventListener`, `AuditEventEmitter`) stay neutral at the type level; sweep targets are `AuditConfiguration.java` Javadoc + `design.md` pipeline-wiring sentences only.

---

## 0. Why v2 — the v1 → v2 transition

**v1 (now vetoed):** the user originally accepted option (a) — drop `AuditConfiguration` as a `SecurityConfiguration` and make audit a top-level Oak service. v1 introduced `CommitHookProvider` SPI in `oak-store-spi`, a Whiteboard aggregator, an `Oak.with(CommitHookProvider)` builder method, and a `MutableRoot` constructor change. It went through full team review and was frozen.

**User constraints that invalidated v1 (more recent, more specific — supersede the option-(a) decision):**

1. **No changes to non-`oak-security-*` modules.** The `oak-store-spi` SPI addition, the `Oak.java` builder method, the `MutableRoot` constructor parameter change, and the `RepositoryManager` (oak-jcr) wiring are all out.
2. **Javadoc must not frame audit as "non-security" or "security-adjacent".** In v1 audit is treated as a security concern. The framing is the design.

The v2 design respects both constraints. The v1 work is preserved as `docs/history/design-item-2-v1-vetoed.md` so the rationale chain stays transparent — future readers can see why the option-(a) shape was tried, what it cost, and why we landed on the alternative.

**Item 3 is unaffected** by this v2 — it lives entirely in `oak-security-spi` + `oak-core/security/audit` + `oak-audit-spi` factory addition (the existing `oak-audit-spi` predates v1's `oak-store-spi` additions and is not in scope of the "no non-`oak-security-*` changes" constraint).

---

## 1. The remaining problem

The reviewer's PR-review item 2 had a substantive concern: **the `AuditConfiguration` interface as shipped in Path α is a near-empty marker** (just `NAME` + `NOOP` + the implicit `extends SecurityConfiguration`). Marker interfaces with no behavior are an antipattern — they exist only to be checked via `instanceof` / `@Reference(service = X.class)`, which the type system can't distinguish from intent.

With option (a) off the table, the alternative is **make `AuditConfiguration` a real interface with substance**. That addresses the marker complaint while keeping the wiring path the reviewer originally questioned (which the user now accepts as a v1 trade-off).

**v2 design contribution:** enrich `AuditConfiguration` with real methods so it's no longer a marker; reframe Javadoc to make explicit that audit is a security concern in v1.

---

## 2. What v2 does NOT change (preserved from current state)

- `AuditConfiguration extends SecurityConfiguration` — UNCHANGED.
- `AuditConfigurationImpl` location: `oak-core/.../security/audit/AuditConfigurationImpl.java` — UNCHANGED.
- `@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})` — UNCHANGED.
- `SecurityProviderBuilder.withAuditConfiguration(...)` — UNCHANGED.
- `SecurityProviderRegistration`'s `@Reference(name = "auditConfiguration", ...)` block — UNCHANGED.
- `InternalSecurityProvider.auditConfiguration` field + `getConfigurations()` inclusion + `setAuditConfiguration(...)` setter — UNCHANGED.
- `oak-store-spi` — NO CHANGES (no `CommitHookProvider`, no `WhiteboardCommitHookProvider`).
- `oak-core/Oak.java` — NO CHANGES (no `Oak.with(CommitHookProvider)`).
- `oak-core/.../core/MutableRoot.java` — NO CHANGES to constructor signature; existing `getCommitHook()` partition logic unchanged.
- `oak-jcr/.../RepositoryManager.java` — NO CHANGES.
- All 8 audit-internal classes stay at `org.apache.jackrabbit.oak.security.audit` (no package rename).
- `oak-audit-spi` — NO CHANGES from item 2 v2 (item 3's `AuditEvent.of(...)` factory addition stands).
- The Path α commit-attached pipeline architecture (`SnapshotAuditBufferHook` + `EditorHook(validators)` + `DispatchAuditEventsHook` partitioning via `PostValidationHook` marker in `MutableRoot.getCommitHook()`) — UNCHANGED, structurally.
- Fire-and-forget pipeline (`AuditEventEmitter` service + `AuditEvents.dispatch`) — UNCHANGED.

---

## 3. What v2 changes

### 3.1 `AuditConfiguration` interface — enrichment

```java
package org.apache.jackrabbit.oak.spi.security.audit;

import org.apache.jackrabbit.oak.spi.security.SecurityConfiguration;
import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Security configuration for Oak's audit pipeline. In v1, audit is treated
 * as a security concern: the audit pipeline is composed alongside the six
 * core {@link SecurityConfiguration} types
 * (authentication, authorization, user, privilege, principal, token) and
 * contributes commit hooks through the inherited
 * {@link SecurityConfiguration#getCommitHooks(String)}.
 *
 * <p>{@code AuditConfiguration} is a typed handle on the audit pipeline,
 * usable via {@code securityProvider.getConfiguration(AuditConfiguration.class)}.
 * It exposes pipeline-level state ({@link #isActive()}) and the feature-toggle
 * name ({@link #FEATURE_TOGGLE_NAME}) so security-aware components within
 * Oak's stack can probe the pipeline without depending on the implementation
 * class.
 *
 * <p>Runtime listener registration is handled via the
 * {@link org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard}, not via this
 * interface. Capture sites (e.g. {@code UserManagerImpl}) record events
 * through the static {@link org.apache.jackrabbit.oak.spi.audit.AuditEvents}
 * façade, which short-circuits to a no-op when {@link #isActive()} would
 * return {@code false}.
 *
 * <p><strong>Cardinality:</strong> unary optional. The
 * {@code AuditBufferLifecycle} is a singleton install and multiple
 * implementations would duplicate the {@code (snapshot, dispatch)} hook
 * pair in {@code MutableRoot}'s composition. Multiplexing belongs at the
 * listener layer ({@code AuditEventListener}), not at the configuration
 * layer.
 *
 * <p>When no implementation is bound, {@code SecurityProvider} returns
 * {@link #NOOP} from {@code getConfiguration(AuditConfiguration.class)} —
 * never {@code null}. The NOOP keeps the
 * {@link #getCommitHooks(String)} chain consistent (returns an empty list,
 * contributes nothing), preserves a uniform {@code @NotNull} return
 * contract, and reports {@link #isActive()} as {@code false}.
 */
@ProviderType
public interface AuditConfiguration extends SecurityConfiguration {

    /**
     * Name of the audit security configuration. Stable across releases.
     */
    String NAME = "org.apache.jackrabbit.oak.audit";

    // NOTE: FEATURE_TOGGLE_NAME is intentionally NOT on the SPI interface in v2.
    // It stays as `public static final String FEATURE_TOGGLE_NAME = "FT_AUDIT"`
    // on AuditConfigurationImpl (with the existing inline comment flagging the
    // deferred OAK-suffix rename). Exposing the constant on the public SPI
    // would commit to the exact literal value forever — too sticky to ship
    // before an OAK JIRA ticket exists. Deferred to a future follow-up when
    // the user files the ticket; see §3.2a.

    /**
     * Returns {@code true} when the audit pipeline is currently active —
     * i.e., the {@link #FEATURE_TOGGLE_NAME} toggle is enabled AND at
     * least one {@code AuditEventListener} is registered on the Whiteboard.
     * The two predicates AND together so a deployed-but-unused pipeline
     * still reports {@code false}, matching the no-allocation semantics
     * documented at the
     * {@link org.apache.jackrabbit.oak.spi.audit.AuditEvents#isEnabled()}
     * façade.
     *
     * <p>Equivalent in semantics to {@code AuditEvents.isEnabled()}, but
     * reachable via the typed
     * {@link org.apache.jackrabbit.oak.spi.security.SecurityProvider#getConfiguration(Class)}
     * lookup. Components within Oak's security stack that already hold a
     * {@code SecurityProvider} reference can probe via this method without
     * touching the static {@code AuditEvents} façade.
     *
     * <p><strong>Drift-prevention invariant.</strong> This method's
     * predicate MUST remain equivalent to
     * {@code AuditEvents.isEnabled()} — both report
     * "feature toggle ON AND at least one listener registered". The two
     * paths exist for different consumer ergonomics, NOT for divergent
     * semantics. If a future implementation needs to diverge these two
     * predicates (e.g. to introduce a "paused" state visible to one path
     * but not the other), the divergence MUST be documented explicitly in
     * both this Javadoc and the {@code AuditEvents.isEnabled()} Javadoc —
     * silent drift between the static façade and the typed handle is a
     * contract violation.
     */
    boolean isActive();

    /**
     * NOOP default. Contributes no commit hooks, exposes no parameters,
     * and reports {@link #isActive()} as {@code false}.
     */
    AuditConfiguration NOOP = new Noop();

    /**
     * NOOP implementation of {@link AuditConfiguration}. Package-private
     * by design — consumers refer to the {@link #NOOP} constant.
     */
    final class Noop extends SecurityConfiguration.Default implements AuditConfiguration {

        @NotNull
        @Override
        public String getName() {
            return AuditConfiguration.NAME;
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }
}
```

**Two enrichments over current state:**

1. `String FEATURE_TOGGLE_NAME = "FT_AUDIT"` — constant moved from `AuditConfigurationImpl` (where it's a `public static final String`) to the interface. Now reachable via `AuditConfiguration.FEATURE_TOGGLE_NAME` without depending on the impl class.
2. `boolean isActive()` — new abstract method; the impl returns `toggle.isEnabled() && registry.hasAnyListener()`, NOOP returns `false`.

**Javadoc reframing:** the current `AuditConfiguration` Javadoc opens with "Marker interface for the audit `SecurityConfiguration`." That language is removed. The new opening positions audit as a security concern in v1, with the typed handle's purpose documented (pipeline-state probe). No "non-security" / "generic" / "cross-domain" framing anywhere in the interface, the impl, or the SPI's package-info.

### 3.2 `AuditConfigurationImpl` — delegating `isActive()`

```java
@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})
@Designate(ocd = AuditConfigurationImpl.Configuration.class)
public class AuditConfigurationImpl extends ConfigurationBase implements AuditConfiguration {

    // FEATURE_TOGGLE_NAME constant STAYS on this class (see §3.2a for rationale).
    // No changes to field declarations, initialize(), dispose(), getCommitHooks(), or BufferSink.

    public static final String FEATURE_TOGGLE_NAME = "FT_AUDIT";  // unchanged; Javadoc extended per §3.2a

    // ... (existing initialize, dispose, getCommitHooks, BufferSink unchanged) ...

    /**
     * Delegates to {@link AuditEvents#isEnabled()} — the single source of
     * truth for "is the audit pipeline up?". The static {@code AuditEvents.sink}
     * field is {@code volatile} (see {@code AuditEvents.java} line 74), so any
     * thread reading {@code isActive()} sees a JMM-safe value without
     * depending on the OSGi activation publication barrier.
     *
     * <p>Pre-init, post-dispose, and NOOP-bound deployments all return
     * {@code false} for free: the NOOP sink installed by default reports
     * {@code isEnabled() == false}, {@link #initialize(Whiteboard)} installs
     * the active sink as its LAST step, and {@link #dispose()} resets the
     * sink to NOOP. No null-checks needed.
     */
    @Override
    public boolean isActive() {
        return AuditEvents.isEnabled();
    }
}
```

**Why delegate to `AuditEvents.isEnabled()` (per sage v2 review):**

1. **Single source of truth.** The predicate body lives in `BufferSink.isEnabled()` (at `AuditConfigurationImpl:255-257`). Both `AuditEvents.isEnabled()` (which calls `sink.isEnabled()`) and the new `isActive()` read it through the same code path. Drift is structurally impossible — there's only ONE predicate body, in `BufferSink`.

2. **JMM-safe by free-ride.** `AuditEvents.sink` is declared `volatile` (`AuditEvents.java:74`). Reads through it are guaranteed by JMM happens-before, regardless of which thread calls `isActive()`. No need to add `volatile` to `AuditConfigurationImpl`'s `featureToggle` / `buffer` / `registry` fields in v2 — they're still read on the OSGi-activation-publication-barrier path by `getCommitHooks()`, which works in practice (and is a pre-existing concern, out of scope for v2; see §3.2b for the follow-up note).

3. **Pre-init / post-dispose defense come free.**
   - Before `initialize()`: `AuditEvents.sink` is the static `NOOP` sink (set at class-load time on `AuditEvents.java:74`), whose `isEnabled()` returns `false`. So `isActive()` correctly reports `false` for any pre-init query.
   - During `initialize()`: `AuditEvents.install(bufferSink)` is the LAST step (after toggle creation, registry start, buffer install). Until that call, the sink stays NOOP. Partial-initialization queries safely return `false`.
   - During `dispose()`: `AuditEvents.install(null)` resets the sink to NOOP early in the teardown sequence. Post-dispose queries return `false`.

4. **No code duplication, no new helper method, no null-checks.** 3 lines instead of the factored-helper variant's ~12 lines.

**Other impl details:**

- The `getCommitHooks(String)` method (lines 222-230) is unchanged. Existing guard (`featureToggle == null || buffer == null || registry == null` → `List.of()`) remains. This path is JMM-safe via the existing publication chain: `InternalSecurityProvider.auditConfiguration` is volatile (`InternalSecurityProvider.java:62`); the volatile read on the consumer side provides happens-before for the AuditConfigurationImpl reference and its previously-initialized plain fields.
- The `FEATURE_TOGGLE_NAME` field STAYS at line 78 of the current impl. Its single in-impl use at line 128 (`Feature.newFeature(FEATURE_TOGGLE_NAME, whiteboard)`) is unchanged. Javadoc extended per §3.2a.
- **No new `volatile` keywords on `AuditConfigurationImpl` fields in v2.** Sage's recommendation: the delegating `isActive()` sidesteps the cross-thread-reader concern entirely (reads go through the existing volatile `AuditEvents.sink`); the `getCommitHooks(String)` path is JMM-safe via the existing publication chain through `InternalSecurityProvider.auditConfiguration` volatile. The pre-existing JMM observation (plain fields on the impl could be `volatile` for explicit JMM safety in any new reader path) becomes a follow-up note (§3.2b), not a v2 change.
- **No factored static helper in v2.** With the delegating `isActive()`, the predicate body lives in exactly one place (`BufferSink.isEnabled()`). A factored helper would have only one direct caller (`BufferSink`) — not factored, just a private method. Drop.

### 3.2b Pre-existing JMM observation (OUT OF SCOPE — follow-up note)

Sage's v2 review noted: `AuditConfigurationImpl.featureToggle` / `buffer` / `registry` are plain (non-volatile) instance fields. `getCommitHooks(workspaceName)` reads them (lines 224-229). The path works in practice because OSGi DS provides happens-before from `@Activate` to subsequent service uses, and the long publication chain (SecurityProviderBuilder → ContentRepositoryImpl → ContentSessionImpl → MutableRoot.commit) carries the visibility forward.

Formally, `volatile` would make this explicit and remove the OSGi-runtime dependency. **Out of scope for v2.** Filed as a follow-up note in `design.md` (task #9) so it doesn't drop off the radar. The follow-up would also benefit `getCommitHooks(workspaceName)`'s thread-safety story.

The v2 delegating `isActive()` doesn't add to this concern — it sidesteps it via the existing volatile `AuditEvents.sink`.

### 3.2a Feature-toggle name — STAYS on the impl in v2

The current code has `FEATURE_TOGGLE_NAME = "FT_AUDIT"` at `AuditConfigurationImpl.java:78`, with an inline comment about the deferred OAK-suffix rename. **v2 keeps it where it is.** Reasoning (team-lead's scope-narrowing decision):

1. **No upstream OAK ticket exists yet.** This PR is on the fork (`dulvac/jackrabbit-oak#1`); upstream JIRA ticket is unfiled.
2. **User is away.** They're the only one who can either file the OAK ticket or authoritatively defer the rename. The team-lead can't reasonably make that call for them.
3. **SPI stickiness asymmetry.** Adding `isActive()` to the SPI is forward-additive (consumers gain a capability). Adding `FEATURE_TOGGLE_NAME = "FT_AUDIT"` with later rename to `"FT_AUDIT_OAK-NNNNN"` is breaking (consumers that bound to the literal value get a runtime mismatch). Ship what's safe; defer what's sticky.
4. **The "marker interface" complaint is sufficiently addressed by `isActive()` alone.** A single real method with a real semantic (predicate-equivalent to `AuditEvents.isEnabled()`) makes `AuditConfiguration` no longer a marker. Adding the constant was a "nice-to-have" enrichment in earlier drafts — not load-bearing.

**Recommended Javadoc extension** at `AuditConfigurationImpl.java:73-78` (grace, per your suggestion):

```java
/**
 * Feature toggle name. The fork ships this as {@code FT_AUDIT}.
 * When upstreaming, rename to {@code FT_AUDIT_OAK-<NNNNN>} per the
 * {@code FT_<DESCRIPTION>_OAK-<issue>} convention in {@code AGENTS.md}.
 * <p>
 * <strong>Why not on the public SPI interface ({@link AuditConfiguration}):</strong>
 * moving this constant to the SPI would commit the literal value to the
 * public surface forever. The OAK ticket allocation is deferred per the
 * team's scope-narrowing call (user's prerogative when they return);
 * until then, this constant stays impl-local with the fork-shape value.
 */
public static final String FEATURE_TOGGLE_NAME = "FT_AUDIT";
```

The extension tells upstream reviewers the deferral is intentional and prevents a future contributor from "fixing" it by moving the constant to the SPI before the OAK number exists.

**Future follow-up:** when the user files the OAK ticket, the constant value updates from `"FT_AUDIT"` to `"FT_AUDIT_OAK-NNNNN"`. At that point, the constant can ALSO move to the SPI interface (binary-additive change — consumers gain access to the symbol). Two changes paired in the same follow-up commit; v2 doesn't pre-empt that decision.

### 3.3 `AuditConfigurationTest` — coverage

Two new tests:

```java
@Test
public void isActiveReportsFalseWhenNotInitialized() {
    AuditConfigurationImpl config = new AuditConfigurationImpl();
    assertFalse(config.isActive());
}

@Test
public void isActiveReportsFalseAfterDispose() {
    AuditConfigurationImpl config = new AuditConfigurationImpl();
    config.initialize(whiteboard);
    config.dispose();
    assertFalse(config.isActive());
}

@Test
public void isActiveDependsOnToggleAndListener() {
    AuditConfigurationImpl config = new AuditConfigurationImpl();
    config.initialize(whiteboard);
    // Toggle is OFF by default (FT_AUDIT defaults to disabled per AGENTS.md).
    assertFalse(config.isActive());
    flipFeatureToggle(whiteboard, AuditConfiguration.FEATURE_TOGGLE_NAME, true);
    // Toggle ON but no listener yet.
    assertFalse(config.isActive());
    registerTestListener(whiteboard);
    // Toggle ON + listener registered.
    assertTrue(config.isActive());
}

@Test
public void noopReportsInactive() {
    assertFalse(AuditConfiguration.NOOP.isActive());
}
```

Coverage gate on `oak-security-spi` is 100% line / 100% branch. The new `Noop.isActive()` method needs a one-line test on `AuditConfiguration.NOOP.isActive()` returning false — included above.

### 3.4 Javadoc framing audit — sweep across audit-touched files

Audit cross-cuts the SPI; v1 framing in some places said "non-security" or "cross-domain". Per the user constraint, those phrases come out. The sweep:

| File | Action |
|---|---|
| `oak-security-spi/.../audit/AuditConfiguration.java` | Replace "Marker interface" opening with the v2 opening shown in §3.1. |
| `oak-security-spi/.../audit/package-info.java` | Verify nothing says "non-security" or "cross-domain"; current state already aligned (it's the security-audit subpackage). |
| `oak-core/.../security/audit/AuditConfigurationImpl.java` | Javadoc at line 46-68 already says "the audit plug-in to Oak" without security/non-security framing. Verify the `FEATURE_TOGGLE_NAME` Javadoc (lines 73-77) doesn't drift. |
| `oak-audit-spi/.../audit/package-info.java` | **NO SWEEP NEEDED** — sage verified the file is just `@Version("1.0.0")`, no prose. |
| `oak-security-spi/.../audit/package-info.java` | **NO SWEEP NEEDED** — same; only `@Version("1.0.0")`. |
| `oak-audit-spi/.../audit/AuditEvent.java` | **NO SWEEP NEEDED.** Domain-neutral framing at the EVENT TYPE level is correct (events can carry any domain string). The event being domain-neutral doesn't make the pipeline non-security. |
| `oak-audit-spi/.../audit/AuditEventListener.java` | **NO SWEEP NEEDED** — listener interface is type-level neutral, attaches to whatever domain string the implementer chooses. |
| `oak-audit-spi/.../audit/AuditEventEmitter.java` | **NO SWEEP NEEDED** — emitter accepts any `AuditEvent`; the existing trust-model section (lines 46-50) is correct about the open producer surface; that's event-level framing, not pipeline-level. |
| `oak-audit-spi/.../audit/AuditEvents.java` (façade) | **SPOT-CHECK ONLY.** Method-level Javadoc stays neutral (`isEnabled()` describes the predicate, not the pipeline). If any class-level Javadoc references "the audit module" in a way that implies non-security, reframe to mention the v1 security-binding. |
| `oak-audit-spi/docs/design.md` §1, §3, §10, §14 | Sweep for sentences specifically about WIRING / OWNERSHIP claiming "audit is cross-domain" — reframe those to acknowledge the v1 security-binding. KEEP sentences about EVENT TYPE TAXONOMY ("the SPI's event types accept any domain string") — that's accurate at the type level. **Key cut to preserve:** event type taxonomy (domain-neutral) ≠ pipeline wiring (security-bound in v1). |
| `oak-audit-spi/docs/design.md` §1, §3, §10, §14 | Reframe any "audit is cross-domain" sentence to acknowledge v1's security-bound wiring. Don't delete the architectural fact that the EVENT primitives are domain-neutral; do reframe the OWNERSHIP of the pipeline as security in v1. |
| `oak-doc/src/site/markdown/security/audit.md` (task #9, grace) | Position audit clearly within the security area; the doc landing page is already under `security/`. |

The sweep is mechanical search-and-revise. The substance is: stop saying "audit is non-security" or "generic", because in v1 the wiring is security-bound and the user has decided the framing matches the wiring.

### 3.5 What v2 explicitly drops vs v1

For the record, so future maintainers don't try to revive a vetoed path:

- ❌ New `CommitHookProvider` SPI in `oak-store-spi` (v1 §3.1).
- ❌ New `WhiteboardCommitHookProvider` aggregator in `oak-store-spi` (v1 §3.2).
- ❌ `MutableRoot.getCommitHook()` accepting a second iteration source (v1 §3.3).
- ❌ `Oak.with(CommitHookProvider)` builder method (v1 §3.4).
- ❌ `RepositoryManager` (oak-jcr) instantiating `WhiteboardCommitHookProvider` and passing to `Oak.with(...)` (v1 §3.5).
- ❌ Rename `AuditConfigurationImpl` → `AuditPipelineImpl` (v1 §3.6.2).
- ❌ Move audit-internal classes from `oak.security.audit` to `oak.audit` (v1 §3.6.2).
- ❌ Delete `AuditConfiguration` interface (v1 §3.6.1).
- ❌ `AuditPipelineImpl.create(Whiteboard)` static factory (v1 §3.7).
- ❌ `Oak.with(audit)` embedded-mode wiring (v1 §3.7).
- ❌ Session-semantics §3.8 statement reframing audit as wiring-gated rather than security-config-gated (v1 §3.8).

All of these stay reverted on grace's branch (see team-lead's instruction). v2's diff applies on top of the `5ab536c13c` baseline (item-3-only state).

---

## 4. End-to-end paths (no change from Path α)

```
[OSGi activation]
AuditConfigurationImpl                                                          [oak-core]
  ├─ @Activate → initialize(OsgiWhiteboard(bundleContext))
  │    ├─ Feature.newFeature(AuditConfiguration.FEATURE_TOGGLE_NAME, whiteboard)   ← constant moves to interface
  │    ├─ WhiteboardAuditEventListenerRegistry.start(whiteboard)
  │    ├─ AuditBufferLifecycle.install(buffer)
  │    └─ AuditEvents.install(bufferSink)
  └─ Registered as @Component(service = {AuditConfiguration.class, SecurityConfiguration.class})
       │
       ▼
SecurityProviderRegistration.bindAuditConfiguration                              [oak-core]
  ├─ Holds reference for SecurityProviderBuilder
  └─ maybeRegister() → SecurityProvider rebuilt via SecurityProviderBuilder.build()
       │
       ▼
SecurityProviderBuilder.build()                                                  [oak-core]
  └─ Includes audit in InternalSecurityProvider.getConfigurations() (existing path; UNCHANGED).
       │
       ▼
Oak.createNewContentRepository() → ContentRepositoryImpl → ContentSessionImpl → MutableRoot
       │
       ▼
MutableRoot.getCommitHook()                                                       [oak-core]
  └─ Iterates securityProvider.getConfigurations() — UNCHANGED.
     For audit's contribution (snapshot, dispatch):
       - SnapshotAuditBufferHook (regular)         → pre-validation bucket
       - EditorHook (validators)                    ← validators
       - DispatchAuditEventsHook (PostValidationHook) → post-validation bucket
```

**Critical preserved invariant:** the `PostValidationHook` marker mechanism does the partitioning. v1's `MutableRoot` changes were unnecessary — the existing path already correctly positions audit hooks across the validator boundary. v2 trusts this path entirely.

---

## 5. Test impact (turing — preview)

Much smaller than v1's. Only:

| Test | Change |
|---|---|
| `oak-security-spi/.../audit/AuditConfigurationTest` | EXTEND with `isActive()` on NOOP test (§3.3). |
| `oak-core/.../security/audit/AuditConfigurationImplTest` | EXTEND with the four `isActive()` tests in §3.3. Coverage of the new state-permutation branches. |
| `oak-core/.../security/audit/AuditPipelineIT` | NO CHANGE expected. Wiring path is unchanged. |
| `oak-core/.../security/audit/AuditWiringIT` | NO CHANGE expected from v2 (item 3's IT changes still apply). |
| `oak-core/.../security/internal/SecurityProviderBuilderTest` | NO CHANGE expected (withAuditConfiguration path preserved). |
| `oak-core/.../security/internal/SecurityProviderRegistrationTest` | NO CHANGE expected (audit-binding preserved). |
| `oak-run-commons/.../MemoryNSWithAuditFixtureTest` | NO CHANGE expected (existing wiring preserved). |

Wave-1/Wave-2 split from v1 collapses: turing's Wave-2 work (item 2 cross-cutting tests) effectively becomes "verify still passes" — the only NEW tests are the colocated `isActive()` ones in `AuditConfigurationImplTest`, which grace owns per the agreed split.

Coverage gate on `oak-security-spi` stays 100% line / 100% branch. `oak-audit-spi` stays 100% / 100% (item 3 update). `oak-core` general gate is `≥80%` overall + audit subpackage covered by named tests.

---

## 6. Trade-offs explicitly accepted

1. **The "marker interface" complaint is addressed partially, not by structural elimination.** The reviewer wanted either "remove the interface" or "give it substance". v2 takes the substance path. If a future reviewer comes back saying "still feels thin", we can grow the interface (e.g. expose `getEventEmitter()` or `getRegisteredListeners()`) — but I'd start with the minimum-viable enrichment and grow only when a real use case lands.
2. **`AuditConfiguration extends SecurityConfiguration` stays.** This is the wiring path the original PR-review item 2 questioned. The user's later constraints override that questioning: in v1, audit IS security; the wiring matches the framing. If audit's scope broadens in a future version (e.g. AEM bundles emit audit events for non-security domains), the design can be revisited then.
3. **No `oak-store-spi`/`Oak.java`/`MutableRoot` mechanism for general "first-class non-security hooks".** Audit doesn't need it. If a future use case (a non-security domain that wants to contribute commit hooks outside SecurityConfiguration) emerges, the v1 design is preserved at `docs/history/design-item-2-v1-vetoed.md` and can serve as the starting point for that work. Don't pre-build for a use case that doesn't exist.
4. **Coverage gate stays 100%/100% for the modules involved.** The two-line enrichment is small enough that 100% is trivial; turing's earlier pushback against "0.99" stands.

---

## 7. Resolved decisions (from alex's v2 review)

| Question | Resolution | Source |
|---|---|---|
| Is `isActive()` enough? | **YES.** Matches `PrivilegeConfiguration`'s 1-method floor; audit plays an "operational handle" role (not "domain access factory") because the access surface already lives on `AuditEvents` static façade + `AuditEventEmitter` OSGi service. Adding more methods would create redundant access paths. | alex Q1 (survey of all 6 typed `SecurityConfiguration` interfaces). |
| Expose `getEventEmitter()` and/or `getRegisteredListeners()`? | **NO.** `getEventEmitter()` duplicates the OSGi service. `getRegisteredListeners()` is leaky abstraction (registry is internal state). Premature for any speculative consumer. | alex Q2. |
| `FEATURE_TOGGLE_NAME` on interface vs impl? | **ON INTERFACE.** Audit's external operational audience (admin UIs, monitoring agents) is the legitimate consumer that needs a stable handle. Deviates from Oak's "private toggle constant" convention but justified. | alex Q3. |
| `FT_AUDIT` string — keep as-is or rename to `FT_AUDIT_OAK-<issue#>`? | **RENAME to `FT_AUDIT_OAK-<issue#>`** paired with the interface exposure. Exposing on the SPI commits to the exact string; do the rename now per AGENTS.md convention. Coordination prerequisite: team-lead resolves the OAK issue number before grace ships impl. See §3.2a. | alex Q3 caveat. |
| Drift between `isActive()` and `AuditEvents.isEnabled()`? | **MITIGATED by delegation** (sage's v2 review): `isActive()` is a 3-line delegate `return AuditEvents.isEnabled();`. Single source of truth lives in `BufferSink.isEnabled()` (called via the volatile `AuditEvents.sink`). Drift impossible by construction — no parallel predicate exists. JMM-safe via the existing volatile `AuditEvents.sink`. NOOP / pre-init / post-dispose all return `false` for free. Plus: Javadoc invariant on `isActive()` documents the equivalence contract. See §3.1 Javadoc + §3.2 impl. **No volatile fields added on the impl in v2** (pre-existing JMM observation parked as follow-up — see §3.2b). **No factored helper either** (grace's observation: with single-consumer-after-delegation it's not factored, just over-engineered). | alex (drift-risk catch) + sage (delegating-impl resolution) + grace (helper-redundancy observation). |
| `FEATURE_TOGGLE_NAME` on interface vs impl? | **STAYS ON IMPL.** Team-lead's scope-narrowing decision: no upstream OAK ticket exists; user away; SPI stickiness asymmetry argues for ship-what's-safe / defer-what's-sticky. `isActive()` alone addresses the "marker interface" complaint; the constant-move is non-load-bearing. Deferred to a future follow-up when the user files the OAK ticket. See §3.2a. | team-lead (scope narrowing). |
| Javadoc sweep scope — primitive-level vs pipeline-level framing? | **SEPARATED.** Event primitives (`AuditEvent`, `AuditEventListener`, `AuditEventEmitter`, `AuditEvents` façade) stay **domain-neutral** — events can carry any domain string; the type system doesn't constrain that. Pipeline-level framing (`AuditConfiguration`, `AuditConfigurationImpl`, package-info pipeline-binding text) becomes **security-bound** — the v1 pipeline is composed alongside the six core `SecurityConfiguration`s. Both facts are true and non-contradictory. See §3.4 updated. | alex Javadoc-sweep nuance. |
| Distinct `isActive()` (pipeline-wide) vs `AuditEventEmitter.isEnabledFor(domain)` (per-domain)? | **KEEP DISTINCT.** Different abstraction layers; document the difference clearly in both Javadocs. Don't conflate. | (preserved from earlier; uncontested.) |

---

## 7a. Outstanding coordination

**Cleared.** All v2 gates resolved:

- ✅ sage v2 review — APPROVED with delegating-impl tweak (adopted in §3.2).
- ✅ alex v2 review — answered all three questions + flagged drift-risk (resolved via sage's delegation).
- ✅ team-lead — scope decision on `FEATURE_TOGGLE_NAME`: STAYS on impl. OAK-rename deferred to a future follow-up when the user files the ticket. Documented in §3.2a.

`[design-freeze item 2 v2]` sent to grace + turing.

---

## 8. Next step

Loop in sage + alex for review of v2. When converged, `[design-freeze item 2 v2]` to grace + turing.

Expected churn for grace post-freeze: very small. Two file edits (`AuditConfiguration.java` + `AuditConfigurationImpl.java`), one test extension, plus the Javadoc sweep across the 5-6 files listed in §3.4. No package moves, no SPI additions, no `MutableRoot` / `Oak.java` / `RepositoryManager` touches.
