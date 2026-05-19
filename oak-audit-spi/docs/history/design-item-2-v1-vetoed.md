# Item 2 — Drop AuditConfiguration as SecurityConfiguration

**Status:** FROZEN
**Author:** ada
**Reviewer feedback addressed:** Option (a) — move audit ownership to `oak-audit-spi` as a top-level Oak service.
**Related:** `design.md` §3 (Module layout), §5 (Commit-attached pipeline).
**Resolutions baked in below:** shannon's research findings (no existing `WhiteboardCommitHook`; only contributable path today is `SecurityConfiguration.getCommitHooks`); alex's verification that upgrade/sidegrade bypass `MutableRoot` entirely (no behavior risk); sage's hook-ordering / disposal-completeness invariants; grace's migration-semantics-pinning ask; **sage's post-review findings A/B/C** (CommitContext-key visibility doc, package-rename consistency, embedded-mode misconfig fail-loudness) — all adopted, see §6 resolved-decisions table and §3.6.2 / §3.6.3 for the impl-level impact.

---

## 1. Problem statement

Path α ships audit's commit-hook contribution through `SecurityConfiguration`:

```java
// AuditConfigurationImpl.java
@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})  // ← TWO services
public class AuditConfigurationImpl extends ConfigurationBase implements AuditConfiguration { ... }

// AuditConfiguration.java (oak-security-spi)
public interface AuditConfiguration extends SecurityConfiguration { ... }       // ← extends SecurityConfiguration

// SecurityProviderRegistration.java
@Reference(name = "auditConfiguration", service = AuditConfiguration.class,
           cardinality = OPTIONAL, policy = DYNAMIC)
public void bindAuditConfiguration(AuditConfiguration auditConfiguration, ...) { ... }

// SecurityProviderBuilder.java
public SecurityProviderBuilder withAuditConfiguration(AuditConfiguration auditConfiguration) { ... }

// InternalSecurityProvider.java
public Iterable<? extends SecurityConfiguration> getConfigurations() {
    return SetUtils.toSet(authentication, authorization, user, privilege, principal, token, audit); // ← audit lumped in
}

// MutableRoot.java
for (SecurityConfiguration sc : securityProvider.getConfigurations()) {
    for (CommitHook ch : sc.getCommitHooks(workspaceName)) {
        if (ch instanceof PostValidationHook) postValidationHooks.add(ch);
        else if (ch != EmptyHook.INSTANCE) hooks.add(ch);
    }
    validators.addAll(sc.getValidators(...));
}
```

Two things to fix:

1. **Conceptual leak.** Audit isn't a security domain. The reviewer's objection ("audit is cross-domain, not security-scoped") is correct — `SecurityConfiguration` was a wiring convenience that misrepresents what audit IS.
2. **Where do the commit hooks land?** The current path piggybacks on `SecurityConfiguration.getCommitHooks(workspaceName)` being iterated inside `MutableRoot.getCommitHook()`. Remove the `SecurityConfiguration` inheritance and the hooks have no path to the commit chain. We need a new mechanism.

---

## 2. Background — how hooks reach the commit chain today

The relevant `MutableRoot.getCommitHook()` logic (line 293):

```java
private CommitHook getCommitHook() {
    List<CommitHook> hooks = new ArrayList<>();
    hooks.add(ResetCommitAttributeHook.INSTANCE);
    hooks.add(hook);                                         // ← Oak-level hook (from Oak.with(CommitHook), composed)

    List<CommitHook> postValidationHooks = new ArrayList<>();
    List<ValidatorProvider> validators = new ArrayList<>();

    for (SecurityConfiguration sc : securityProvider.getConfigurations()) {
        for (CommitHook ch : sc.getCommitHooks(workspaceName)) {
            if (ch instanceof PostValidationHook) postValidationHooks.add(ch);
            else if (ch != EmptyHook.INSTANCE)    hooks.add(ch);
        }
        validators.addAll(sc.getValidators(workspaceName, principals, moveTracker));
    }

    if (!validators.isEmpty()) hooks.add(new EditorHook(CompositeEditorProvider.compose(validators)));
    hooks.addAll(postValidationHooks);                       // ← appended LAST

    return CompositeHook.compose(hooks);
}
```

Key insight: **the `PostValidationHook` marker interface is what splits hooks across the validator boundary.** Audit's `DispatchAuditEventsHook implements PostValidationHook` (verified `oak-core/.../audit/DispatchAuditEventsHook.java:45`); `SnapshotAuditBufferHook` is a regular `CommitHook` (`SnapshotAuditBufferHook.java:54`). The marker positions them on the right side of the validator EditorHook.

**This marker mechanism is the load-bearing pattern we must preserve.** Any new hook-contribution path MUST run through `MutableRoot.getCommitHook()` (or an equivalent split point) so the `PostValidationHook` marker is honored.

There are two existing sources of hooks:
| Source | Reaches MutableRoot how | PostValidationHook split? |
|---|---|---|
| Oak-level `hook` (the global `Oak.with(CommitHook)` chain, composed) | Passed through `Oak.createNewContentRepository` → `ContentRepositoryImpl` → `ContentSessionImpl` → `MutableRoot` constructor | **NO** — runs in declared order, no split |
| `SecurityConfiguration.getCommitHooks(workspaceName)` | Iterated inside `MutableRoot.getCommitHook()` | **YES** |

Audit currently uses the second source. After this refactor it must STILL use a split-aware source.

---

## 3. Design

### 3.1 New generic SPI: `CommitHookProvider`

Add a new SPI interface in `oak-store-spi`, alongside `CommitHook` and `PostValidationHook`:

```java
package org.apache.jackrabbit.oak.spi.commit;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ConsumerType;

/**
 * Contributes a set of {@link CommitHook} instances to Oak's commit chain.
 * Hooks implementing {@link PostValidationHook} are positioned AFTER Oak's
 * validators; regular hooks run before.
 *
 * <p>Providers are discovered via OSGi (services registered with
 * {@link CommitHookProvider} as their interface type) and Whiteboard
 * tracking in OSGi deployments, or registered explicitly via
 * {@code Oak.with(CommitHookProvider)} in embedded deployments.
 *
 * <p>Unlike {@link CommitHook#processCommit} which runs per commit, this
 * SPI is queried once per session at {@link org.apache.jackrabbit.oak.api.Root}
 * commit time; the returned list is iterated and partitioned by
 * {@link PostValidationHook} marker. Implementations should therefore be
 * stable in their returned hooks (no per-call allocation that depends on
 * mutable state).
 *
 * <p><strong>Trust model.</strong> Bundle deployment is the security
 * boundary. A bundle that can register a {@code CommitHookProvider} can
 * contribute hooks that run inside Oak's commit chain; such hooks observe
 * (and can mutate) the commit's {@code NodeState} and read keys from
 * {@code CommitContext}. Protecting against hostile providers requires
 * OSGi-level controls (bundle signing, deployment policy). Embedded
 * (non-OSGi) deployments inherit the JVM classpath as the boundary
 * instead.
 */
@ConsumerType
public interface CommitHookProvider {

    /**
     * Returns the list of commit hooks this provider contributes for the
     * given workspace. Empty list means "this provider has nothing to
     * contribute right now" (e.g. feature toggle off).
     *
     * @param workspaceName name of the workspace being committed against; non-null.
     * @return non-null, possibly-empty list of {@link CommitHook} instances.
     */
    @NotNull
    List<? extends CommitHook> getCommitHooks(@NotNull String workspaceName);
}
```

**Why a new SPI rather than reusing `CommitHook`:**
- `CommitHook` is a *processing* interface; `CommitHookProvider` is a *contribution* interface — same distinction as `Editor` vs `EditorProvider`.
- Allows a single OSGi component to contribute MULTIPLE hooks (audit needs two: pre-validation snapshot + post-validation dispatch). With raw `CommitHook` services, OSGi cardinality semantics would force the audit component to register two separate services and Oak would have to identify pairs — fragile.
- The list-returning shape is exactly what `MutableRoot.getCommitHook()` already iterates on `SecurityConfiguration`. Symmetric.

### 3.2 Whiteboard aggregator: `WhiteboardCommitHookProvider`

Mirror of the existing `WhiteboardEditorProvider` (`oak-store-spi/.../spi/commit/WhiteboardEditorProvider.java`):

```java
package org.apache.jackrabbit.oak.spi.commit;

import java.util.ArrayList;
import java.util.List;
import org.apache.jackrabbit.oak.spi.whiteboard.AbstractServiceTracker;
import org.jetbrains.annotations.NotNull;

/**
 * Dynamic {@link CommitHookProvider} aggregator backed by Whiteboard
 * services. Returns the concatenation of all tracked
 * {@link CommitHookProvider} instances' contributions.
 *
 * <p>The order of aggregated providers follows the Whiteboard tracker's
 * iteration order (typically registration order). Within an individual
 * provider, the returned list order is preserved. Per-provider ordering
 * stability is the responsibility of each provider implementation.
 */
public class WhiteboardCommitHookProvider
        extends AbstractServiceTracker<CommitHookProvider>
        implements CommitHookProvider {

    public WhiteboardCommitHookProvider() {
        super(CommitHookProvider.class);
    }

    @NotNull
    @Override
    public List<? extends CommitHook> getCommitHooks(@NotNull String workspaceName) {
        List<CommitHook> all = new ArrayList<>();
        for (CommitHookProvider provider : getServices()) {
            all.addAll(provider.getCommitHooks(workspaceName));
        }
        return all;
    }
}
```

This is the OSGi-mode reach mechanism, equivalent to how `WhiteboardEditorProvider` is consumed in `oak-jcr/.../RepositoryManager.java:72`.

### 3.3 `MutableRoot.getCommitHook()` modification

The split logic stays; we add a second iteration source:

```java
private CommitHook getCommitHook() {
    List<CommitHook> hooks = new ArrayList<>();
    hooks.add(ResetCommitAttributeHook.INSTANCE);
    hooks.add(hook);

    List<CommitHook> postValidationHooks = new ArrayList<>();
    List<ValidatorProvider> validators = new ArrayList<>();

    // EXISTING: security-driven contribution
    for (SecurityConfiguration sc : securityProvider.getConfigurations()) {
        for (CommitHook ch : sc.getCommitHooks(workspaceName)) {
            partitionHook(ch, hooks, postValidationHooks);
        }
        validators.addAll(sc.getValidators(workspaceName, principals, moveTracker));
    }

    // NEW: generic contribution from any CommitHookProvider
    for (CommitHook ch : commitHookProvider.getCommitHooks(workspaceName)) {
        partitionHook(ch, hooks, postValidationHooks);
    }

    if (!validators.isEmpty()) hooks.add(new EditorHook(CompositeEditorProvider.compose(validators)));
    hooks.addAll(postValidationHooks);

    return CompositeHook.compose(hooks);
}

private static void partitionHook(CommitHook ch, List<CommitHook> pre, List<CommitHook> post) {
    if (ch instanceof PostValidationHook) post.add(ch);
    else if (ch != EmptyHook.INSTANCE)    pre.add(ch);
}
```

`commitHookProvider` is a new constructor parameter on `MutableRoot`. Default value when not wired: `(ws) -> List.of()` (returns empty). Threaded through `ContentSessionImpl.getLatestRoot()` and `ContentRepositoryImpl` constructor (carrying the value from `Oak`).

**Defensive ctor check.** `MutableRoot`'s ctor adds `Objects.requireNonNull(commitHookProvider)` so a forgotten thread-through produces a loud failure at construction time, not an NPE at the first commit. Same hardening pattern as the existing `requireNonNull(store)` / `requireNonNull(hook)` calls at lines 165–167.

### 3.4 `Oak.java` integration

Add a builder method:

```java
private CommitHookProvider commitHookProvider = (ws) -> Collections.emptyList();

@NotNull
public Oak with(@NotNull CommitHookProvider provider) {
    // Composes additively — caller may call multiple times; each appends.
    CommitHookProvider previous = this.commitHookProvider;
    this.commitHookProvider = (ws) -> {
        List<CommitHook> all = new ArrayList<>(previous.getCommitHooks(ws));
        all.addAll(provider.getCommitHooks(ws));
        return all;
    };
    return this;
}
```

In `Oak.createNewContentRepository()`, the `commitHookProvider` is passed forward into `ContentRepositoryImpl`. (Already-existing `securityProvider` plumbing serves as the precedent — symmetric injection.)

### 3.5 `RepositoryManager` (oak-jcr) integration

`RepositoryManager.activate()` (line 168 area) gains:

```java
private final WhiteboardCommitHookProvider commitHookProvider = new WhiteboardCommitHookProvider();
// …

@Activate
public void activate(BundleContext bundleContext, Map<String, ?> config) {
    // …
    editorProvider.start(whiteboard);
    commitHookProvider.start(whiteboard);   // NEW — pattern matches editorProvider
    initializers.start(whiteboard);
    // …

    Oak oak = new Oak(store)
            .with(new InitialContent())
            .with(new VersionHook())
            // …
            .with(editorProvider)
            .with(commitHookProvider)        // NEW
            // …
            .with(securityProvider)
            // …;
}

@Deactivate
public void deactivate() {
    commitHookProvider.stop();              // NEW
    // …
}
```

This is the OSGi-mode end-to-end path. No bespoke wiring per-domain (audit, future others).

### 3.6 Audit module refactor

#### 3.6.1 `oak-audit-spi` — interface decisions

**Open question (resolved here, debate welcome):**
Does `AuditConfiguration` survive at all?

I propose **NO** — delete it entirely. Reasoning:

| Today's purpose of `AuditConfiguration` | Replacement |
|---|---|
| Typed `@Reference` lookup in `SecurityProviderRegistration` | Removed entirely (no longer needed). |
| `securityProvider.getConfiguration(AuditConfiguration.class)` callers in `InternalSecurityProvider` | Removed. No external callers (grep across `apache/jackrabbit-oak` is clean — verify: see §5). |
| `AuditConfiguration.NOOP` placeholder | Not needed — without typed lookup, no need for a no-arg placeholder. |
| Marker for "audit is deployed" | `AuditEvents.isEnabled()` already answers this. |

**Net effect on `oak-audit-spi`:** ZERO additions beyond what Path α already ships. The module's public surface stays at:
- `AuditEvent` (extended with static factory per item 3)
- `AuditEventListener`
- `AuditEventEmitter`
- `AuditEvents` (façade)
- `AuditBufferLifecycle`
- (item 3 additions: `SimpleAuditEvent` package-private)

**Alternative (if team prefers):** KEEP `AuditConfiguration` in `oak-audit-spi` as a thin marker interface that the impl class implements — same shape, just `extends CommitHookProvider` instead of `extends SecurityConfiguration`. Pro: gives an explicit "this is audit" service identity. Con: extra interface for no functional reason; `CommitHookProvider` registration alone is sufficient. I recommend dropping it; open for debate.

#### 3.6.2 `oak-core/.../security/audit/AuditConfigurationImpl` — refactor

```java
// Before
@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})
public class AuditConfigurationImpl extends ConfigurationBase implements AuditConfiguration {
    // … initialize, dispose, getCommitHooks, BufferSink
}

// After
@Component(service = CommitHookProvider.class)
public class AuditPipelineImpl implements CommitHookProvider {
    // Same initialize/dispose lifecycle (Feature toggle, AuditBuffer, registry, AuditEvents.install)
    // No ConfigurationBase, no SecurityConfiguration
    // Same getCommitHooks(workspaceName) shape — just on a different interface
}
```

Rename class to `AuditPipelineImpl` (clearer; internal, no SPI impact).

**Package move (Finding B resolution): ALL audit-internal classes move together** to a new top-level package `org.apache.jackrabbit.oak.audit` (drops the `security.` prefix to reflect cross-domain ownership). The class set is:

| Current location | New location | Visibility |
|---|---|---|
| `oak.security.audit.AuditConfigurationImpl` | `oak.audit.AuditPipelineImpl` | `public` (OSGi `@Component`) |
| `oak.security.audit.AuditBuffer` | `oak.audit.AuditBuffer` | package-private |
| `oak.security.audit.SnapshotAuditBufferHook` | `oak.audit.SnapshotAuditBufferHook` | package-private |
| `oak.security.audit.DispatchAuditEventsHook` | `oak.audit.DispatchAuditEventsHook` | package-private |
| `oak.security.audit.CommitMetadataDecorator` | `oak.audit.CommitMetadataDecorator` | package-private |
| `oak.security.audit.WhiteboardAuditEventListenerRegistry` | `oak.audit.WhiteboardAuditEventListenerRegistry` | package-private |
| `oak.security.audit.NoOpAuditEventListener` | `oak.audit.NoOpAuditEventListener` | package-private |
| `oak.security.audit.AuditEventEmitterImpl` | `oak.audit.AuditEventEmitterImpl` | `public` (OSGi `@Component`) |

**Visibility invariant (sage Finding B):** the 6 package-private classes MUST stay package-private after the move. The OSGi bundle's Export-Package on `oak-core` must NOT expose `org.apache.jackrabbit.oak.audit.AuditBuffer`/`SnapshotAuditBufferHook`/etc. Grace's bnd metadata (or `oak-core/pom.xml` `<Export-Package>` directive) must explicitly NOT include these internal classes; baseline check on `oak-core` will catch this if grace accidentally widens the export. The two `public` classes (`AuditPipelineImpl`, `AuditEventEmitterImpl`) are the ONLY ones that should appear on the OSGi export.

**Rejected alternative (sage Finding B Option 2):** making the 6 package-private classes `public` to keep the `AuditPipelineImpl` in `org.apache.jackrabbit.oak.audit` while leaving collaborators in `oak.security.audit`. Rejected categorically — it would expose implementation internals on the OSGi bundle's Export-Package, locking future maintainers into preserving their shape. Move-all-together is the only sound option.

**Self-initialization fail-loudly (Finding C resolution):** `AuditPipelineImpl.getCommitHooks(workspaceName)` checks whether `initialize(Whiteboard)` has run, and throws `IllegalStateException` with a clear message if not:

```java
@NotNull
@Override
public List<? extends CommitHook> getCommitHooks(@NotNull String workspaceName) {
    if (featureToggle == null || buffer == null || registry == null) {
        throw new IllegalStateException(
                "AuditPipelineImpl not initialized. Call initialize(Whiteboard) or use " +
                "AuditPipelineImpl.create(Whiteboard) before passing to Oak.with(CommitHookProvider). " +
                "See oak-audit-spi/docs/design.md §3.7.");
    }
    return List.of(
            new SnapshotAuditBufferHook(featureToggle, buffer),
            new DispatchAuditEventsHook(featureToggle, buffer, registry));
}
```

This replaces the Path α silent-no-op `return List.of()`. The previous safety net came from `SecurityProviderBuilder.build()` auto-calling `auditConfiguration.initialize(whiteboard)` if it detected an `AuditConfigurationImpl` — that safety net is gone with the wiring removal, so a missed `initialize()` would otherwise silently produce zero audit events with no diagnostic. Loud failure at the first commit > silent no-op forever.

#### 3.6.2a `SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY` doc comment (Finding A)

The package-private constant `SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY = "oak.audit.events"` is the key under which staged audit events ride the `CommitContext` between `SnapshotAuditBufferHook` (regular, pre-validation) and `DispatchAuditEventsHook` (PostValidationHook). The constant identifier is package-private, but the **string value** is just a string — any other CommitHook (whether contributed via `SecurityConfiguration` or — after item 2 — via `CommitHookProvider`) running in the same commit can do:

```java
List<AuditEvent> events = (List<AuditEvent>) commitContext.get("oak.audit.events");
// Now we have all staged audit events for this commit.
```

This is a pre-existing exfiltration vector inherited from Oak's general `CommitContext` model (CUG and others use string-keyed `CommitContext` entries the same way). Item 2 does not introduce it, but does make the producer surface for hooks more discoverable, so we document the limitation in source.

Grace's impl adds a one-line comment at the declaration:

```java
// The string value "oak.audit.events" is observable to any CommitHook
// service in the same commit. Exfiltration via this channel is a
// recognized limitation under the bundle-deploy trust model
// (see AuditEvents.install Javadoc and design.md §9).
static final String COMMIT_CONTEXT_KEY = "oak.audit.events";
```

A typed `CommitContext` key system that would harden this is out of scope for item 2 (would require an Oak-wide SPI addition).

#### 3.6.3 Removals from `oak-core/security/internal`

| File | Change |
|---|---|
| `InternalSecurityProvider.java` | DELETE the `auditConfiguration` field (lines 50–63), the `NAME`-based dispatch case for audit (line 103–105), the class-based dispatch case for audit (line 152–158), the `setAuditConfiguration` method (line 197–204). Remove audit from `getConfigurations()` (line 113–122). |
| `SecurityProviderRegistration.java` | DELETE the `auditConfiguration` field (line 156), the `@Reference` annotation block (line 267–283), the `unbindAuditConfiguration` method (line 285–291), the `.withAuditConfiguration(auditConfiguration)` call (line 629). |
| `SecurityProviderBuilder.java` | DELETE the `auditConfiguration` field (line 81–82), the `withAuditConfiguration` method (line 261–264), the audit-init block in `build()` (line 232–246). |

#### 3.6.4 Test fixture changes

`oak-run-commons/.../OakFixture.getMemoryNSWithAudit` (currently uncommitted per memory) needs adjustment: instead of building a `SecurityProvider` with `.withAuditConfiguration(auditPipeline)`, the fixture wires audit via `Oak.with(auditPipeline)` directly.

### 3.7 Embedded mode

In tests / non-OSGi callers (e.g. `MemoryNSWithAuditFixtureTest`, `AuditPipelineIT`, `AuditWiringIT`):

```java
// One-step construction. Static factory does new + initialize.
AuditPipelineImpl audit = AuditPipelineImpl.create(whiteboard);

// Wire it into Oak
ContentRepository repo = new Oak(nodeStore)
        .with(securityProvider)
        .with(audit)            // CommitHookProvider; reaches MutableRoot via the new path
        .createContentRepository();
```

The `AuditPipelineImpl.create(Whiteboard)` static factory collapses construction + `initialize(whiteboard)` to a single call. The two-step `new + initialize` API stays available (the OSGi `@Activate` path uses it), but embedded callers use the factory.

No more `SecurityProviderBuilder.withAuditConfiguration(...)`. The audit pipeline is constructed and wired separately, on equal footing with `SecurityProvider`.

**Why not put the helper in `oak-audit-spi`?** `oak-audit-spi` contains SPI only (no impl). `AuditPipelineImpl` lives in `oak-core` (impl). A static factory `AuditPipelineImpl.create(Whiteboard)` on the impl class gives embedded callers the one-line ergonomics alex pushed for (option (c) shape from his review), without adding impl to the SPI module.

### 3.8 Session semantics — which sessions fire audit events

(Grace + alex specifically asked this be pinned.)

> **Audit hooks fire only for sessions whose commit chain is materialized through the audit-bound mechanism** — i.e. `Oak` instances configured with the audit module (either via `Oak.with(auditPipeline)` directly, or via OSGi where `WhiteboardCommitHookProvider` tracks the `AuditPipelineImpl` `@Component`). Custom commit chains that bypass this route do NOT fire audit events:
>
> - `RepositoryUpgrade` and `RepositorySidegrade` call `NodeStore.merge` directly (`RepositorySidegrade.java:446`, `RepositoryUpgrade.java:550+`), bypassing `Oak`/`MutableRoot` entirely. **No audit events.** Alex verified this is structurally true regardless of the wiring mechanism we pick.
> - Test fixtures or embedded apps that construct a custom commit chain without an `AuditPipelineImpl` instance get **no audit events** (they didn't wire it).
> - Sessions issued by an `Oak` instance configured WITHOUT audit get **no audit events** (no contribution from the new mechanism).
>
> **This is intentional and matches PR-review item 4:** audit captures user-API-level changes (`Root.commit()` invocations on application sessions), not transaction-log-level changes. No runtime gating on session type is introduced — the wiring decision IS the gate. Future code paths that want non-audited Oak instances simply don't attach the audit `CommitHookProvider`.

This is also captured in `design.md` § (new sub-section under §5) as part of task #9.

---

## 4. End-to-end paths (OSGi vs. embedded)

### 4.1 OSGi mode

```
[bundle activation]
  AuditPipelineImpl                                                              [oak-core]
    ├─ @Activate → initialize(OsgiWhiteboard(bundleContext))
    │    ├─ Feature.newFeature(FT_AUDIT, whiteboard)
    │    ├─ WhiteboardAuditEventListenerRegistry.start(whiteboard)
    │    ├─ AuditBufferLifecycle.install(buffer)
    │    └─ AuditEvents.install(bufferSink)
    └─ Registered as @Component(service = CommitHookProvider.class)
         │
         ▼
[OSGi service registry]                                                          [OSGi]
    │
    ▼
RepositoryManager.activate()                                                     [oak-jcr]
  ├─ commitHookProvider = new WhiteboardCommitHookProvider()
  ├─ commitHookProvider.start(whiteboard)   ◄── tracks AuditPipelineImpl
  └─ new Oak(store).with(commitHookProvider).with(securityProvider).…
         │
         ▼
Oak.createNewContentRepository()                                                 [oak-core]
  └─ new ContentRepositoryImpl(store, composite, …, securityProvider, commitHookProvider)
         │
         ▼
ContentSessionImpl.getLatestRoot()                                                [oak-core]
  └─ new MutableRoot(store, hook, …, securityProvider, commitHookProvider, …)
         │
         ▼
MutableRoot.commit() → MutableRoot.getCommitHook()                                [oak-core]
  ├─ securityProvider.getConfigurations() loop (existing) → partition by PostValidationHook marker
  └─ commitHookProvider.getCommitHooks(workspaceName)     → partition by PostValidationHook marker
         │
         ▼
        SnapshotAuditBufferHook                  ← pre-validation
        EditorHook (composite validators)        ← validators
        DispatchAuditEventsHook                  ← post-validation
```

### 4.2 Embedded mode

```
[test setup]
AuditPipelineImpl audit = new AuditPipelineImpl();
audit.initialize(whiteboard);
…
new Oak(store).with(securityProvider).with(audit).createContentRepository()
         │
         ▼
Oak.with(CommitHookProvider provider)    ← single-provider builder method
   composes with the prior provider (default: empty)
         │
         ▼
[same downstream as OSGi from createNewContentRepository onward]
```

Same downstream code path; only the wiring stage differs.

---

## 5. Audit of removable code

`grep -rln "AuditConfiguration" oak-core/src/main/ oak-security-spi/src/main/`:

```
oak-core/src/main/java/org/apache/jackrabbit/oak/security/audit/AuditConfigurationImpl.java
oak-core/src/main/java/org/apache/jackrabbit/oak/security/internal/InternalSecurityProvider.java
oak-core/src/main/java/org/apache/jackrabbit/oak/security/internal/SecurityProviderBuilder.java
oak-core/src/main/java/org/apache/jackrabbit/oak/security/internal/SecurityProviderRegistration.java
oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/AuditConfiguration.java
```

All five files are listed above with explicit edits in §3.6. No external callers detected within `apache/jackrabbit-oak`. (Shannon: please confirm against any AEM/Sling/other-downstream references you can reach.)

---

## 6. Test impact (turing — preview)

Tests likely touched:

| Test | Change |
|---|---|
| `oak-core/.../security/audit/AuditConfigurationImplTest` | RENAME to `AuditPipelineImplTest`; adjust to the new `CommitHookProvider`-only surface (no more `SecurityConfiguration`); coverage stays 100%. |
| `oak-core/.../security/audit/AuditPipelineIT` | Adjust wiring to use `Oak.with(audit)` instead of `SecurityProviderBuilder.withAuditConfiguration(audit)`. End-to-end behavior unchanged. |
| `oak-core/.../security/audit/AuditWiringIT` | Same wiring change. Plus the item-3 assertion changes (typed events → string discrimination). |
| `oak-core/.../security/internal/SecurityProviderBuilderTest` | DELETE tests that asserted `withAuditConfiguration` behavior. Other tests unaffected. |
| `oak-core/.../security/internal/SecurityProviderRegistrationTest` | DELETE the audit-binding test cases. |
| `oak-security-spi/.../spi/security/audit/AuditConfigurationTest` | DELETE — interface no longer exists. |
| `oak-run-commons/.../MemoryNSWithAuditFixtureTest` | Adjust to new wiring. |
| `oak-core/.../core/MutableRootTest` | EXTEND with tests verifying `commitHookProvider` contributions get partitioned by `PostValidationHook` marker. Cover the empty/default case. |
| `oak-store-spi` | NEW: `WhiteboardCommitHookProviderTest` (analogous to `WhiteboardEditorProviderTest`). |

---

## 7. Trade-offs explicitly accepted

1. **`MutableRoot` constructor gains a parameter.** Going from 9 params to 10. Annoying but unavoidable — passing through a default `(ws) -> List.of()` keeps existing callers compiling unchanged.
2. **New SPI in `oak-store-spi`.** `CommitHookProvider` + `WhiteboardCommitHookProvider` add two types to a module that is rarely versioned. Adding to `oak-store-spi` IS the right home — symmetric with `EditorProvider` / `WhiteboardEditorProvider`. The SPI semantic version bumps accordingly (minor — additive). OSGi baseline check on `oak-store-spi` will require the corresponding manifest update.
3. **`oak-jcr` gains a dependency on the new `WhiteboardCommitHookProvider`.** `oak-jcr` already depends on `oak-store-spi` (transitively via `oak-core`), so no new module dependency — only a new class import in `RepositoryManager`.
4. **The `AuditConfiguration` interface in `oak-security-spi` is deleted.** This is a binary-incompatible removal — but the interface was newly added in Path α (this PR) and not yet released. The risk window is narrow to this PR's review cycle. **alex, please confirm** there are no in-flight branches outside this team using it.

---

## 8. Resolved decisions (from team review)

| Question | Resolution | Source |
|---|---|---|
| Existing `WhiteboardCommitHook` aggregator? | **NONE.** Audit is the first contributable-CommitHook consumer outside `SecurityConfiguration.getCommitHooks`. | shannon (`grep WhiteboardCommitHook` → 0 hits; only `WhiteboardUtils.getService(board, CommitHook.class)` is `AtomicCounterEditor:306`, one-shot lookup, not aggregator). |
| PostValidationHook ordering on the new path | **Preserved** by partitioning inside `MutableRoot.getCommitHook()` (§3.3). NOT inside `WhiteboardCommitHookProvider` (that would lose the split). | shannon's caveat (Design Choice 2 in her research) + sage invariant (b). The aggregator returns a flat list; `MutableRoot` partitions by marker. |
| `CommitHookProvider` SPI location | **`oak-store-spi`** (next to `EditorProvider` / `WhiteboardEditorProvider`). | alex, shannon — convention match. |
| `Oak.with(CommitHookProvider)` semantics | **Additive** (multiple calls chain). Matches multi-provider OSGi case. | self-debate, no pushback. |
| Drop `AuditConfiguration` interface? | **YES — delete entirely.** No callers after wiring removal. `AuditEvents.isEnabled()` is the externally-observable "audit deployed?" probe. | alex (grep clean across the repo); sage (no replacement typed lookup needed). |
| `AuditPipelineImpl` package — `oak.audit` vs `oak.security.audit` | **`org.apache.jackrabbit.oak.audit`** (new top-level — signals cross-domain ownership). | alex, grace; touches more imports, worth it. |
| Upgrade/sidegrade behavior change risk | **NONE.** Both bypass `Oak`/`MutableRoot` entirely; new mechanism doesn't reach them. | alex's verification (retraction). |
| Session-type gating (user-API vs internal) | **No runtime gate.** Wiring decision IS the gate. Documented in §3.8. | alex Option (i), grace concur. |
| Embedded-mode wiring shape | **Static factory `AuditPipelineImpl.create(whiteboard)` in `oak-core`.** One-line embedded use. Closest to alex's option (c) without putting impl in `oak-audit-spi`. | alex pushed for (c); accepted with placement-in-core. |
| Sage security invariants | All commitments ADOPTED: init/dispose ordering preserved; hook ordering preserved via `MutableRoot` partition; `unbindAuditConfiguration` → `@Deactivate` on the renamed class with same ordered teardown. | sage invariants (a), (b), (c). |
| **Sage Finding A — `oak.audit.events` CommitContext key** | One-line doc comment at `SnapshotAuditBufferHook.COMMIT_CONTEXT_KEY` notes the exfiltration vector under the bundle-deploy boundary. No code change. See §3.6.2a. | sage Finding A. |
| **Sage Finding B — package rename consistency** | **Move ALL 8 audit-internal classes together** from `oak.security.audit` → `oak.audit`. The 6 package-private collaborators (`AuditBuffer`, `SnapshotAuditBufferHook`, `DispatchAuditEventsHook`, `CommitMetadataDecorator`, `WhiteboardAuditEventListenerRegistry`, `NoOpAuditEventListener`) STAY package-private; OSGi Export-Package on `oak-core` MUST NOT widen to include them. Option 2 (make them public) rejected categorically. See §3.6.2. | sage Finding B. |
| **Sage Finding C — embedded-mode misconfig fail-loudness** | `AuditPipelineImpl.getCommitHooks(ws)` throws `IllegalStateException` if `initialize(Whiteboard)` was not called. Replaces Path α's silent `return List.of()`. See §3.6.2. | sage Finding C. |
| **`CommitHookProvider` Javadoc** — bundle-deploy boundary | One-sentence Javadoc note on the SPI interface declaring bundle-deploy as the security boundary (mirror of `AuditEvents.install` Javadoc lines 84-92). See §3.1. | sage invariant 2 follow-up. |
| **`MutableRoot` ctor — `Objects.requireNonNull(commitHookProvider)`** | Defensive ctor check matches existing `requireNonNull(store)` / `requireNonNull(hook)` pattern (lines 165–167). Forgotten thread-through fails at construction, not first commit. See §3.3. | sage invariant 1 follow-up. |

---

## 9. What this does NOT change

- The audit *capture sites* (`UserManagerImpl.recordSingleMembershipAuditEvent` etc.) — unchanged.
- The static `AuditEvents` façade and its `record`/`dispatch`/`isEnabled` methods — unchanged.
- The `AuditEventListener` interface and `WhiteboardAuditEventListenerRegistry` — unchanged.
- The `AuditEventEmitter` OSGi service for fire-and-forget — unchanged.
- The `SnapshotAuditBufferHook` and `DispatchAuditEventsHook` internals — unchanged (only their REACHING the commit chain changes).
- `PostValidationHook` SPI — unchanged. The marker semantics survive intact.
- The trust model (§9 of `design.md`) — unchanged.

---

## 10. Critical-path dependency call-outs

- **`MutableRoot` constructor signature change** is the riskiest line item. It's package-private (`MutableRoot(...)`) — so the only callers are `ContentSessionImpl.getLatestRoot()` and tests. grace, validate this via grep before commit.
- **OSGi baseline failure** on `oak-store-spi` is expected for the additive `CommitHookProvider` / `WhiteboardCommitHookProvider` exports. Update `oak-store-spi/pom.xml` or the bnd metadata accordingly.
- **`ContentRepositoryImpl` constructor** — also takes a new param. Same path as MutableRoot; package-private; bounded blast radius.

---

## 11. Next step

[design-freeze item 2] sent. grace proceeds with task #4; turing with cross-cutting test changes in task #6 once grace's impl lands. `design.md` §3 + §5 + new §5.x (session semantics) + §10 updates are task #9 (grace).
