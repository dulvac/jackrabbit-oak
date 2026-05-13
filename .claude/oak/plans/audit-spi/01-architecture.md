# Audit SPI — Architecture (Path α)

Author: ada (architect)
Status: draft for team-lead consolidation
Scope: this file covers only the architectural decisions. Source-level validation lives in `02-oak-validation.md` (alex), code skeleton in `03-skeleton/` (grace), tests/benchmarks in `04-tests-and-benchmarks.md` (turing), background research in `05-research-notes.md` (shannon).

All citations are against trunk as of the design-brief snapshot (`00-design-brief.md`). Every claim below is grounded in a file:line reference; no hand-wave.

---

## 1. SPI placement — `org.apache.jackrabbit.oak.spi.security.audit` under `oak-security-spi`

**Decision: confirmed.** The new package lives in `oak-security-spi`.

### Why oak-security-spi (and not oak-core-spi or a new module)

The audit SPI is, by design, a `SecurityConfiguration` extension: capture sites live in oak-security domains (user/group, ACL, token, CUG, …); the dispatch hooks compose with the rest of the security hook chain via `SecurityConfiguration.getCommitHooks(workspaceName)` (`oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/SecurityConfiguration.java:88`).

The "security-only" framing is enforced by the lifecycle: `MutableRoot.getCommitHook()` iterates `securityProvider.getConfigurations()` and pulls hooks from each `SecurityConfiguration` (`oak-core/src/main/java/org/apache/jackrabbit/oak/core/MutableRoot.java:290-300`). Anything not implementing `SecurityConfiguration` does not participate. Putting the SPI in a non-security module would force a parallel registration path; that violates separation of concerns and we'd be re-inventing the security hook chain for one use case.

A separate `oak-audit-spi` module was considered and rejected: cross-module circulars would appear immediately because `AuditEventListener` consumes oak-store-spi types (`NodeState`, `CommitInfo`) and the producer side lives in oak-security types. The current `oak-security-spi` already pulls in both (`oak-security-spi/pom.xml:127-131` depends on `oak-store-spi`) and is the natural home.

### Package surface to export

`oak-security-spi/pom.xml:48-66` lists `<Export-Package>` entries. The new subpackage must be added:

```xml
<Export-Package>
  org.apache.jackrabbit.oak.plugins.tree,
  org.apache.jackrabbit.oak.spi.security,
  org.apache.jackrabbit.oak.spi.security.audit,   <!-- NEW -->
  ...
</Export-Package>
```

The new package follows the existing `spi.security.<topic>` convention (`authentication`, `authorization`, `principal`, `privilege`, `user`, …) — see `oak-security-spi/pom.xml:51-65`. No baseline regression: this is purely additive.

### Annotation conventions in the new package

Existing convention from `oak-security-spi`:
- `SecurityConfiguration` uses `@ProviderType` (`oak-security-spi/.../SecurityConfiguration.java:29,39`) — implementors are Oak, consumers are third parties.
- The audit SPI must distinguish:
  - `AuditEvent` → `@ProviderType` (Oak ships event subclasses; third parties only consume).
  - `AuditEventListener` → `@ConsumerType` (third parties implement listeners; Oak invokes them).
  - `AuditEvents` / `AuditBufferLifecycle` (static façades) → no annotation; they are not interfaces.

Decision: enforce `@ConsumerType` on `AuditEventListener` — listener API growth must be backward-compatible for implementers, not for consumers.

### `AuditConfiguration` as a typed SPI marker interface

**Decision (resolves alex's structural blocker in `02-oak-validation.md` §7)**: `AuditConfiguration` is a typed sub-interface of `SecurityConfiguration` exported from `oak-security-spi` — **not** an impl-only `@Component(service = SecurityConfiguration.class)` as the design brief originally sketched.

Why this is required (not optional):

`SecurityProviderRegistration.java:229-318` binds only six concrete typed `@Reference`s: `AuthenticationConfiguration`, `PrivilegeConfiguration`, `UserConfiguration` (unary mandatory), and `AuthorizationConfiguration`, `PrincipalConfiguration`, `TokenConfiguration` (multiple dynamic). There is no generic `SecurityConfiguration.class` binding. An `@Component(service = SecurityConfiguration.class)` would float unconsumed — `InternalSecurityProvider.java:91-102` hardcodes those exact six fields in `getConfigurations()` and would not include an audit slot. `MutableRoot.getCommitHook()` iterates that hardcoded set; audit hooks would never enter the chain.

The fix is a 7th typed slot in the registration pattern. To bind via OSGi DS `@Reference`, that slot needs a unique service type — i.e., a typed sub-interface:

```java
// oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/AuditConfiguration.java
@ProviderType
public interface AuditConfiguration extends SecurityConfiguration {
    /** SecurityConfiguration.getName() returns this constant. */
    String NAME = "org.apache.jackrabbit.oak.audit";

    /** No-op singleton used as the default slot value in InternalSecurityProvider. */
    AuditConfiguration NOOP = new Noop();

    // No methods in v1 — pure marker.

    /** Default implementation used when no AuditConfiguration is bound. */
    final class Noop extends SecurityConfiguration.Default implements AuditConfiguration {
        @Override public @NotNull String getName() { return NAME; }
        // inherits empty getCommitHooks/getValidators/etc. from SecurityConfiguration.Default
    }
}
```

**Marker-only — no v1 methods (confirmed with alex).** The listener registry is internal machinery of the impl; production code outside oak-core has no reason to reach into it. Exposing a `getListenerRegistry()` would invite third-party coupling to internal aggregation state. Symmetry note: `SecurityConfiguration` itself only declares the contract methods (`getName`, `getCommitHooks`, …); all six existing sub-interfaces add domain-specific *production* APIs (e.g., `AuthorizationConfiguration.getPermissionProvider`, `UserConfiguration.getUserManager`) — those serve real callers. Audit has no analog: capture is via the static `AuditEvents` façade; listener discovery is via Whiteboard; dispatch is internal. The interface stays marker-only.

**NoOp default — uniform `@NotNull` contract**: `AuditConfiguration.NOOP` is the SPI's null-object. `InternalSecurityProvider.auditConfiguration` is initialized to it (not null); the bind setter coerces null to NOOP. Benefits over a null-allowing field:

- Uniform `@NotNull` contract on `InternalSecurityProvider.getConfiguration(AuditConfiguration.class)` — callers never need null guards.
- `getConfigurations()` aggregation is unconditional — the NoOp is always included; it just contributes `emptyList()` from `getCommitHooks(...)`.
- One less branch in `MutableRoot.getCommitHook()` (it iterates configurations without a null check on audit).
- Cost: one always-iterated config returning empty lists from each `getCommitHooks/getValidators/…` call. Unmeasurable.

This mirrors Oak's established null-object pattern (e.g., `BlobAccessProvider.DEFAULT_BLOB_ACCESS_PROVIDER` referenced from `UserConfigurationImpl.java:244`). The nested `Noop` class lives in the SPI alongside the interface (analog to `SecurityConfiguration.Default` from which it extends), so embedders don't need to construct one themselves.

For testability, the impl can expose package-visible accessors or `@VisibleForTesting` getters on `AuditConfigurationImpl`. No SPI exposure.

### Listener ordering — hard SPI contract (not a Whiteboard delegation)

**This was an open question in the design brief; shannon's research (`05-research-notes.md`) closes it: Whiteboard ranking is *not* portable across the two impls.**

- `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/spi/whiteboard/DefaultWhiteboard.java:35-64`: services are stored in an identity `HashSet` and returned by `tracker.getServices()` without sort and without reading any `service.ranking` property. Arbitrary order.
- `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/osgi/OsgiWhiteboard.java:181-194`: uses a `TreeMap<ServiceReference, T>` whose natural ordering is `service.ranking` descending (OSGi Core spec §5.2.5).

**Consequence**: `WhiteboardAuditEventListenerRegistry` cannot delegate ordering to the underlying Whiteboard. Standalone tests and embedded Oak would see arbitrary order; OSGi production would see ranked order. That divergence is unacceptable for an audit pipeline (test what you ship).

**Decision (SPI requirement)**:

1. `AuditEventListener` declares `default int getRank() { return 0; }`. Higher rank → invoked first.
2. `WhiteboardAuditEventListenerRegistry.dispatch(...)` always applies an explicit sort:
   ```java
   listeners.sort(Comparator.comparingInt(AuditEventListener::getRank).reversed());
   ```
   `List.sort` is stable, so ties preserve underlying Whiteboard order.
3. OSGi listener implementations should register with `service.ranking = N` **and** override `getRank()` to return the same `N`. The redundancy is intentional — `service.ranking` controls Whiteboard tracking attributes for any other consumer; `getRank()` is what the audit dispatcher reads. Both must agree to avoid surprises if the consumer ever changes.

This converts the design brief's "fallback if not natively supported" into a hard, single-path SPI contract. No conditional ordering code in the registry.

### Greenfield namespace (no prior Oak audit SPI)

Shannon confirms: trunk has no prior audit SPI. The only audit-adjacent code is `OAK-2516` (2015), a single SLF4J text logger. **There is no deprecated API to maintain compatibility with**, so `org.apache.jackrabbit.oak.spi.security.audit.*` is a clean namespace with no backward-compat constraint. Future evolution of the SPI is bound only by `@ProviderType` / `@ConsumerType` discipline (see above), not by legacy interface shapes.

### `AuditEvent.payload()` contract — non-null values, absent keys for optional fields

**Decision (resolves grace's Q1)**: payload map values are **never null**; optional fields are represented by **omitting the key**, not by a null value or empty-string sentinel.

Three concrete rules:

1. **Factory**: `AuditEvent` implementations use `Map.of(...)` (Java 9+ canonical immutable map factory). `Map.of` rejects null values at construction time — this is the type system enforcing the contract for free, no runtime guard needed.

2. **User-ID fields** (e.g., `MemberAddedEvent.performedBy`) use Oak's existing `OAK_UNKNOWN` sentinel (`"oak:unknown"`) for system-session commits. This value is sourced from `CommitInfo.getUserId()` which is already never null per Oak's contract (see alex's validation in `02-oak-validation.md`: `CommitInfo.java:94`). **Empty string is not a permitted user-ID value** — it would conflate "unspecified" with "explicit empty", which is unsemantic. Grace's current `""` substitution in `MemberAddedEvent`/`MemberRemovedEvent` is dead code (the source is never null) and should be removed.

3. **Genuinely optional fields** (future event types — e.g., an ACL event with an optional restriction): **omit the key from the payload map** rather than include it with a sentinel value. Listeners use `payload.containsKey(k)` or `payload.getOrDefault(k, default)` to handle absence.

Rationale:
- Listener code stays simple: `payload.get("performedBy")` always returns a non-null `String` for events that declare it. No `instanceof String` checks, no null guards on values.
- Matches Oak's existing pattern: `CommitInfo` uses `OAK_UNKNOWN` for user IDs and never returns null. We extend that pattern, not invent a new one.
- Optional fields via absent-key (rather than sentinel) lets listeners distinguish "this event type doesn't carry that field" from "this event carries that field but it's unset". Sentinel values collapse both into the same representation.
- `Map.of` factory enforces the contract at the wire, not in documentation.

Listener Javadoc must call out: "Payload map values are never null. Optional fields are absent from the map; use `containsKey` or `getOrDefault` to check."

---

## 2. `AuditConfigurationImpl` — OSGi component pattern

**Canonical reference**: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/user/UserConfigurationImpl.java`.

(Class renamed from `AuditConfiguration` to `AuditConfigurationImpl` per the §1 decision to promote the marker interface `AuditConfiguration` to oak-security-spi. The impl class follows the `*Impl` convention used everywhere else in oak-core/.../security/ — `UserConfigurationImpl`, `AuthorizationConfigurationImpl`, `TokenConfigurationImpl`, etc.)

### Component declaration

Follow `UserConfigurationImpl.java:78-80` — register under both the typed sub-interface AND `SecurityConfiguration`:

```java
@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})
@Designate(ocd = AuditConfigurationImpl.Configuration.class)
public class AuditConfigurationImpl extends ConfigurationBase implements AuditConfiguration {
    ...
}
```

Notes:
- Dual service registration matches `UserConfigurationImpl:78` (`UserConfiguration.class, SecurityConfiguration.class`). The typed binding (`AuditConfiguration.class`) is what `SecurityProviderRegistration`'s new `@Reference` discriminates on; the `SecurityConfiguration.class` binding lets non-registration consumers track the configuration as a generic security service.
- Inherit from `ConfigurationBase` (used by `UserConfigurationImpl:80,213-219`) so that the SecurityProvider can wire parameters in non-OSGi mode (`getSecurityProvider().getParameters(NAME)`).

### Activation

`UserConfigurationImpl.java:225-231` is the template:

```java
@Activate
private void activate(Configuration configuration, BundleContext bundleContext, Map<String, Object> properties) {
    setParameters(ConfigurationParameters.of(properties));
    Whiteboard whiteboard = new OsgiWhiteboard(bundleContext);

    // 1. Toggle (see §3)
    this.feature = Feature.newFeature(FT_AUDIT, whiteboard);   // FT_AUDIT on the fork; rename to FT_AUDIT_OAK-<NNNNN> when upstreaming

    // 2. Listener tracker (lifetime tied to component, not to a single commit)
    this.listenerRegistry = new WhiteboardAuditEventListenerRegistry(whiteboard);

    // 3. Buffer + lifecycle hook installation
    this.buffer = new AuditBuffer();
    AuditBufferLifecycle.install(buffer);
}
```

The order matters: toggle → registry → buffer → install. Reason: if `install(buffer)` fired first, a concurrent `MutableRoot.commit()` on another thread could observe a buffer that has no toggle backing yet, then check `feature.isEnabled()` against a null reference. The toggle and registry must be initialized before publishing the buffer via `install(...)`.

### Deactivation

The deactivation order has different correctness goals than activation: stop new captures **first** (toggle off), then unwire dispatch machinery, then drain residual state. A literal "reverse of activate" order would leave the toggle ON after the buffer is unpublished — captures would still fire, write to a buffer that's about to be cleared, and produce a window of guaranteed event loss.

```java
@Deactivate
private void deactivate() {
    // 1. Stop new captures FIRST. AuditEvents.isEnabledFor(...) reads the toggle;
    //    once closed, capture sites short-circuit with no event allocation.
    if (feature != null) {
        feature.close();
    }
    // 2. Stop listener discovery. dispatch hook sees an empty listener list from now on.
    if (listenerRegistry != null) {
        listenerRegistry.stop();
    }
    // 3. Unpublish the buffer. MutableRoot lifecycle calls (onCommitFailed/onRefresh)
    //    now route through the NOOP, not a half-torn-down AuditBuffer.
    AuditBufferLifecycle.install(null);
    // 4. Best-effort drain of the deactivator-thread ThreadLocal entry.
    //    See "Residual ThreadLocal leak" below.
    if (buffer != null) {
        buffer.clearAll();
    }
}
```

The toggle-close pattern follows `DocumentNodeStoreService.java:723-724` (which uses `closeFeatures(...)`). The ordering above ensures:

- After step 1, capture sites short-circuit on `isEnabledFor()` → no new `ThreadLocal` entries are created.
- After step 2, `DispatchAuditEventsHook` (if still in a chain on an in-flight commit attempt) sees an empty listener list → invocation is a no-op.
- After step 3, any concurrent `MutableRoot.commit()/refresh()/rebase()` lifecycle calls go to the NOOP listener.
- Step 4 cleans the deactivator's ThreadLocal — see residual note below.

### Residual ThreadLocal leak on deactivation (acknowledged trade-off)

Per alex's `02-oak-validation.md` follow-up: at deactivation time, `buffer.clearAll()` runs on the *deactivator* thread and can only directly clear entries reachable from that thread's `ThreadLocal`. Other threads' `ThreadLocal` maps holding pre-deactivation per-session entries are unreachable from the deactivation thread.

After step 1, those orphan entries stop receiving new events. They are cleaned up indirectly on subsequent events on the same thread:
- Next `MutableRoot.commit()` from the same thread: hooks are no longer composed (because `AuditConfiguration` is unbound from `SecurityProviderRegistration`), so `SnapshotAuditBufferHook` does not run; events remain in the ThreadLocal.
- Next `Root.refresh()`/`rebase()` from the same thread: the lifecycle call goes to NOOP; events remain.
- When the thread dies (worker-pool churn) or the `ContentSession` is GC'd (and with it the `sessionId` key, if held weakly): entries become unreachable.

**Impact bound**: residual heap is `O(#in-flight sessions × #threads with captured events × bytes-per-event)`. In a steady-state production deployment this is bounded by the worker-pool size × in-flight session count. Not unbounded.

**Mitigations (impl detail for grace, not architectural — flagged in `03-skeleton`)**:
- Use weak references for the `Map<sessionId, List<AuditEvent>>` values, keyed by something that GC can release when the session closes. Adds complexity for a low-probability cost.
- Or accept the residual. Recommended for v1 — deactivation is rare (bundle stop/refresh), and the leak is bounded.

### Registration binding (the 7th slot)

To make the OSGi `@Reference` actually fire, `SecurityProviderRegistration` and `InternalSecurityProvider` and `SecurityProviderBuilder` all need a new audit slot. Detail belongs in `03-skeleton/` (grace), but the architectural shape is:

1. **`oak-core/.../security/internal/SecurityProviderRegistration.java`**: new `@Reference` block following the pattern at `:278-290`:

```java
@Reference(
        name = "auditConfiguration",
        service = AuditConfiguration.class,
        cardinality = ReferenceCardinality.OPTIONAL,
        policy = ReferencePolicy.DYNAMIC
)
public void bindAuditConfiguration(AuditConfiguration configuration) {
    synchronized (this) {
        this.auditConfiguration = configuration;
    }
    maybeRegister();
}

public void unbindAuditConfiguration(AuditConfiguration configuration) {
    synchronized (this) {
        if (this.auditConfiguration == configuration) {
            this.auditConfiguration = null;
        }
    }
    maybeRegister();
}
```

**Cardinality decision (confirmed with alex)**:
- **UNARY** (not MULTIPLE): audit is a singleton resource at the SPI level. One `AuditBuffer`, one `WhiteboardAuditEventListenerRegistry`, one set of lifecycle hooks. Multiple `AuditConfiguration`s would compete for `AuditBufferLifecycle.install(...)` — last writer wins, earlier installs' `onCommitFailed`/`onRefresh` callbacks silently lost. Authorization/Principal/Token are MULTIPLE because they contribute parallel hooks/validators with composite AND/OR semantics; audit has no analog (domain multiplexing lives at the *listener* layer via Whiteboard, not the configuration layer).
- **OPTIONAL** (not MANDATORY): audit absence is non-fatal. Repo functions, no events captured. Auth/Privilege/User are MANDATORY because absence breaks login/auth.
- **DYNAMIC** policy: hot-swap audit add/remove without tearing down the SecurityProvider. `OPTIONAL+DYNAMIC` precedent already exists in `UserConfigurationImpl.java:238` (`@Reference(... cardinality = OPTIONAL, policy = DYNAMIC)` on `BlobAccessProvider`), so this is not a new pattern in oak-core.

2. **`oak-core/.../security/internal/InternalSecurityProvider.java`**: new `volatile AuditConfiguration auditConfiguration = AuditConfiguration.NOOP` field (initialized to NoOp, never null), setter that coerces null-bind to NOOP, `getConfigurations()` unconditionally includes audit (cheap when NoOp — see §1 "NoOp default"), `getConfiguration(Class)` dispatches on `AuditConfiguration.class` with `@NotNull` return. `volatile` is required because OSGi DS DYNAMIC binds/unbinds on a service-tracker thread, not the field's reader thread.

3. **`oak-core/.../security/internal/SecurityProviderBuilder.java`**: new `withAuditConfiguration(AuditConfiguration)` method following the existing `withXyzConfiguration(...)` builder pattern at `:152-233`. Build-time wiring is optional — when not called, the slot remains null and the OSGi `@Reference` binds dynamically.

4. **`oak-core/.../security/SecurityProviderImpl.java`** (deprecated, line 65): **skipped**. Alex's search confirmed zero non-test consumers across the entire repository. Embedded callers still constructing `SecurityProviderImpl` directly must migrate to `SecurityProviderBuilder.newBuilder()` to get audit. The deprecation pre-dates this work; this PR does not extend its surface.

### Metatype configuration

Minimal config surface for v1; following `UserConfigurationImpl.java:82-203`:

```java
@ObjectClassDefinition(name = "Apache Jackrabbit Oak AuditConfiguration")
@interface Configuration {
    @AttributeDefinition(
        name = "Listener invocation timeout (ms)",
        description = "Soft budget logged when a listener exceeds this duration. 0 disables timing.")
    long listenerTimeoutMs() default 0L;

    @AttributeDefinition(
        name = "Drop events on listener exception",
        description = "If true (default), a listener throwing only logs; other listeners still run.")
    boolean isolateListenerExceptions() default true;
}
```

We intentionally do **not** expose the toggle state as a metatype property: the toggle is flipped via Whiteboard at runtime (`FeatureToggle.setEnabled(boolean)` — `oak-core-spi/.../spi/toggle/FeatureToggle.java:66`), not via OSGi config. Mixing the two configuration channels is a footgun.

### SecurityConfiguration method overrides

```java
@Override @NotNull
public String getName() { return NAME; }

@Override @NotNull
public List<? extends CommitHook> getCommitHooks(@NotNull String workspaceName) {
    if (feature == null || !feature.isEnabled()) {
        return Collections.emptyList();       // toggle off → no hooks installed
    }
    return List.of(new SnapshotAuditBufferHook(buffer),
                   new DispatchAuditEventsHook(listenerRegistry));
}
```

The pattern mirrors `AuthorizationConfigurationImpl.java:160-164`:

```java
public List<? extends CommitHook> getCommitHooks(@NotNull String workspaceName) {
    return List.of(new VersionablePathHook(workspaceName, this),
                   new PermissionHook(workspaceName, getRestrictionProvider(), this));
}
```

`DispatchAuditEventsHook` implements `PostValidationHook` (`oak-store-spi/.../PostValidationHook.java:24`). `MutableRoot.getCommitHook()` already partitions on that interface (`MutableRoot.java:292-294`) — no special wiring needed.

---

## 3. Feature toggle lifecycle

**SPI**: `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/spi/toggle/Feature.java:41-83`.

`Feature.newFeature(name, whiteboard)` (line 62-67) registers a `FeatureToggle` on the Whiteboard via `whiteboard.register(FeatureToggle.class, adapter, emptyMap())`. The returned `Feature` is `Closeable`; `close()` (line 80-82) unregisters from the Whiteboard. State is backed by `AtomicBoolean` (line 43 + `FeatureToggle.java:33,66`) — thread-safe by construction, no synchronization needed at the call site.

### Reference implementations in trunk

1. **`oak-core/src/main/java/org/apache/jackrabbit/oak/Oak.java:810`** — single-toggle registration during repository construction:
   ```java
   newFeature("FT_CLASSIC_MOVE_OAK-10147", whiteboard)
   ```
   This toggle has no `close()` call in the immediate context — its lifetime is the `ContentRepositoryImpl` instance.

2. **`oak-core/src/main/java/org/apache/jackrabbit/oak/Oak.java:580-591`** — query-engine toggles registered with `closer.register(feature)` for unified shutdown. Three toggles registered together (`FT_SORT_UNION_QUERY_LEGACY_MODE`, `FT_OPTIMIZE_XPATH_UNION`, `FT_IGNORE_LIMIT_IN_INDEX_SELECTION`).

3. **`oak-store-document/src/main/java/org/apache/jackrabbit/oak/plugins/document/DocumentNodeStoreService.java:319-326`** — multi-toggle OSGi activation pattern (closest analog to ours):
   ```java
   prefetchFeature = Feature.newFeature(FT_NAME_PREFETCH, whiteboard);
   docStoreThrottlingFeature = Feature.newFeature(FT_NAME_DOC_STORE_THROTTLING, whiteboard);
   ...
   ```
   Paired with deactivation at `DocumentNodeStoreService.java:713-724`:
   ```java
   @Deactivate
   protected void deactivate() {
       ...
       closeFeatures(prefetchFeature, docStoreThrottlingFeature, ...);
   }
   ```
   This is the canonical OSGi pattern we follow.

### Naming

On the author's fork (this iteration): `FT_AUDIT`. Define the constant on `AuditConfigurationImpl`:

```java
public static final String FEATURE_TOGGLE_NAME = "FT_AUDIT";
```

When upstreaming, rename to `FT_AUDIT_OAK-<NNNNN>` per `AGENTS.md` "Feature Toggles" (the project's `FT_<DESCRIPTION>_OAK-<issue>` convention).

Default state: **disabled** — this is a new feature, not a bug fix (`AGENTS.md` rule).

### Hot-path check — two-level: toggle + per-domain listener presence

**Decision (resolves grace's Q2)**: the `AuditEvents` façade exposes **both** `isEnabled()` (global toggle check) **and** `isEnabledFor(String domain)` (per-domain listener-presence check). Capture sites MUST use `isEnabledFor(domain)` to short-circuit before allocating event objects.

```java
public final class AuditEvents {
    private static volatile Feature feature;                      // installed by AuditConfigurationImpl.activate
    private static volatile WhiteboardAuditEventListenerRegistry registry;

    /** Global toggle — cheap; first gate. */
    public static boolean isEnabled() {
        Feature f = feature;
        return f != null && f.isEnabled();                       // volatile read + AtomicBoolean.get()
    }

    /** Per-domain — short-circuits before event allocation when no listener for the domain. */
    public static boolean isEnabledFor(@NotNull String domain) {
        if (!isEnabled()) return false;
        WhiteboardAuditEventListenerRegistry r = registry;
        return r != null && r.hasListenerFor(domain);            // volatile read + Set.contains() on cached Set<String>
    }

    public static void record(@NotNull Root root, @NotNull AuditEvent event) { ... }
}
```

`hasListenerFor(domain)` reads a cached `volatile Set<String>` rebuilt by the registry whenever the Whiteboard tracker fires an add/remove. Cost: 2 volatile reads + 1 `Set.contains(String)`. No allocation. No lock.

Capture-site discipline (mandatory pattern, enforced by Risk 3 mitigation 2):

```java
// oak-core/.../security/user/MembershipProvider.java
if (AuditEvents.isEnabledFor(SecurityAuditDomain.NAME)) {
    AuditEvents.record(root, MemberAddedEvent.of(
            groupTree.getPath(), newMemberTree.getPath(),
            root.getContentSession().getAuthInfo().getUserID()));
}
```

The `if` gate runs first — when no listener is registered for `"security"`, the `MemberAddedEvent.of(...)` factory is never invoked, no event object is allocated, no `Map.of(...)` payload is built. This is the "no-listener fast path" performance contract documented in §"Performance constraints" of the design brief.

Reasoning on the static volatile: this is the same fast-path discipline the `CLASSIC_MOVE` system-property pattern uses in `MutableRoot.java:142-143` (`static final boolean CLASSIC_MOVE`). The audit statics must be volatile because, unlike `CLASSIC_MOVE`, they are set after class load (in `@Activate`). The toggle's internal `AtomicBoolean.get()` does its own ordering, so the visible ordering is: volatile read on `feature` → atomic read on the `AtomicBoolean` → volatile read on `registry` → volatile read on cached `Set<String>` → `Set.contains`. All lock-free.

`isEnabled()` (without domain) remains in the SPI as a coarse-grained check usable when the caller is domain-agnostic (e.g., generic framework code routing into multiple domains). For domain-specific capture sites, **always prefer `isEnabledFor(domain)`** — it includes the toggle check, so callers never need to chain both.

### Toggle-off behavior (zero hot-path cost)

When toggle is off:
- `AuditEvents.record(...)` returns immediately on the `isEnabled()` short-circuit. No buffer access. No allocation.
- `AuditConfiguration.getCommitHooks(workspaceName)` returns `Collections.emptyList()` (see §2). Two hooks are not even added to the chain. `MutableRoot.getCommitHook()` builds a `CompositeHook` without them.
- `MutableRoot`'s three new lifecycle calls (`onCommitFailed`, `onRefresh` × 2) hit the NOOP `Listener` baked into `AuditBufferLifecycle.java` (design brief §α, lines 99-110). NOOP method body is empty — JIT inlines to nothing.

Cumulative cost when audit is fully disabled or undeployed: bounded by three NOOP virtual calls per `commit`/`refresh`/`rebase`. On modern JVMs with class-hierarchy analysis: ~zero.

---

## 4. Module dependency graph

### Existing edges (verified)

- `oak-core → oak-security-spi`: `oak-core/pom.xml:261-265`.
- `oak-core → oak-store-spi`: `oak-core/pom.xml:283-287`.
- `oak-security-spi → oak-core-spi`: `oak-security-spi/pom.xml:122-126` (for `Whiteboard`, `Feature`, `FeatureToggle`).
- `oak-security-spi → oak-store-spi`: `oak-security-spi/pom.xml:127-131` (for `CommitHook`, `CommitContext`, `PostValidationHook`, `CommitInfo`, `NodeState`).
- `oak-core-spi exports org.apache.jackrabbit.oak.spi.toggle`: `oak-core-spi/pom.xml:58`.
- `oak-security-spi exports org.apache.jackrabbit.oak.spi.security`: `oak-security-spi/pom.xml:50`.

### New edges

**None.** The new package `org.apache.jackrabbit.oak.spi.security.audit` lives inside oak-security-spi. The `AuditBufferLifecycle` static façade is in that package and used by `MutableRoot` via an existing oak-core → oak-security-spi edge (`MutableRoot.java` already imports from `org.apache.jackrabbit.oak.spi.security.*` — see `MutableRoot.java:62-66`).

The diff to `MutableRoot.java` adds exactly one import:

```java
import org.apache.jackrabbit.oak.spi.security.audit.AuditBufferLifecycle;
```

No new Maven dependency, no new Export-Package outside the audit subpackage already discussed in §1.

### Direction sanity check

The classical Oak rule (`AGENTS.md`): SPI must not depend on impl. We respect this:
- `AuditEvent`, `AuditEventListener`, `AuditBufferLifecycle`, `AuditEvents` (façade) all live in oak-security-spi.
- `AuditBuffer`, `WhiteboardAuditEventListenerRegistry`, `SnapshotAuditBufferHook`, `DispatchAuditEventsHook`, `AuditConfiguration` all live in oak-core (`oak-core/.../security/audit/`).
- oak-core depends on oak-security-spi (existing). Impl → SPI direction is correct.

---

## 5. Risks & mitigations

### Risk 1 — Hook ordering across SecurityConfigurations (HashSet undefined iteration)

**Source**: `oak-core/src/main/java/org/apache/jackrabbit/oak/security/SecurityProviderImpl.java:146-155` — `getConfigurations()` returns a `HashSet<SecurityConfiguration>`. Iteration order is JVM/JDK-dependent (in practice: hash-code-bucket order, which varies across JDKs and is not specified).

**Impact**: `MutableRoot.getCommitHook()` iterates `securityProvider.getConfigurations()` (`MutableRoot.java:290`) and appends regular hooks in iteration order. `SnapshotAuditBufferHook` (regular hook from `AuditConfiguration`) may run **before** or **after** another configuration's hook that legitimately throws (e.g., `AuthorizationConfigurationImpl.PermissionHook` — `AuthorizationConfigurationImpl.java:163`). If it runs after a throwing hook, the snapshot never executes; if it runs before, the snapshot ran but the dispatch never fires (commit fails).

**Mitigation**:
1. Both outcomes are handled by `AuditBufferLifecycle.onCommitFailed(sessionId)` called from `MutableRoot.commit()`'s catch block (design brief §α, line 57). The ThreadLocal entry is cleared either way.
2. **Do not** rely on hook ordering for correctness. The dispatch hook is `PostValidationHook` so it always runs last among hooks (`MutableRoot.java:292-294, 305`); the snapshot hook is positioned by HashSet order but its only contract is "runs at most once per merge attempt; idempotent".
3. Document in `AuditConfiguration` Javadoc: snapshot ordering is non-deterministic across `SecurityConfiguration`s. Do not chain other security state to it.

### Risk 2 — `SystemRoot` inherits the lifecycle (system-session commits get audited)

**Source**: `oak-core/src/main/java/org/apache/jackrabbit/oak/core/SystemRoot.java:36-94`. `SystemRoot extends MutableRoot` and does not override `commit()`, `refresh()`, or `rebase()`. Therefore the three new lifecycle calls run for every internal Oak system operation (initial repo setup, background indexing commits, etc.).

**Impact**: A listener that does not filter could be flooded with system events that are not user-actionable. Worse, an expensive listener could slow down internal commit paths that historically had no audit cost.

**Mitigation**:
1. **Documented contract**: `AuditEventListener.onCommit(...)` receives a `CommitInfo` (already populated by `MutableRoot.commit` line 259-260). `CommitInfo.getUserId()` returns `OAK_UNKNOWN` for system-session commits. Listeners filter on the user-id.
2. The listener guidance — also called out in design brief §"Failure modes addressed" — must appear in the `AuditEventListener` Javadoc, not buried in `AuditConfiguration`.
3. Performance: `AuditEvents.record(...)` is only called from capture sites in user-facing code (`MembershipProvider.addMember`, etc. — design brief lines 150-162). System operations don't trip capture sites today. The only cost for system commits is the empty `ThreadLocal` lookup in `SnapshotAuditBufferHook`, which short-circuits when the per-session entry is null.

### Risk 3 — Performance regression detection

**Concern**: The design's "zero hot-path cost" claim must hold across the three regimes (toggle off, toggle on / no listener, toggle on / listener). A regression here is invisible until production deployment.

**Mitigation**:
1. **Pre-merge benchmark required**, per design brief §"Performance constraints". Turing owns the benchmark plan (`04-tests-and-benchmarks.md`). Specific asks for that doc:
   - JMH benchmark in `oak-benchmarks` that measures `Root.commit()` latency with empty changes, in all three regimes, against `SEGMENT_TAR` (default) and `DOCUMENT_NS`.
   - Acceptance criterion: < 5% regression for "toggle off" vs trunk baseline at p50 and p99.
2. **No allocation on capture call site when listener absent**: `AuditEvents.record(...)` must check `WhiteboardAuditEventListenerRegistry.hasListenerFor(domain)` (cached `Set<String>`) before allocating any event object. The capture site in `MembershipProvider.addMember` (design brief lines 150-162) constructs the `MemberAddedEvent` only after `isEnabled()` returns true — this is intentional and grace must preserve it in the skeleton.
3. **No copy on snapshot**: `SnapshotAuditBufferHook` moves the `List<AuditEvent>` reference from ThreadLocal into `CommitContext`, then nulls out the ThreadLocal entry. No `ArrayList(other)` copy.

### Risk 4 — Listener mis-registration race (TOCTOU)

**Scenario**:
1. Thread A starts a write transaction. `AuditEvents.record(...)` checks `hasListenerFor("security")` → false → no event buffered.
2. Thread B registers a listener for `"security"` via Whiteboard.
3. Thread A commits. `DispatchAuditEventsHook` runs, sees the new listener, but the buffer is empty — events that should have been observed are lost.

**Mitigation**:
1. **Documented as expected behavior** (design brief §"Failure modes addressed", "Listener registered after capture, before commit (TOCTOU)" row). This is the price of the no-listener fast path; the alternative is unconditional event buffering, which violates §Performance.
2. Operationally: deploy listeners before enabling the toggle. Once the toggle has been ON for any non-zero duration, registering a new listener has standard "applies to subsequent transactions only" semantics — which is the JCR / observation convention anyway.
3. Cache invalidation in `WhiteboardAuditEventListenerRegistry`: when a listener appears/disappears on the Whiteboard, refresh the cached `Set<String> activeDomains` and publish via `volatile` write. Already-staged transactions on other threads are unaffected; new transactions see the new value immediately (volatile semantics).

### Risk 5 — Dynamic bind/unbind race semantics (OPTIONAL+DYNAMIC cardinality)

**Concern (per alex's follow-up in `02-oak-validation.md`)**: with `ReferenceCardinality.OPTIONAL` + `ReferencePolicy.DYNAMIC` on the new audit slot in `SecurityProviderRegistration`, the `SecurityProvider` can register before `AuditConfigurationImpl` binds, and can survive after it unbinds. Two transient windows result:

**Bootstrap window** (SecurityProvider live, audit not yet bound):
- `MutableRoot.getCommitHook()` (called per-commit, not memoized — `MutableRoot.java:282-308`) builds the chain *without* audit hooks. Correct: no audit configuration → no audit slot in `InternalSecurityProvider.getConfigurations()` → no `getCommitHooks` contribution.
- Concurrent capture sites: `AuditEvents.isEnabledFor(domain)` checks `feature` (`@Activate`-installed by `AuditConfigurationImpl`). Before activation, `feature == null` → returns false → captures short-circuit. **No events emitted before the dispatch chain can handle them.**
- After `AuditConfigurationImpl` binds and activates: next call to `MutableRoot.getCommitHook()` picks up the hooks (because `getCommitHook()` recomputes per commit). New captures land in a buffer that has a dispatch chain.

**Teardown window** (SecurityProvider live, audit just unbound):
- After unbind: `InternalSecurityProvider.auditConfiguration == null` → next `getCommitHook()` builds without audit hooks. Correct.
- Concurrent captures: `feature.close()` (step 1 of `@Deactivate`, see §2) makes `isEnabledFor()` return false. New captures stop immediately. **Asymmetric to bootstrap** — the closing thread shuts down captures synchronously; bootstrap relies on the natural absence of `feature` before `@Activate`.
- Residual: ThreadLocal entries already populated on other threads between the last commit and unbind. Documented in §2 "Residual ThreadLocal leak on deactivation". Bounded; not a leak in the unbounded sense.

**Why this works correctly (architectural invariant)**:

The `Feature` toggle is **owned by `AuditConfigurationImpl`'s lifecycle**, not a free-floating global. It is registered on the Whiteboard inside `@Activate` (`Feature.newFeature(...)`) and closed inside `@Deactivate` (`feature.close()`). Therefore the toggle can return `true` if and only if the configuration is alive. Capture sites check the toggle via `AuditEvents.isEnabledFor(domain)` which performs:

```
feature != null && feature.isEnabled() && registry != null && registry.hasListenerFor(domain)
```

— **all four conditions are tied to the same lifecycle.** When `AuditConfigurationImpl` is alive: toggle is registered and may be ON, registry is alive. When `AuditConfigurationImpl` is unbound: toggle is closed (returns false), registry is stopped. There is no window where the toggle says "capture" but the dispatch chain can't deliver.

**Mitigation**:
1. **Architectural invariant** (documented above): `Feature` and `WhiteboardAuditEventListenerRegistry` are private to `AuditConfigurationImpl`'s instance lifetime. Never expose them as static singletons or share across configuration instances. This is the constraint that makes OPTIONAL+DYNAMIC safe.
2. **Embedded mode** (non-OSGi): when no `AuditConfiguration` is wired via `SecurityProviderBuilder.withAuditConfiguration(...)`, `InternalSecurityProvider` has a null audit slot. `MutableRoot.getCommitHook()` builds without audit hooks. `AuditBufferLifecycle` stays NOOP (never `install(...)`-ed). Embedded users get the toggle-off, zero-cost path automatically.
3. **`maybeRegister()` re-trigger**: per the existing pattern (`SecurityProviderRegistration.java:325`, called from `bindConfiguration`), bind/unbind of audit triggers `maybeRegister()`. If the implementation defers re-registration until all *mandatory* references are satisfied, the OPTIONAL audit slot doesn't block bootstrap (because it's OPTIONAL). Grace's skeleton should verify this in `03-skeleton/` — but the existing OPTIONAL precedent in `UserConfigurationImpl.java:238` confirms OPTIONAL+DYNAMIC does not block bootstrap.

### Risk 6 — Toggle-off behavior leaves dead façade calls

**Concern**: When the toggle is off but `AuditConfiguration` is deployed and activated, the three `MutableRoot` calls still go through `AuditBufferLifecycle.onCommitFailed/onRefresh` to the *installed* `AuditBuffer` (not the NOOP). The buffer's methods early-exit on null per-session ThreadLocal entries — but they still incur a `ThreadLocal.get()` + Map lookup.

**Mitigation**:
1. `AuditBuffer` short-circuits on `feature.isEnabled() == false`. The check is a single volatile read followed by an atomic boolean read — cheaper than ThreadLocal lookup.
2. **Cleaner alternative considered and rejected**: gate `AuditBufferLifecycle.install(...)` on `feature.isEnabled()`, swap back to NOOP when toggle flips off. Rejected because toggle state is volatile/runtime-flippable and we don't want to wire a toggle-state observer just for this. The atomic-read short-circuit is fine.
3. Document: when the toggle is on with no listener, lifecycle calls have a sub-microsecond cost. Toggle-off OR no-deployment is the zero-cost path.

### Risk 7 — `EmptyHook.INSTANCE` filtering in `MutableRoot.getCommitHook()`

**Source**: `MutableRoot.java:292-296`:
```java
if (ch instanceof PostValidationHook) {
    postValidationHooks.add(ch);
} else if (ch != EmptyHook.INSTANCE) {
    hooks.add(ch);
}
```

**Concern**: `SnapshotAuditBufferHook` must not be `EmptyHook.INSTANCE`-equal, or it gets silently dropped. Conversely, `DispatchAuditEventsHook` is captured via the `PostValidationHook` branch and is **not** subject to the `EmptyHook` filter — fine.

**Mitigation**: trivial — `SnapshotAuditBufferHook` is a distinct instance with state. Just call out the rule in `03-skeleton/` so grace doesn't accidentally return `EmptyHook.INSTANCE` from a toggle-off conditional inside the hook.

### Risk 8 — `ResetCommitAttributeHook` clears `CommitContext` at the start of each merge attempt

**Source**: `oak-store-spi/.../ResetCommitAttributeHook.java:33-44` — clears `SimpleCommitContext` attributes on every `processCommit` call. `MutableRoot.getCommitHook():284` places this as the first hook.

**Impact**: NodeStore-driven merge retries (e.g., on conflict) re-enter the entire hook chain. `ResetCommitAttributeHook` clears the `CommitContext` at the top of each retry.

**Resolution — peek-only Snapshot + finally-block Dispatch (revised from design brief)**: an earlier draft of this section followed the design brief's "Snapshot copies ThreadLocal → CommitContext, *clears* ThreadLocal" pattern, which would lose events if a validator threw after Snapshot ran. Alex's structural review and grace's skeleton settled on a cleaner split that closes that gap:

- `SnapshotAuditBufferHook` is **peek-only**. It reads (does not remove) the per-session entry from `ThreadLocal` and writes the same list reference into `CommitContext` under the audit key. No state change on the ThreadLocal.
- `DispatchAuditEventsHook` (PostValidationHook) holds drain authority. After dispatching to listeners, it clears the ThreadLocal entry in a `finally` block — so the ThreadLocal entry is removed whether dispatch succeeds, individual listeners throw, or dispatch itself fails.

Concrete retry behavior:

| Scenario | Attempt 1 | Attempt 2 (retry) | Outcome |
|---|---|---|---|
| Validator throws after Snapshot | Snapshot peeks → CommitContext filled; validator throws; CommitContext wiped by `ResetCommitAttributeHook` at top of attempt 2 | Snapshot peeks ThreadLocal *again* (events still there) → CommitContext refilled. Commit succeeds → Dispatch fires → finally clears ThreadLocal. | **Events delivered once, no duplicates, no loss.** |
| Validator throws on every attempt | Snapshot peeks every attempt; ThreadLocal preserved. Final throw → `MutableRoot.commit()`'s catch block calls `AuditBufferLifecycle.onCommitFailed(sessionId)` → ThreadLocal cleared. | — | No dispatch (commit failed). ThreadLocal drained. Correct. |
| Listener throws during Dispatch | Snapshot peeks → CommitContext filled. Validators pass. Dispatch runs, listener throws, finally clears ThreadLocal. | — | Other listeners continue (Dispatch wraps each listener in try/catch per brief §"Failure modes addressed"). ThreadLocal drained. Single dispatch. |
| `Root.refresh()`/`rebase()` mid-transaction | — | — | `AuditBufferLifecycle.onRefresh(sessionId)` is called BEFORE the underlying refresh/rebase (`MutableRoot.java` diff per brief §α lines 70-89) → ThreadLocal cleared. Snapshot on next commit attempt sees empty entry. |

**Why this is materially better than the brief's design**: the brief's destructive Snapshot lost events on the validator-throws-after-snapshot retry path. Peek-only Snapshot preserves them across retries because the ThreadLocal is the authoritative store until Dispatch drains it. The "residual gap" I flagged in an earlier version of this risk is closed.

**Hook implementations (informative — code lives in 03-skeleton/)**:

```java
// SnapshotAuditBufferHook — regular CommitHook, peek-only
@Override
public NodeState processCommit(NodeState before, NodeState after, CommitInfo info) {
    String sessionId = info.getSessionId();
    List<AuditEvent> events = AuditBuffer.peek(sessionId);     // no clear
    if (events != null && !events.isEmpty()) {
        CommitContext ctx = (CommitContext) info.getInfo().get(CommitContext.NAME);
        ctx.set(AUDIT_KEY, events);
    }
    return after;
}

// DispatchAuditEventsHook — PostValidationHook, holds drain authority
@Override
public NodeState processCommit(NodeState before, NodeState after, CommitInfo info) {
    String sessionId = info.getSessionId();
    try {
        CommitContext ctx = (CommitContext) info.getInfo().get(CommitContext.NAME);
        Object events = ctx.get(AUDIT_KEY);
        if (events instanceof List<?>) {
            dispatch(after, info, (List<AuditEvent>) events);  // try/catch per listener
        }
    } finally {
        AuditBuffer.clearForSession(sessionId);                // unconditional drain
    }
    return after;
}
```

**Invariant**: at the start of every `MutableRoot.commit()`, the per-session ThreadLocal entry is either empty (cleaned up at the end of the previous successful commit, or by `onCommitFailed`/`onRefresh`) or holds events captured since the last lifecycle event. Snapshot+Dispatch is the only mechanism that moves them out, and Dispatch is the only mechanism that clears them.

**Turing's benchmark plan must still cover** (per `04-tests-and-benchmarks.md`):
- Single-attempt success path (cost of one peek + one move + one dispatch).
- Retry-storm scenario (N attempts, validator throws on the first N-1, succeeds on attempt N) → exactly one dispatch, no event loss, no duplication, no exception storm.
- Listener-throws scenario → other listeners still fire; ThreadLocal drained.

---

## Summary of architectural decisions

| Topic | Decision | Citation |
|---|---|---|
| SPI module | `oak-security-spi` | `oak-security-spi/pom.xml:48-66` |
| New package | `org.apache.jackrabbit.oak.spi.security.audit` (add to `Export-Package`) | new line in `oak-security-spi/pom.xml` |
| New SPI interface | `AuditConfiguration extends SecurityConfiguration` — typed marker, no v1 methods, `@ProviderType` | required by `SecurityProviderRegistration.java:229-318` — registration binds typed sub-interfaces only |
| Impl class name | `AuditConfigurationImpl` (renamed from `AuditConfiguration` per the SPI promotion) | matches `UserConfigurationImpl`, `AuthorizationConfigurationImpl` convention |
| Registration cardinality | UNARY OPTIONAL DYNAMIC — audit is a singleton resource; absence is non-fatal; hot-swap supported | precedent: `UserConfigurationImpl.java:238` (OPTIONAL+DYNAMIC on `BlobAccessProvider`) |
| Null-object | `AuditConfiguration.NOOP` (nested `Noop` extends `SecurityConfiguration.Default`); `InternalSecurityProvider.auditConfiguration` initialized to NOOP, setter coerces null → NOOP; `@NotNull` contract end-to-end | mirrors `BlobAccessProvider.DEFAULT_BLOB_ACCESS_PROVIDER` pattern from `UserConfigurationImpl.java:244` |
| Deprecated `SecurityProviderImpl` | Skipped (alex confirmed zero non-test consumers); embedded users migrate to `SecurityProviderBuilder` | `SecurityProviderImpl.java:65` `@Deprecated` |
| Annotations | `@ConsumerType` on `AuditEventListener`; `@ProviderType` on `AuditEvent` and `AuditConfiguration` | follows `SecurityConfiguration.java:29,39` |
| Listener ordering | `AuditEventListener.getRank()` (default 0); explicit stable sort in registry — Whiteboard ranking is NOT portable | `DefaultWhiteboard.java:35-64` (no sort) vs `OsgiWhiteboard.java:181-194` (TreeMap by ranking) |
| Prior art | None — `org.apache.jackrabbit.oak.spi.security.audit.*` is greenfield (OAK-2516 was a logger, not an SPI) | `05-research-notes.md` |
| Payload contract | Non-null values via `Map.of(...)`; optional fields omitted (absent key), not sentinel; user IDs use `OAK_UNKNOWN`, never `""` | `CommitInfo.java:94` (`OAK_UNKNOWN = "oak:unknown"`) |
| Hot-path API | `AuditEvents.isEnabledFor(domain)` is the canonical capture-site gate; `isEnabled()` is coarse fallback | Risk 3 mitigation 2 (this doc, §5) |
| OSGi pattern | `@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})`, `@Designate`, `@Activate`/`@Deactivate` | `UserConfigurationImpl.java:78-236` |
| Deactivation order | `feature.close()` FIRST (stop new captures) → `registry.stop()` → `install(null)` → `clearAll()` — NOT reverse-of-activate | this doc §2 "Deactivation"; race exposed by alex |
| Toggle | `Feature.newFeature("FT_AUDIT", whiteboard)` in `@Activate`; `feature.close()` in `@Deactivate` (FIRST). Rename to `FT_AUDIT_OAK-<NNNNN>` if upstreamed. | `Feature.java:62-83`, `DocumentNodeStoreService.java:319-326, 713-724` |
| Toggle default | disabled (new feature) | `AGENTS.md` "Feature Toggles" |
| Hook ordering | `DispatchAuditEventsHook implements PostValidationHook` → auto-routed to end of chain | `MutableRoot.java:292-294, 305` |
| Snapshot semantics | Peek-only, **non-destructive**. Dispatch holds drain authority in `finally` block. Closes retry-loss gap from the brief's destructive snapshot | this doc §5 Risk 8 |
| New deps | none — uses existing `oak-core → oak-security-spi`, `oak-security-spi → oak-core-spi/oak-store-spi` | `oak-core/pom.xml:261-287`, `oak-security-spi/pom.xml:122-131` |
| MutableRoot diff | +1 import (`AuditBufferLifecycle`), +3 method calls (no new fields) | `MutableRoot.java:235-268` |

---

## Open questions raised to teammates

- ~~**alex**: confirm `SystemRoot` inherits all three lifecycle calls and that no other `MutableRoot` subclass exists in trunk.~~ **Resolved (2026-05-12)**: alex completed validation in `02-oak-validation.md` §7.
- ~~**alex**: confirm `SecurityProviderImpl` either auto-discovers `AuditConfiguration` or whether explicit wiring is needed in the embedded constructor.~~ **Resolved (2026-05-12)**: alex's source-validation pass exposed the structural blocker — `SecurityProviderRegistration.java:229-318` binds only six typed sub-interfaces; no generic `SecurityConfiguration` binding. Path α as written in the brief does not work in OSGi. Resolution: promote `AuditConfiguration` to a typed SPI marker interface in `oak-security-spi`, add a 7th UNARY OPTIONAL DYNAMIC `@Reference` slot. Deprecated `SecurityProviderImpl` skipped (zero non-test consumers per alex's grep). See §1 "AuditConfiguration as a typed SPI marker interface" and §2 "Registration binding (the 7th slot)".
- ~~**shannon**: confirm Whiteboard service-ranking sort applies to the `Tracker<AuditEventListener>` path.~~ **Resolved (2026-05-12)**: shannon confirmed `DefaultWhiteboard` does not honor ranking (`DefaultWhiteboard.java:35-64` — identity HashSet, no sort). SPI now requires `AuditEventListener.getRank()` and explicit sort in the registry. See §1 "Listener ordering — hard SPI contract".
- ~~**team-lead Q1 + Q2** (payload null contract, `isEnabledFor(domain)` API).~~ **Resolved (2026-05-12)**: see §1 "AuditEvent.payload() contract" and §3 "Hot-path check — two-level".
- **grace**: in the skeleton, make sure `SnapshotAuditBufferHook` is never `EmptyHook.INSTANCE`-equal (Risk 7) and that `AuditEvents.record(...)` allocates no event object when the listener cache reports no listener for the domain (Risk 3, mitigation 2). `AuditEventListener` interface must declare `default int getRank() { return 0; }` per §1. Skeleton must reflect class rename to `AuditConfigurationImpl`, new SPI marker `AuditConfiguration`, and the four registration deltas in §2. Remove the `""` substitution in `MemberAddedEvent`/`MemberRemovedEvent` payload maps — source values are never null.
- **turing**: regressions targeted in Risks 3 and 8 need explicit benchmarks.
