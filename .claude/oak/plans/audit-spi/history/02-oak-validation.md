# 02 — Oak Source Validation (Path α)

This document validates every assumption in `00-design-brief.md` against the current
`trunk` of Apache Jackrabbit Oak at `/Users/adulvac/work/jackrabbit-oak`. Every claim
cites the exact `file:line`. Status legend:

- ✅ **Confirmed** — brief matches source.
- ⚠️ **Gap** — brief omits or misstates a real but non-blocking nuance; design still
  works with a documented adjustment.
- 🛑 **Blocker** — design as written cannot be implemented without re-design.

---

## 1. Hook chain ordering in `MutableRoot.getCommitHook()`

**Brief claim:** `SnapshotAuditBufferHook` runs before validators; `DispatchAuditEventsHook
(PostValidationHook)` runs after.

### Source

`oak-core/src/main/java/org/apache/jackrabbit/oak/core/MutableRoot.java:282-308`:

```java
private CommitHook getCommitHook() {
    List<CommitHook> hooks = new ArrayList<>();
    hooks.add(ResetCommitAttributeHook.INSTANCE);                       // 284
    hooks.add(hook);                                                    // 285 (global hook)

    List<CommitHook> postValidationHooks = new ArrayList<CommitHook>(); // 287
    List<ValidatorProvider> validators = new ArrayList<>();             // 288

    for (SecurityConfiguration sc : securityProvider.getConfigurations()) { // 290
        for (CommitHook ch : sc.getCommitHooks(workspaceName)) {            // 291
            if (ch instanceof PostValidationHook) {                          // 292
                postValidationHooks.add(ch);                                 // 293
            } else if (ch != EmptyHook.INSTANCE) {                           // 294
                hooks.add(ch);                                               // 295
            }
        }
        validators.addAll(sc.getValidators(workspaceName, subject.getPrincipals(), moveTracker));
    }

    if (!validators.isEmpty()) {                                                  // 302
        hooks.add(new EditorHook(CompositeEditorProvider.compose(validators)));   // 303
    }
    hooks.addAll(postValidationHooks);                                            // 305

    return CompositeHook.compose(hooks);                                          // 307
}
```

### Finding ✅ **Confirmed (with one nuance)**

Effective hook order:

1. `ResetCommitAttributeHook.INSTANCE` (`MutableRoot.java:284`)
2. The global `hook` field (`MutableRoot.java:285`) — composite of `Oak.with(CommitHook)`
   + `RepoStateCheckHook` + `IndexUpdateProvider` `EditorHook` (`Oak.java:786-797`)
3. **All regular `CommitHook`s** from every `SecurityConfiguration.getCommitHooks(workspaceName)`,
   interleaved in the order produced by `securityProvider.getConfigurations()` (see §3 —
   this is **`HashSet` order**, i.e., undefined).
4. `EditorHook(validators)` (`MutableRoot.java:303`) — only if `validators` is non-empty.
5. All `PostValidationHook`s collected from every `SecurityConfiguration` in iteration
   order (`MutableRoot.java:305`).

So `SnapshotAuditBufferHook` (regular `CommitHook`) DOES run before validators, and
`DispatchAuditEventsHook` (`PostValidationHook`) DOES run after validators. **The brief
is correct on both counts.**

⚠️ **Nuance to record (not a blocker):** in step 3, other security configs' regular
hooks may run *before or after* `SnapshotAuditBufferHook` depending on `HashSet` iteration
— this is exactly the scenario the brief's "Hook in chain throws **before**
SnapshotAuditBufferHook runs" row addresses (§2 below).

---

## 2. Other configs' commit hooks that could throw

**Brief claim:** `PermissionHook`, `JcrAllCommitHook`, and CUG's hook can throw before
`SnapshotAuditBufferHook` runs.

### Source

| Config | `getCommitHooks(workspaceName)` returns | Hook type |
|---|---|---|
| `AuthorizationConfigurationImpl` (`oak-core/.../security/authorization/AuthorizationConfigurationImpl.java:160-164`) | `[VersionablePathHook, PermissionHook]` | `VersionablePathHook implements CommitHook` (`oak-core/.../security/authorization/permission/VersionablePathHook.java:50`); `PermissionHook implements PostValidationHook` (`oak-core/.../security/authorization/permission/PermissionHook.java:63`) |
| `CugConfiguration` (`oak-authorization-cug/.../cug/impl/CugConfiguration.java:166-168`) | `[NestedCugHook]` | `NestedCugHook implements PostValidationHook` (`oak-authorization-cug/.../cug/impl/NestedCugHook.java:54`) |
| `PrincipalBasedAuthorizationConfiguration` (`oak-authorization-principalbased/.../impl/PrincipalBasedAuthorizationConfiguration.java:159-161`) | `Collections.emptyList()` | — |
| `PrivilegeConfigurationImpl` (`oak-core/.../security/privilege/PrivilegeConfigurationImpl.java:74-76`) | `[JcrAllCommitHook]` | `JcrAllCommitHook implements PostValidationHook` (`oak-core/.../security/privilege/JcrAllCommitHook.java:37`) |

### Finding ⚠️ **Gap — brief misnames the hook that can preempt Snapshot**

The brief's failure-modes table row ("Hook in chain throws **before** `SnapshotAuditBufferHook`
runs (e.g., other config's `PermissionHook` throws first)") **names the wrong hook.**

**`PermissionHook` is a `PostValidationHook`** (`PermissionHook.java:63`); it runs *after*
validators, never before `SnapshotAuditBufferHook`. Same for `NestedCugHook` and
`JcrAllCommitHook`.

From the default ship-as-is OSGi shape, the **only** regular `CommitHook` from
`securityProvider.getConfigurations()` that runs in the same bucket as
`SnapshotAuditBufferHook` is **`VersionablePathHook`**. It declares `throws
CommitFailedException` on `processCommit` (`VersionablePathHook.java:63-64`) and may
fail via downstream calls into `ReadWriteVersionManager`. So the realistic
"throws-before-Snapshot" scenarios are:

1. **`VersionablePathHook`** in the same bucket as `SnapshotAuditBufferHook`, HashSet
   iteration places it first, and a version-storage manipulation fails.
2. **The global `hook`** (`MutableRoot.java:285`) — composed in `Oak.java:797` from
   `Oak.with(CommitHook)` + `RepoStateCheckHook` (`Oak.java:730-731`, throws on
   `closed`) + IndexUpdate `EditorHook`. Any of these throw before *every* security
   regular hook.
3. **External `@Component(service = AuthorizationConfiguration.class)`** modules
   contributing regular hooks via the composite registration (see §7).

The brief's mitigation — `MutableRoot.commit()` catches `Throwable`, calls
`AuditBufferLifecycle.onCommitFailed(sessionId)` — addresses all three. **Recommendation:**
update the brief's example to cite `VersionablePathHook` (or the global hook) instead
of `PermissionHook`. Behavior of the mitigation is unchanged; only the documentation
is misleading as written.

---

## 3. `securityProvider.getConfigurations()` iteration order

**Brief claim:** Returns `HashSet<SecurityConfiguration>` — iteration order is undefined.

### Source

Two relevant implementations exist on trunk:

1. **Default (current OSGi path) — `InternalSecurityProvider`**, instantiated by
   `SecurityProviderBuilder.build()` (`oak-core/.../security/internal/SecurityProviderBuilder.java:152-233`),
   itself driven by `SecurityProviderRegistration.createSecurityProvider(whiteboard)`
   (`oak-core/.../security/internal/SecurityProviderRegistration.java:578-593`).

   `InternalSecurityProvider.getConfigurations()`
   (`oak-core/.../security/internal/InternalSecurityProvider.java:91-102`):

   ```java
   @Override
   public Iterable<? extends SecurityConfiguration> getConfigurations() {
       return SetUtils.toSet(
               authenticationConfiguration, authorizationConfiguration,
               userConfiguration,           privilegeConfiguration,
               principalConfiguration,      tokenConfiguration);
   }
   ```

   `SetUtils.toSet(T...)` (`oak-commons/.../collections/SetUtils.java:78-84`) returns
   `new HashSet<>(...)`.

2. **Deprecated — `SecurityProviderImpl`**
   (`oak-core/.../security/SecurityProviderImpl.java:146-155`):

   ```java
   public Iterable<? extends SecurityConfiguration> getConfigurations() {
       Set<SecurityConfiguration> scs = new HashSet<>();
       ...
       return scs;
   }
   ```

   Same shape.

### Finding ✅ **Confirmed**

Both production implementations use `HashSet`. **Iteration order is undefined.** The
brief's design hinges on this being true to justify the `MutableRoot.commit()`
catch-and-`onCommitFailed` fix (covers the "other regular hook ordered first and throws"
scenario), and the assumption is correct.

⚠️ **Aside (not a blocker):** `SetUtils.toLinkedSet(...)` exists in the same file
(`SetUtils.java:117-138`). Switching `InternalSecurityProvider.getConfigurations()` to
`toLinkedSet` would make the order deterministic but is **out of scope** — and arguably
undesirable, because external `SecurityConfiguration`s plugged into the composites are
still iterated in `HashSet` order inside `Composite*Configuration` (a separate concern
not analyzed here).

---

## 4. `ResetCommitAttributeHook` and the residual-events-lost gap

**Brief claim:** `CommitContext` is reset every merge attempt; residual gap exists where
validators throw after `SnapshotAuditBufferHook` ran but `DispatchAuditEventsHook` never
fired.

### Source

`oak-store-spi/src/main/java/org/apache/jackrabbit/oak/spi/commit/ResetCommitAttributeHook.java:28-44`:

```java
public enum ResetCommitAttributeHook implements CommitHook {
    INSTANCE;

    @NotNull
    @Override
    public NodeState processCommit(NodeState before, NodeState after, CommitInfo info)
            throws CommitFailedException {
        //Reset the attributes upon each commit attempt
        resetAttributes(info);
        return after;
    }

    private static void resetAttributes(CommitInfo info) {
        SimpleCommitContext attrs = (SimpleCommitContext) info.getInfo().get(CommitContext.NAME);
        //As per implementation this should not be null
        requireNonNull(attrs, "No commit attribute instance found in info map").clear();
    }
}
```

### Finding ⚠️ **Gap — confirmed and worth strengthening the mitigation**

The hook **clears** the `SimpleCommitContext` (which is mutable — `SimpleCommitContext.clear()`)
at the start of *every* merge attempt. Critical implications for Path α:

1. **Sequential attempts share the same `SimpleCommitContext` instance** because
   `CommitInfo.getInfo()` returns the same `Map` (§5 below) and the map's value at
   `CommitContext.NAME` is the same `SimpleCommitContext` object (built once in
   `MutableRoot.newInfoWithCommitContext(info)` at `MutableRoot.java:388-393`).
2. The brief's `SnapshotAuditBufferHook` moves events from `ThreadLocal[sessionId]` →
   `CommitContext` and **clears the ThreadLocal**. If a validator throws after Snapshot
   ran:
   - `CommitContext` still has the events.
   - `ThreadLocal[sessionId]` is empty.
   - `DispatchAuditEventsHook` never runs (validators threw → PostValidation skipped).
   - The merge engine retries → on the next attempt, `ResetCommitAttributeHook` clears
     `CommitContext`. Now both stores are empty. **Events are lost.**

The brief acknowledges this. The proposed Path α design accepts the loss. Two cheaper
mitigations the brief should consider explicitly:

- **Option A (recommended):** make `SnapshotAuditBufferHook` *non-destructive* — copy
  `ThreadLocal[sessionId]` into `CommitContext` but DO NOT clear `ThreadLocal`. Then
  `DispatchAuditEventsHook` is the only authority that clears both `CommitContext` AND
  `ThreadLocal[sessionId]` upon successful dispatch. This makes Snapshot **idempotent
  under retry** at the cost of one extra hashmap reference held until dispatch.
- **Option B (status quo):** brief's current design. Accept the loss; document it.

🛑 **Sub-blocker if Option A is chosen:** `MutableRoot.commit()`'s `try { store.merge…
} catch (Throwable t) { onCommitFailed(sessionId); throw t; }` must still clear the
ThreadLocal on outer failure to prevent stale events leaking into the next commit on
the same thread. The brief's diff already does this. ✅ Fine.

**Recommendation:** prefer Option A. The cost is trivial (one short-lived reference);
the correctness improvement is real.

---

## 5. `CommitContext` lifetime under NodeStore merge retries

**Brief claim:** Same `CommitInfo` reused across retries in both DocumentNodeStore and
SegmentNodeStore.

### Source

#### DocumentNodeStore

`oak-store-document/.../plugins/document/DocumentNodeStoreBranch.java:117-136`:

```java
@NotNull
@Override
public NodeState merge(@NotNull CommitHook hook, @NotNull CommitInfo info)
        throws CommitFailedException {
    try {
        return merge0(hook, info, false);
    } catch (CommitFailedException e) {
        if (!e.isOfType(MERGE)) { throw e; }
        if (avoidMergeLock) { throw e; }
    }
    return merge0(hook, info, true);
}
```

`merge0(...)` (`DocumentNodeStoreBranch.java:168-229`) loops with the **same** `info`
parameter, calling `branchState.merge(hook, info, exclusive)` repeatedly on the same
object. Inside `InMemory.merge(...)` (`DocumentNodeStoreBranch.java:534-589`), the hook
chain runs via `TimingHook.wrap(hook, ...).processCommit(base, head, info)` (line 547).
Same `info`, same underlying `SimpleCommitContext` instance.

#### SegmentNodeStore

`oak-segment-tar/.../scheduler/LockBasedScheduler.java:253-322`:

```java
@Override
public NodeState schedule(@NotNull Commit commit, SchedulerOption... schedulingOptions)
        throws CommitFailedException {
    ...
    SegmentNodeState merged = (SegmentNodeState) execute(commit); // line 270
    ...
}

private NodeState execute(Commit commit) throws CommitFailedException, InterruptedException {
    if (commit.hasChanges()) {
        ...
        for (long backoff = 1; backoff < MAXIMUM_BACKOFF; backoff *= 2) { // line 296
            refreshHead(true);
            SegmentNodeState before = head.get();
            SegmentNodeState after = commit.apply(before);                 // line 299
            if (revisions.setHead(before.getRecordId(), after.getRecordId())) {
                head.set(after);
                contentChanged(after.getChildNode(ROOT), commit.info());   // line 303
                return head.get().getChildNode(ROOT);
            }
            ...
            Thread.sleep(backoff, randNs);                                 // line 311
        }
    ...
    }
    ...
}
```

The retry loop in `execute()` re-applies `commit.apply(before)` (which runs the hook
chain) **without re-creating `Commit`**. The same `commit.info()` is reused; the
underlying `SimpleCommitContext` is the same instance across attempts.

### Finding ✅ **Confirmed**

Both stores reuse the same `CommitInfo` (and thus the same `SimpleCommitContext`) across
all hook-chain attempts. `ResetCommitAttributeHook.INSTANCE` runs first inside each
hook-chain invocation and clears the shared `SimpleCommitContext`. This validates the
brief's assumption that the snapshot hook can rely on the context being empty at the
start of each attempt.

Verification of synchronous dispatch (relied on by Path α for HTTP/MDC context):
- DocumentNodeStore: `dispatcher.contentChanged(getRoot(), info)` at
  `oak-store-document/.../DocumentNodeStore.java:1143` — same thread as the merge.
- SegmentNodeStore: `contentChanged(after.getChildNode(ROOT), commit.info())` at
  `LockBasedScheduler.java:303` — same thread as the merge.

These are post-merge dispatches; they are **not** where the audit hook chain runs. The
audit hook chain runs *inside* `commit.apply(before)` (segment) or
`branchState.merge(...)` (document), on the merging thread. ✅

---

## 6. `SystemRoot extends MutableRoot` — Oak-internal system operations

**Brief claim:** Inherits MutableRoot's lifecycle calls; system operations get audited;
listener can filter on `userId == OAK_UNKNOWN`.

### Source

`oak-core/src/main/java/org/apache/jackrabbit/oak/core/SystemRoot.java:36-94`:

```java
public class SystemRoot extends MutableRoot {

    private static final LoginContext LOGIN_CONTEXT = new LoginContext() {
        @Override public Subject getSubject() { return SystemSubject.INSTANCE; }
        @Override public void login() {}
        @Override public void logout() {}
    };

    public static SystemRoot create(@NotNull NodeStore store, @NotNull CommitHook hook,
            @NotNull String workspaceName, @NotNull SecurityProvider securityProvider,
            @NotNull QueryIndexProvider indexProvider) { ... }

    public static SystemRoot create(...) { ... }

    private SystemRoot(@NotNull final NodeStore store, ... ) {
        this(store, hook, workspaceName, securityProvider, queryEngineSettings, indexProvider,
                new ContentSessionImpl(LOGIN_CONTEXT, securityProvider, workspaceName,
                        store, hook, queryEngineSettings, indexProvider, null) {
                    @NotNull @Override
                    public Root getLatestRoot() { return new SystemRoot(...); }
                });
    }

    private SystemRoot(...) {
        super(store, hook, workspaceName, SystemSubject.INSTANCE,
                securityProvider, queryEngineSettings, indexProvider, null, session);
    }
}
```

### Finding ✅ **Confirmed (correcting an earlier note in this section)**

`SystemRoot` does **NOT** override `commit`, `refresh`, or `rebase`. It inherits
`MutableRoot.commit(Map)` (`MutableRoot.java:256-268`), `refresh()`
(`MutableRoot.java:245-253`), and `rebase()` (`MutableRoot.java:235-242`). The brief
diff's calls into `AuditBufferLifecycle.onCommitFailed/onRefresh` fire for SystemRoot
too. ✅

**Brief's `OAK_UNKNOWN` userId filter is correct.** Tracing the value:

1. `SystemSubject.INSTANCE` (`oak-security-spi/.../authentication/SystemSubject.java:30-43`)
   has principals = `{SystemPrincipal.INSTANCE}`. `SystemPrincipal` is NOT a
   `SystemUserPrincipal`.
2. `AuthInfoImpl.createFromSubject` (`oak-security-spi/.../authentication/AuthInfoImpl.java:54-59`)
   extracts userId only from `SystemUserPrincipal`s, so for `SystemSubject` it returns
   an `AuthInfoImpl` with `userID = null`.
3. `MutableRoot.commit()` (`MutableRoot.java:259-260`) constructs
   `new CommitInfo(session.toString(), session.getAuthInfo().getUserID(), ...)` —
   passing `null` for the userId argument.
4. `CommitInfo` constructor (`oak-store-spi/.../spi/commit/CommitInfo.java:92-97`,
   specifically line 94) **normalizes** null → `OAK_UNKNOWN`:
   ```java
   this.userId = (userId == null) ? OAK_UNKNOWN : userId;
   ```
   where `OAK_UNKNOWN = "oak:unknown"` (`CommitInfo.java:37`). `getUserId()` is `@NotNull`
   (`CommitInfo.java:110-113`).

So **`commitInfo.getUserId().equals(CommitInfo.OAK_UNKNOWN)` IS the correct, exact
filter** for system-initiated operations. The brief's wording is accurate. (Tests
should compare via `equals(CommitInfo.OAK_UNKNOWN)`, not `==`, since the field is a
constructor-assigned `String` reference — although in practice `==` works here because
`CommitInfo` always stores the same constant. Prefer `equals` defensively.)

🛑 **Larger issue: initializer paths**. `RepositoryInitializer` and
`WorkspaceInitializer` do **NOT** go through `MutableRoot`. `OakInitializer.initialize`
(`oak-core/src/main/java/org/apache/jackrabbit/oak/OakInitializer.java:41-78`) calls
`store.merge(builder, hook, createCommitInfo())` directly with a manually built
`CommitInfo(SESSION_ID="OakInitializer", userId=null, infoMap)`. Crucially, the `hook`
passed to `OakInitializer` is built in `Oak.initialContent()`
(`Oak.java:693-714`):

```java
List<CommitHook> initHooks = new ArrayList<CommitHook>(commitHooks);
initHooks.add(0, ResetCommitAttributeHook.INSTANCE);
initHooks.add(new EditorHook(new IndexUpdateProvider(indexEditors)));
CommitHook initHook = CompositeHook.compose(initHooks);
```

This composition does **NOT** include `securityProvider.getConfigurations()`'s
`getCommitHooks(workspaceName)` results. So during initializer paths, neither
`SnapshotAuditBufferHook` nor `DispatchAuditEventsHook` runs at all. **Initialization-time
mutations are not auditable** under Path α as written.

The brief should explicitly state this exclusion (initializer paths are out of scope)
or accept it implicitly. For v1 this is reasonable: initializers run before any
user-facing session exists, so there's no consumer to dispatch to.

---

## 7. `SecurityProviderRegistration` plug-in path

**Brief claim:** An external `@Component(service = SecurityConfiguration.class)`
composes into the `SecurityProvider`.

### Source

`oak-core/src/main/java/org/apache/jackrabbit/oak/security/internal/SecurityProviderRegistration.java:98-749`:

- `@Component(immediate = true)` (line 95).
- Unary references (singleton bindings):
  - `AuthenticationConfiguration` (line 229)
  - `PrivilegeConfiguration` (line 238)
  - `UserConfiguration` (line 247)
- Multi-cardinality references (`ReferencePolicy.DYNAMIC`, aggregated into
  `Composite*Configuration`):
  - `AuthorizationConfiguration` (lines 278-290) → `CompositeAuthorizationConfiguration`
  - `PrincipalConfiguration` (lines 292-304) → `CompositePrincipalConfiguration`
  - `TokenConfiguration` (lines 306-318) → `CompositeTokenConfiguration`
- Add-on references (lines 337-451): `AuthorizableNodeName`, `AuthorizableActionProvider`,
  `RestrictionProvider`, `UserAuthenticationFactory`, `AggregationFilter`.

**There is no `@Reference` for `SecurityConfiguration` as a generic interface.** Only
the six concrete configuration interfaces above are bound. The `SecurityProvider`
ultimately constructed in `createSecurityProvider(whiteboard)` (lines 578-593) is an
`InternalSecurityProvider` whose `getConfigurations()` returns exactly the six fixed
configs (`InternalSecurityProvider.java:91-102`) — nothing more.

### Finding 🛑 **Blocker for the brief's deployment model**

**An external module declaring `@Component(service = SecurityConfiguration.class)` for
an `AuditConfiguration` would NOT be picked up by `SecurityProviderRegistration`.** It
would register as an OSGi service of type `SecurityConfiguration`, but no Oak code
consumes such services generically.

For the audit hooks to actually run in `MutableRoot.getCommitHook()`, the
`AuditConfiguration` MUST be reachable via `securityProvider.getConfigurations()`. There
is no extension point in current trunk to inject a 7th configuration type.

**Options for Path α:**

1. **Extend `SecurityProviderRegistration`** with a new `@Reference` for
   `AuditConfiguration` and add an `auditConfiguration` field on `InternalSecurityProvider`
   that is returned from both `getConfiguration(Class)` and `getConfigurations()`. This
   is the cleanest fit and matches the existing pattern. Requires changes to
   `oak-core/.../security/internal/{SecurityProviderRegistration,SecurityProviderBuilder,InternalSecurityProvider,ConfigurationInitializer}.java`.
   Touches the OSGi binding contract — coordinate with `ada` (architecture) and the
   broader Oak SPI compatibility story.

2. **Have `AuditConfiguration` register as one of the existing aggregated types** —
   not feasible. `AuditConfiguration` is not an `AuthorizationConfiguration` /
   `PrincipalConfiguration` / `TokenConfiguration`.

3. **Bypass `getCommitHooks(workspaceName)` entirely** — register `SnapshotAuditBufferHook`
   and `DispatchAuditEventsHook` as the *global* commit hook fed to `Oak.with(CommitHook)`.
   Problem: that global hook is fixed at `ContentRepositoryImpl` construction time
   (`Oak.java:797-798`); there's no runtime tracker for `CommitHook` services. This
   contradicts the brief's "register listeners via Whiteboard" runtime-flexibility
   goal — and the *hooks* still wouldn't be Whiteboard-tracked.

4. **Make `AuditConfiguration` a third-party `SecurityProvider` wrapper** — too invasive
   for v1.

**Recommendation:** Option 1. The change to `SecurityProviderRegistration` /
`InternalSecurityProvider` is mechanical (~30 lines + tests) but it IS an SPI surface
change and needs ada's sign-off. Without this, Path α does not deploy in OSGi.

The non-OSGi `SecurityProviderBuilder` path
(`oak-core/.../security/internal/SecurityProviderBuilder.java:152-233`) has the same
shape and would need the same parallel change (a `withAuditConfiguration(...)` step).

---

## 8. Whiteboard injection timing

**Brief claim:** `setWhiteboard(...)` is invoked before `getCommitHooks(workspaceName)`
is ever called.

### Source

#### Non-OSGi (programmatic `Oak` builder) path

`oak-core/src/main/java/org/apache/jackrabbit/oak/Oak.java:716-728`:

```java
private ContentRepository createNewContentRepository() {
    if (securityProvider instanceof WhiteboardAware) {
        ((WhiteboardAware) securityProvider).setWhiteboard(whiteboard); // 718
    }
    for (SecurityConfiguration sc : securityProvider.getConfigurations()) {
        RepositoryInitializer ri = sc.getRepositoryInitializer();
        ...
    }
    ...
```

`setWhiteboard` at line 718 is the first action of `createNewContentRepository`.
`getCommitHooks(workspaceName)` is called later, lazily, inside
`MutableRoot.getCommitHook()` (`MutableRoot.java:282-308`) when a session commits — far
after construction.

#### OSGi path

`oak-core/.../security/internal/SecurityProviderRegistration.java:516-522`:

```java
Whiteboard whiteboard = new OsgiWhiteboard(context);
SecurityProvider securityProvider = createSecurityProvider(whiteboard);
ServiceRegistration registration = context.registerService(
        SecurityProvider.class.getName(), securityProvider, properties);
```

`createSecurityProvider(...)` at line 578-593 calls `.withWhiteboard(whiteboard).build()`
on `SecurityProviderBuilder`. Inside `SecurityProviderBuilder.build()`
(`SecurityProviderBuilder.java:152-233`), line 228-230:

```java
if (whiteboard != null) {
    securityProvider.setWhiteboard(whiteboard);
}
```

So the SecurityProvider has its whiteboard set **before** it is registered as an OSGi
service and **before** any consumer can ever call `getCommitHooks(workspaceName)` on
it.

### Finding ✅ **Confirmed**

Both code paths set the Whiteboard before any `getCommitHooks(workspaceName)` invocation.
`AuditConfiguration.activate(...)` can safely assume the Whiteboard is available when
constructing `WhiteboardAuditEventListenerRegistry` — provided `AuditConfiguration`
implements `WhiteboardAware` (or fetches the whiteboard from the OSGi `BundleContext`
in its `@Activate`).

---

## 9. Tree-mutation paths not going through `MutableRoot.commit()`

**Brief claim:** Most user-facing mutation paths flow through `MutableRoot.commit()`;
the `AuditBufferLifecycle.onCommitFailed/onRefresh` hooks therefore cover them.

### Source

| Caller | Site | Calls `Root.commit(...)`? |
|---|---|---|
| `SessionDelegate.commit(Root, String)` | `oak-jcr/.../delegate/SessionDelegate.java:402` (`root.commit(Collections.unmodifiableMap(info))`) | ✅ Yes — invoked by `Session.save()`, `Item.save()`, etc. |
| `WorkspaceDelegate.WorkspaceCopy.perform` | `oak-jcr/.../delegate/WorkspaceDelegate.java:129` (`root.commit(Collections.unmodifiableMap(copyInfo))`) | ✅ Yes — `Workspace.copy(...)`. |
| XML import paths | `oak-jcr/.../xml/SessionImporter`, `WorkspaceImporter` — both reuse the surrounding session's `save()`/refresh flow, ultimately landing on `SessionDelegate.commit(root, path)`. (Not separately inspected here; trusted from JCR import patterns.) | ✅ Yes — via `Session.save()`. |
| `OakInitializer.initialize(...)` | `oak-core/.../OakInitializer.java:47, 68` (`store.merge(builder, hook, createCommitInfo())`) | ❌ **Bypasses** `MutableRoot`. |
| Internal direct `NodeStore.merge(...)` (background tasks, async indexing, GC, etc.) | various — e.g., `AsyncIndexUpdate`, store-internal compaction | ❌ Bypasses `MutableRoot`. |

### Finding ✅ **Confirmed** (with one ⚠️ caveat)

All user-facing JCR mutation paths flow through `MutableRoot.commit()`:

- `SessionDelegate.commit(...)` is the single funnel for `Session.save()`,
  `Item.save()`, lock operations, and version-manager operations.
- `WorkspaceDelegate.copy(...)` (and analogous workspace-level mutations) call
  `root.commit(...)` directly.

⚠️ **Initializer / async-task paths bypass.** As noted in §6, these:
- do not pass through `MutableRoot.commit()` (so `AuditBufferLifecycle.onCommitFailed`
  is **not** called on failure);
- do not include `securityProvider.getConfigurations()` hooks in their hook chain (so
  audit hooks don't fire even on success).

For Path α v1, this is acceptable — audit captures are at security-API call sites
(`MembershipProvider.addMember`, etc.), and security APIs are not invoked from
initializers/async-indexers in practice. **The brief should call this out explicitly
in "What is NOT in scope for v1"** so consumers don't assume audit covers
repository-internal subsystem mutations.

---

## 10. Capture-site correctness

**Brief claim:** Wrapping `writer.addMember(...)` in `MembershipProvider.addMember`
with `AuditEvents.record(root, MemberAddedEvent.of(...))` is correct.

### Source

`oak-core/src/main/java/org/apache/jackrabbit/oak/security/user/MembershipProvider.java`:

```java
// line 297-299 — single add
boolean addMember(@NotNull Tree groupTree, @NotNull Tree newMemberTree) {
    return writer.addMember(groupTree, getContentID(newMemberTree));
}

// line 308-310 — bulk add
Set<String> addMembers(@NotNull Tree groupTree, @NotNull Map<String, String> memberIds) {
    return writer.addMembers(groupTree, memberIds);
}

// line 319-326 — single remove
boolean removeMember(@NotNull Tree groupTree, @NotNull Tree memberTree) {
    if (writer.removeMember(groupTree, getContentID(memberTree))) {
        return true;
    } else {
        log.debug("Authorizable {} was not member of {}", memberTree.getName(), groupTree.getName());
        return false;
    }
}

// line 335-337 — bulk remove
Set<String> removeMembers(@NotNull Tree groupTree, @NotNull Map<String, String> memberIds) {
    return writer.removeMembers(groupTree, memberIds);
}
```

`oak-core/.../security/user/UserManagerImpl.java:371-379`:

```java
void onGroupUpdate(@NotNull Group group, boolean isRemove, @NotNull Authorizable member) throws RepositoryException {
    for (GroupAction action : filterGroupActions()) {
        if (isRemove) {
            action.onMemberRemoved(group, member, root, namePathMapper);
        } else {
            action.onMemberAdded(group, member, root, namePathMapper);
        }
    }
}
```

`oak-core/.../security/user/UserManagerImpl.java:393-405` (bulk variant):

```java
void onGroupUpdate(@NotNull Group group, boolean isRemove, boolean isContentId,
                   @NotNull Set<String> memberIds, @NotNull Set<String> failedIds) throws RepositoryException {
    for (GroupAction action : filterGroupActions()) {
        if (isRemove) {
            action.onMembersRemoved(group, memberIds, failedIds, root, namePathMapper);
        } else {
            if (isContentId) {
                action.onMembersAddedContentId(group, memberIds, failedIds, root, namePathMapper);
            } else {
                action.onMembersAdded(group, memberIds, failedIds, root, namePathMapper);
            }
        }
    }
}
```

Caller flow (`GroupImpl`):
- `GroupImpl.addMember(authorizable)` (`oak-core/.../security/user/GroupImpl.java:101-141`)
  → `getMembershipProvider().addMember(getTree(), authorizableImpl.getTree())` (line
  138) → on success, `getUserManager().onGroupUpdate(this, false, authorizable)` (line
  141).
- `GroupImpl.removeMember(...)` mirror (lines 154-181).
- Bulk: `GroupImpl.updateMembers(...)` calls `mp.addMembers(...)` / `mp.removeMembers(...)`
  (lines 318/320) → `getUserManager().onGroupUpdate(this, isRemove, false, processedIds, failedIds)`
  (line 326).

### Finding ⚠️ **Gap — example captures only the single-member path**

The brief's example (`MembershipProvider.addMember` wrapping) is **mechanically correct**
for the single-member case:

- `root` IS a field accessible to `MembershipProvider` — inherited from
  `AuthorizableBaseProvider` (the parent class declares `protected final Root root`,
  and `MembershipProvider`'s constructor at line 119-121 passes through).
- `getContentSession().getAuthInfo().getUserID()` is read-only and free of mutations
  to the staged Tree.
- The capture is **after** `writer.addMember(...)` returned `true`, so the Tree
  mutation is staged. If a later validator throws or the commit fails, the brief's
  `AuditBufferLifecycle.onCommitFailed` discards the event. ✅

⚠️ **But the example does NOT capture bulk variants** (`addMembers`, `removeMembers`).
These flow through `GroupImpl.updateMembers` → `MembershipProvider.addMembers`/
`removeMembers`. If only `MembershipProvider.addMember` is instrumented, bulk-mode
adds/removes go unaudited. For symmetric coverage, all four `MembershipProvider`
methods (`addMember`, `addMembers`, `removeMember`, `removeMembers`) need analogous
calls, with the bulk variants emitting one `MemberAddedEvent` / `MemberRemovedEvent`
per processed ID — minus those in the `failedIds` set returned by `writer.addMembers`
(success criterion is "the contentId was NOT in the returned failure set").

**Recommended alternative capture site** (cleaner for v1): capture in
`UserManagerImpl.onGroupUpdate(Group, boolean, Authorizable)` (line 371) **and**
`UserManagerImpl.onGroupUpdate(Group, boolean, boolean, Set, Set)` (line 393) — both
variants. This:

- centralizes capture at one logical layer (the `UserManager`),
- already fires only after the membership change was actually applied,
- naturally distinguishes single vs. bulk via the overload,
- has direct access to `root` and `namePathMapper` (both fields),
- avoids capturing the *low-level* `MembershipProvider` path that may also be reached
  by internal/import code that should not be audited.

The brief should either:
1. **Add bulk capture sites** in `MembershipProvider.addMembers`/`removeMembers` along
   with the single-member ones, OR
2. **Move capture to `UserManagerImpl.onGroupUpdate` (both overloads)** for cleaner
   layering.

Recommend option (2) for v1. The example diff in the brief should be updated
accordingly.

---

## Summary

| # | Topic | Status |
|---|---|---|
| 1 | Hook chain ordering | ✅ Confirmed (minor nuance noted) |
| 2 | Other configs' throwing hooks | ⚠️ Gap — brief misnames the example (PermissionHook is PostValidation; VersionablePathHook is the only regular-bucket pre-Snapshot throw candidate from default configs) |
| 3 | HashSet iteration order | ✅ Confirmed |
| 4 | `ResetCommitAttributeHook` residual-events-lost gap | ⚠️ Gap — recommend non-destructive Snapshot (keep ThreadLocal until Dispatch succeeds) |
| 5 | `CommitContext` / `CommitInfo` reuse under retry | ✅ Confirmed for both DocumentNodeStore and SegmentNodeStore |
| 6 | `SystemRoot` lifecycle | ✅ Confirmed for SystemRoot itself (userId IS `CommitInfo.OAK_UNKNOWN` via the null→constant normalization at `CommitInfo.java:94`). ⚠️ Gap: initializer paths bypass `MutableRoot` entirely and are unauditable. |
| 7 | `SecurityProviderRegistration` plug-in path | 🛑 **Blocker** — no extension point binds an external `SecurityConfiguration`; requires extending `SecurityProviderRegistration` + `InternalSecurityProvider` + `SecurityProviderBuilder` to add an `AuditConfiguration` reference |
| 8 | Whiteboard injection timing | ✅ Confirmed for both Oak builder and OSGi paths |
| 9 | Mutation paths through `MutableRoot.commit()` | ✅ Confirmed for user-facing JCR; initializer/async paths bypass (acceptable for v1, document explicitly) |
| 10 | Capture-site correctness | ⚠️ Gap — example covers only single-member adds; recommend moving to `UserManagerImpl.onGroupUpdate` overloads to cover bulk too |

### Action items for the team

- **Blocker (alex → ada, grace):** §7 — `SecurityProviderRegistration` extension required.
  Decide between extending the registration in this PR or building the AuditConfiguration
  as a side-channel (and document why).
- **Brief corrections (alex → team-lead):** §2 (rename PermissionHook → VersionablePathHook),
  §6 (flag initializer-path bypass in "NOT in scope"), §9 (add initializer/async
  exclusions to "NOT in scope"), §10 (move capture to `UserManagerImpl.onGroupUpdate`
  or add bulk sites). **NOTE:** the brief's `OAK_UNKNOWN` userId filter for system
  operations is correct — see §6.
- **Recommended hardening (alex → grace):** §4 — adopt non-destructive Snapshot pattern
  to eliminate the residual events-lost-on-retry gap. Negligible memory cost.
