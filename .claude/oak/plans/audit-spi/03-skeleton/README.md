# Audit SPI — Skeleton

This directory holds the Java skeleton files and unified diffs for the audit-spi v1 design (path α). Nothing here is committed source; it is reference material for the plan's review cycle.

## Inventory

### SPI (oak-security-spi, package `org.apache.jackrabbit.oak.spi.security.audit`)

| File | Role |
|------|------|
| `AuditConfiguration.java` | Marker interface — `extends SecurityConfiguration`. Typed lookup target for `SecurityProvider.getConfiguration(AuditConfiguration.class)`. |
| `AuditEvent.java` | Immutable value-type contract (`@ProviderType`): domain, type, timestamp, payload map. |
| `AuditEventListener.java` | Listener contract (`@ConsumerType`): `getDomain()`, `getRank()`, `onCommit(NodeState, CommitInfo, List<AuditEvent>)`. |
| `AuditEventAware.java` | Optional marker for components that emit audit events at API call sites. |
| `AuditEvents.java` | Static façade — `record(Root, AuditEvent)`, `isEnabled()`, `isEnabledFor(domain)`, `install(Sink)`. |
| `AuditBufferLifecycle.java` | Static façade — `onCommitFailed(sessionId)`, `onRefresh(sessionId)`, `install(Listener)`. |
| `SecurityAuditDomain.java` | Constant: `NAME = "security"`. |
| `SecurityAuditEvent.java` | Abstract base for security-domain events. |
| `MemberAddedEvent.java` | Concrete event — group + member path. No `performedBy` (read `commitInfo.getUserId()`). |
| `MemberRemovedEvent.java` | Concrete event — group + member path. |
| `MembersAddedBulkEvent.java` | Bulk concrete event — `{groupPath, memberIds, contentIds, failedIds}`. `memberIds` is non-empty (successful subset); `failedIds` may be empty. Emitted once per bulk operation. |
| `MembersRemovedBulkEvent.java` | Bulk concrete event — same shape. |

### Impl (oak-core, package `org.apache.jackrabbit.oak.security.audit`)

| File | Role |
|------|------|
| `AuditConfigurationImpl.java` | OSGi DS `@Component(service = {AuditConfiguration.class, SecurityConfiguration.class})`. Owns the toggle, buffer, registry, sink installation. |
| `AuditBuffer.java` | `ThreadLocal<Map<sessionId, List<AuditEvent>>>`, lazy ArrayList alloc. `peek` + `drain` + `isAllocatedOnCurrentThread` (test). Implements `AuditBufferLifecycle.Listener`. |
| `WhiteboardAuditEventListenerRegistry.java` | `AbstractServiceTracker<AuditEventListener>` + cached `volatile Set<String> activeDomains` + stable rank sort. |
| `SnapshotAuditBufferHook.java` | Regular `CommitHook` — non-destructive peek into `CommitContext`. Idempotent under merge retry. |
| `DispatchAuditEventsHook.java` | `PostValidationHook` — groups by domain, sorted-rank dispatch, per-listener try/catch. Sole drain authority (`finally` block). |
| `NoOpAuditEventListener.java` | TRACE-only listener; not auto-registered. Reference example. |

### Diffs (unified, applied with `git apply` against trunk)

| File | Target |
|------|--------|
| `MutableRoot.diff` | `oak-core/.../core/MutableRoot.java` — 1 import + 3 lifecycle calls (commit try/finally, rebase, refresh). |
| `UserManagerImpl.diff` | `oak-core/.../security/user/UserManagerImpl.java` — 5 imports + single AND bulk `onGroupUpdate` instrumentation via `recordSingleMembershipAuditEvent` / `recordBulkMembershipAuditEvent` helpers. |
| `oak-security-spi-pom.diff` | `oak-security-spi/pom.xml` — adds `org.apache.jackrabbit.oak.spi.security.audit` to the `<Export-Package>` block. |
| `InternalSecurityProvider.diff` | `oak-core/.../security/internal/InternalSecurityProvider.java` — typed audit field (`volatile`), setter, `getConfigurations()` aggregation, `getConfiguration(Class)` routing. |
| `SecurityProviderRegistration.diff` | `oak-core/.../security/internal/SecurityProviderRegistration.java` — `@Reference(OPTIONAL, DYNAMIC)` block, `bind/unbindAuditConfiguration`, `withAuditConfiguration()` wiring in `createSecurityProvider`. |
| `SecurityProviderBuilder.diff` | `oak-core/.../security/internal/SecurityProviderBuilder.java` — field + `withAuditConfiguration(...)` setter + `build()` initialization. |

All five diffs verified with `git apply --check`.

## Out of scope for v1

- **`SecurityProviderImpl`** (`oak-core/.../security/SecurityProviderImpl.java`) is `@Deprecated` and is intentionally NOT updated. Embedded users still on that constructor receive no audit pipeline — they must migrate to `SecurityProviderBuilder.newBuilder().withAuditConfiguration(...).build()`. To be called out in the release notes when the feature lands.

## Bulk membership

Bulk membership operations emit `MembersAddedBulkEvent`/`MembersRemovedBulkEvent` — one event per bulk call. The event carries both `memberIds` (successful subset, non-empty) and `failedIds` (the IDs that failed to stage, possibly empty) under distinct payload keys, so listeners can distinguish "happened" from "rejected" without risk of mis-attribution. Listeners decide whether to resolve paths via Whiteboard. Capture is skipped when `memberIds` is empty.

- **External-change events** (cross-cluster), **validator-side capture** (from NodeState diff), and **async/batching adapters** are explicitly out of scope per the design brief.

## Quality bar (verified)

- Apache 2.0 license header on every Java file.
- Java 11 baseline: no records, no sealed types, no pattern matching for switch.
- No wildcard imports.
- `@NotNull`/`@Nullable` from `org.jetbrains.annotations`.
- OSGi DS annotations on `AuditConfigurationImpl` (`@Component`, `@Activate`, `@Deactivate`, `@Designate`).
- SLF4J for all logging.
- Lazy ArrayList alloc in `AuditBuffer` (null until first `record`).
- Cached volatile `Set<String> activeDomains` in `WhiteboardAuditEventListenerRegistry`.
- `AuditEvents.isEnabled()` is at most two volatile reads (toggle + cached listener-domain set).
- No TODOs, no `UnsupportedOperationException`.
