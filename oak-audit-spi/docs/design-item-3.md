# Item 3 — Drop typed AuditEvent subclass hierarchy

**Status:** FROZEN
**Author:** ada
**Reviewer feedback addressed:** Option (d) — drop the typed AuditEvent subclass hierarchy.
**Related:** `design.md` §4.1 (AuditEvent), §10 (Migration).
**Resolutions baked in below:** sage's 4 security commitments (shallow-copy doc, IAE on blank domain/type, `@throws` doc on bulk helper, credential `@apiNote`); alex's Oak-convention alignment (`PrivilegeUtil`/`UserUtil` precedent, skip `implements` clause); grace's allocation analysis + impl-class naming; shannon's "no external consumer" reality-check.

---

## 1. Problem statement

Path α ships five public classes in `oak-security-spi`:

| Class | Role |
|---|---|
| `SecurityAuditEvent` (abstract) | Base — pins `getDomain() == "security"`, captures `type` + `timestamp` in ctor |
| `MemberAddedEvent` | Concrete event for `user.member.added` |
| `MemberRemovedEvent` | Concrete event for `user.member.removed` |
| `MembersAddedBulkEvent` | Concrete event for `user.members.added.bulk` |
| `MembersRemovedBulkEvent` | Concrete event for `user.members.removed.bulk` |

Reviewer's two objections:

1. **Vocabulary leak.** User-management terms (`MemberAdded`, `MembersBulk`) live on a stable SPI surface. The SPI should be domain-neutral; the security module's choices shouldn't become SPI commitments.
2. **Class proliferation.** Every newly-audited operation (ACL change, principal mutation, token issue, login event…) means a new public class. The cost compounds — N events × per-class Javadoc × per-class test × per-class binary-compatibility maintenance.

The reviewer's direction: **public SPI keeps only the `AuditEvent` interface; capture sites build events via a factory; consumers discriminate by `domain + type` strings.**

---

## 2. Design

### 2.1 Factory shape — `AuditEvent.of(domain, type, payload)` on the interface

The single public construction entry point is a `static` factory on the `AuditEvent` interface itself, backed by a package-private `SimpleAuditEvent` implementation.

```java
package org.apache.jackrabbit.oak.spi.audit;

@ProviderType
public interface AuditEvent {

    @NotNull String getDomain();
    @NotNull String getType();
    long getTimestamp();
    @NotNull default Map<String, Object> getPayload() { return Collections.emptyMap(); }

    /**
     * Creates an audit event with the current wall-clock timestamp.
     * The payload Map is defensively copied via {@link Map#copyOf}; the
     * caller's Map reference is decoupled from the event.
     *
     * @throws IllegalArgumentException if {@code domain} or {@code type} is blank.
     *
     * @apiNote <strong>Shallow-copy semantics.</strong> {@code Map.copyOf}
     *          decouples the caller's Map reference but does NOT clone
     *          payload <em>values</em>. Callers MUST pass immutable values
     *          (Strings, boxed primitives, {@code List.copyOf(...)},
     *          {@code Set.copyOf(...)}). Mutating a payload value after passing
     *          it to {@code of(...)} produces undefined dispatch behavior on
     *          the commit-attached path, where capture and dispatch are
     *          separated by the surrounding commit.
     *
     *          <h3>Security warning</h3>
     *          The {@code payload} map values are forwarded verbatim to
     *          listeners. Callers MUST NOT pass:
     *          <ul>
     *            <li>Any {@code javax.jcr.Credentials} subtype.</li>
     *            <li>The value of a {@code rep:password} or {@code rep:credentials} property.</li>
     *            <li>Any token-bearing object (e.g. {@code TokenInfo},
     *                {@code TokenCredentials}, raw token strings).</li>
     *            <li>Any node, property, or value that could transitively expose such
     *                data (e.g. a {@code Node} pointing at a {@code rep:User} subtree).</li>
     *          </ul>
     *          Pass user identifiers, paths, timestamps, and other non-sensitive
     *          scalars only. <strong>Oak does not redact or filter the payload at
     *          dispatch.</strong> See §9 of {@code design.md} for the producer-side
     *          responsibility under the open trust model.
     */
    @NotNull
    static AuditEvent of(@NotNull String domain,
                         @NotNull String type,
                         @NotNull Map<String, Object> payload) {
        if (domain.isBlank()) throw new IllegalArgumentException("domain must not be blank");
        if (type.isBlank())   throw new IllegalArgumentException("type must not be blank");
        return new AuditEventImpl(domain, type, System.currentTimeMillis(), Map.copyOf(payload));
    }

    /**
     * Convenience overload for events with no payload.
     *
     * @throws IllegalArgumentException if {@code domain} or {@code type} is blank.
     */
    @NotNull
    static AuditEvent of(@NotNull String domain, @NotNull String type) {
        return of(domain, type, Map.of());
    }
}

// Package-private — NOT part of the SPI surface.
final class AuditEventImpl implements AuditEvent {
    private final String domain;
    private final String type;
    private final long timestamp;
    private final Map<String, Object> payload;

    AuditEventImpl(String domain, String type, long timestamp, Map<String, Object> payload) {
        this.domain = domain;
        this.type = type;
        this.timestamp = timestamp;
        this.payload = payload;          // already-immutable Map.copyOf result
    }

    @NotNull @Override public String getDomain() { return domain; }
    @NotNull @Override public String getType()   { return type; }
    @Override public long getTimestamp()         { return timestamp; }
    @NotNull @Override public Map<String, Object> getPayload() { return payload; }
}
```

**Why this shape (vs alternatives):**

| Option | Decision | Rationale |
|---|---|---|
| **Static factory on interface (`AuditEvent.of`)** | ✅ CHOSEN | One public type, package-private impl, one-line construction at call sites. Smallest possible SPI surface. |
| Public `AuditEventImpl` / `SimpleAuditEvent` class | ❌ Rejected | Reintroduces the very leak we're removing — a concrete public class on the SPI. Even if "simple", it widens the surface and invites consumers to depend on the class identity. |
| `AuditEvent.of(domain, type, Object... kvPairs)` varargs k-v | ❌ Rejected | Slightly nicer at call sites, but runtime odd-length check; varargs allocates an `Object[]` anyway. Sage initially preferred this for credential-leak prevention; conceded the structural argument (the underlying SPI exposes `getPayload() : Map`, so varargs is cosmetic at the SPI level). The `@apiNote` credential warning + `SecurityAuditEvents` typed helpers are the real mitigation. |
| `AuditEvent.builder()` fluent builder | ❌ Rejected for v1 | Most security events carry 2-5 keys; `Map.of(...)` is just as readable as a builder, with two fewer allocations. Builder also adds a class. Revisit only if a real event needs variable-arity / optional keys. |
| `AuditEvents.event(domain, type, payload)` on the façade | ❌ Rejected | Conflates two concerns. `AuditEvents` is the dispatch façade (`record`/`dispatch`/`isEnabled`). Construction belongs with the value type. |

**Trust-contract note (carry-over from §4.1 of design.md):**
`Map.copyOf` ensures payload-Map immutability after construction. **Shallow-copy semantics:** payload values are stored by reference. The defense against value mutation lives at capture sites — the `SecurityAuditEvents` helpers always `List.copyOf` / `Set.copyOf` their inputs. Documenting this at the factory Javadoc and concentrating the discipline in the helper class is the chosen mitigation; deep-copy is rejected as too expensive and not generalizable to arbitrary nested types.

The drain-time decoration in `DispatchAuditEventsHook` builds a new payload Map for the `commit.*` keys and wraps the event in a *new* `AuditEvent` instance — it does NOT mutate the caller's payload. That invariant is unchanged.

**Naming note:** `AuditEventImpl` was chosen over `SimpleAuditEvent` / `DefaultAuditEvent`. Oak's `Default<X>` convention is reserved for *abstract* inner classes on SPI interfaces (`SecurityConfiguration.Default`, etc.) — concrete package-private value-holders use `*Impl`. The class is package-private and never seen by consumers; the name is internal.

### 2.2 Type-string constants — `SecurityAuditTypes` class

Type strings must NOT be inlined at capture sites. Inline strings have three failure modes:

- Typos go undetected at compile time (`"user.member.aded"` is valid Java).
- Consumers (listeners) cannot autocomplete or enumerate known types.
- Future evolution (type-registry, type→payload-schema map) has nothing to anchor on.

The constants live in a new class in `oak-security-spi`:

```java
package org.apache.jackrabbit.oak.spi.security.audit;

/**
 * Stable type-string constants for events in the
 * {@link SecurityAuditDomain security} domain, plus their payload keys.
 *
 * <p>Each {@code USER_*} / {@code ACL_*} / {@code …} constant is paired with
 * Javadoc describing which {@code PAYLOAD_*} keys its events carry.
 */
public final class SecurityAuditTypes {

    // ── Type strings ──────────────────────────────────────────────────

    /**
     * Recorded when a single authorizable is added as a member of a group.
     * Payload keys: {@link #PAYLOAD_GROUP_PATH}, {@link #PAYLOAD_MEMBER_PATH}.
     */
    public static final String USER_MEMBER_ADDED        = "user.member.added";

    /**
     * Recorded when a single authorizable is removed from a group.
     * Payload keys: {@link #PAYLOAD_GROUP_PATH}, {@link #PAYLOAD_MEMBER_PATH}.
     */
    public static final String USER_MEMBER_REMOVED      = "user.member.removed";

    /**
     * Recorded when multiple authorizables are added to a group in a single API call.
     * Payload keys: {@link #PAYLOAD_GROUP_PATH}, {@link #PAYLOAD_MEMBER_IDS},
     * {@link #PAYLOAD_IS_CONTENT_ID}, {@link #PAYLOAD_FAILED_IDS}.
     */
    public static final String USER_MEMBERS_ADDED_BULK  = "user.members.added.bulk";

    /**
     * Recorded when multiple authorizables are removed from a group in a single API call.
     * Payload keys: same as {@link #USER_MEMBERS_ADDED_BULK}.
     */
    public static final String USER_MEMBERS_REMOVED_BULK = "user.members.removed.bulk";

    // ── Payload keys ──────────────────────────────────────────────────

    /** Group path. Value type: {@code String}. */
    public static final String PAYLOAD_GROUP_PATH   = "groupPath";

    /** Member path. Value type: {@code String}. */
    public static final String PAYLOAD_MEMBER_PATH  = "memberPath";

    /** Successfully-staged member IDs. Value type: {@code List<String>}. */
    public static final String PAYLOAD_MEMBER_IDS   = "memberIds";

    /** {@code true} when {@link #PAYLOAD_MEMBER_IDS} carries content IDs (UUIDs from {@code rep:members}). Value type: {@code Boolean}. */
    public static final String PAYLOAD_IS_CONTENT_ID = "isContentId";

    /** IDs that failed to stage. Value type: {@code List<String>}; may be empty but never null. */
    public static final String PAYLOAD_FAILED_IDS   = "failedIds";

    private SecurityAuditTypes() {
        // constants
    }
}
```

**`SecurityAuditTypes` is a `public final class` with private constructor** — NOT a constants interface. This follows Effective Java Item 22 (the modern idiom) and matches Oak's existing pattern in places that have migrated away from `*Constants` interfaces. Helper class `SecurityAuditEvents` (§2.3) references the constants directly via `SecurityAuditTypes.USER_MEMBER_ADDED` etc.; no `implements SecurityAuditTypes` clause (it's a class, not an interface). A short Javadoc note on `SecurityAuditTypes` records the rationale so a future maintainer doesn't 'fix' it to align with `PrivilegeConstants` (an older interface-based constants holder).

**Why one combined class for types + payload keys** (vs `SecurityAuditTypes` + `SecurityAuditPayloadKeys` split):

- The relationship `type → its payload keys` is the most-asked question by a listener developer (*"what's in the payload of `user.member.added`?"*). Co-locating types and keys in one class makes that lookup one click in the IDE.
- Payload keys are reused across multiple types (`PAYLOAD_GROUP_PATH` is in all four current types). Splitting would either duplicate keys or scatter the relationship; combined is simpler.
- Class is internal organization of `oak-security-spi`; not exposed in `oak-audit-spi`, so it carries no domain-neutral commitment.

The existing `SecurityAuditDomain.NAME = "security"` constant **stays as-is** — same class, single-responsibility (just the domain identifier).

### 2.3 Helper layer — `SecurityAuditEvents` (DECIDED: KEEP)

Without a helper class, capture sites look like:

```java
// in UserManagerImpl.recordSingleMembershipAuditEvent
AuditEvents.record(root, AuditEvent.of(
        SecurityAuditDomain.NAME,
        isRemove ? SecurityAuditTypes.USER_MEMBER_REMOVED
                 : SecurityAuditTypes.USER_MEMBER_ADDED,
        Map.of(SecurityAuditTypes.PAYLOAD_GROUP_PATH,  groupPath,
               SecurityAuditTypes.PAYLOAD_MEMBER_PATH, memberPath)));
```

This is **6 lines** vs the current **3 lines** with `MemberAddedEvent.of(groupPath, memberPath)`. Verbosity is the cost of flexibility.

**Mitigation: a thin helper class in `oak-security-spi`** that wraps the factory + constants:

```java
package org.apache.jackrabbit.oak.spi.security.audit;

/**
 * Factory helpers for security-domain audit events. Convenience layer over
 * {@link AuditEvent#of} that centralizes type-string and payload-key references
 * for capture sites in Oak's security modules.
 *
 * <p>This class is part of Oak's internal organization of the {@code "security"}
 * audit domain; consumers (listeners, downstream tooling) should NOT depend on
 * its method shapes — they receive plain {@link AuditEvent} instances and
 * discriminate via {@link AuditEvent#getDomain()} + {@link AuditEvent#getType()}.
 */
public final class SecurityAuditEvents {

    @NotNull
    public static AuditEvent memberAdded(@NotNull String groupPath, @NotNull String memberPath) {
        return AuditEvent.of(SecurityAuditDomain.NAME,
                SecurityAuditTypes.USER_MEMBER_ADDED,
                Map.of(SecurityAuditTypes.PAYLOAD_GROUP_PATH,  groupPath,
                       SecurityAuditTypes.PAYLOAD_MEMBER_PATH, memberPath));
    }

    @NotNull
    public static AuditEvent memberRemoved(@NotNull String groupPath, @NotNull String memberPath) {
        return AuditEvent.of(SecurityAuditDomain.NAME,
                SecurityAuditTypes.USER_MEMBER_REMOVED,
                Map.of(SecurityAuditTypes.PAYLOAD_GROUP_PATH,  groupPath,
                       SecurityAuditTypes.PAYLOAD_MEMBER_PATH, memberPath));
    }

    /**
     * @throws IllegalArgumentException if {@code memberIds} is empty.
     *         Capture sites MUST pre-check {@code memberIds.isEmpty()} before
     *         calling this method; an empty bulk event has no semantic meaning,
     *         and {@code UserManagerImpl.recordBulkMembershipAuditEvent} already
     *         enforces this gate at its capture site.
     */
    @NotNull
    public static AuditEvent membersAddedBulk(@NotNull String groupPath,
                                              @NotNull Set<String> memberIds,
                                              boolean isContentId,
                                              @NotNull Set<String> failedIds) {
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds must not be empty");
        }
        return AuditEvent.of(SecurityAuditDomain.NAME,
                SecurityAuditTypes.USER_MEMBERS_ADDED_BULK,
                Map.of(SecurityAuditTypes.PAYLOAD_GROUP_PATH,    groupPath,
                       SecurityAuditTypes.PAYLOAD_MEMBER_IDS,    List.copyOf(memberIds),
                       SecurityAuditTypes.PAYLOAD_IS_CONTENT_ID, isContentId,
                       SecurityAuditTypes.PAYLOAD_FAILED_IDS,    List.copyOf(failedIds)));
    }

    /**
     * @throws IllegalArgumentException if {@code memberIds} is empty.
     *         Capture sites MUST pre-check {@code memberIds.isEmpty()} before
     *         calling this method (see {@link #membersAddedBulk}).
     */
    @NotNull
    public static AuditEvent membersRemovedBulk(@NotNull String groupPath,
                                                @NotNull Set<String> memberIds,
                                                boolean isContentId,
                                                @NotNull Set<String> failedIds) {
        if (memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds must not be empty");
        }
        return AuditEvent.of(SecurityAuditDomain.NAME,
                SecurityAuditTypes.USER_MEMBERS_REMOVED_BULK,
                Map.of(SecurityAuditTypes.PAYLOAD_GROUP_PATH,    groupPath,
                       SecurityAuditTypes.PAYLOAD_MEMBER_IDS,    List.copyOf(memberIds),
                       SecurityAuditTypes.PAYLOAD_IS_CONTENT_ID, isContentId,
                       SecurityAuditTypes.PAYLOAD_FAILED_IDS,    List.copyOf(failedIds)));
    }

    private SecurityAuditEvents() {
        // utility
    }
}
```

Capture site collapses back to **2 lines**:

```java
AuditEvents.record(root, isRemove
        ? SecurityAuditEvents.memberRemoved(groupPath, memberPath)
        : SecurityAuditEvents.memberAdded(groupPath, memberPath));
```

**Why this is not the leak the reviewer objected to:**

| Reviewer's concern with the old design | How `SecurityAuditEvents` differs |
|---|---|
| Public CLASSES per event in the stable SPI | One class. Adding an ACL event = adding a METHOD, not a class. Adding methods is binary-compatible. |
| Domain-neutral SPI surface knows user-management terms | `SecurityAuditEvents` lives in `oak-security-spi`, NOT `oak-audit-spi`. The domain-neutral SPI (`AuditEvent`, `AuditEventListener`, `AuditEventEmitter`, `AuditEvents` façade) doesn't see it. |
| Type system enforces "if instanceof MemberAddedEvent, …" | `SecurityAuditEvents.memberAdded(...)` returns the bare `AuditEvent` interface. Consumers cannot `instanceof`-check; they MUST use `getDomain() + getType()`. |
| N classes × N test classes × N Javadoc files | One class, one test class, one Javadoc file. Per-event delta is ~10 lines + Javadoc on the new constant. |

**DECIDED:** KEEP `SecurityAuditEvents`. Aligns with the established `PrivilegeUtil`/`UserUtil` precedent in `oak-security-spi` — `public final class` with static helper methods returning interface-typed values. Sage strong-keeps (centralizes both type-string discipline AND payload-value `List.copyOf` discipline — the shallow-copy mitigation lives here). Grace strong-keeps (8 lines → 3 lines for bulk capture sites; centralizes the IAE empty-bulk guard). Alex confirms the convention match.

**Convention codification (alex):** v1 keeps the convention informal — a single Javadoc note on `SecurityAuditEvents` ("other audit-event consumers (ACL, principal, token) MAY follow this pattern, providing their own per-domain helper class that returns the bare `AuditEvent` interface"). Revisit formalization in `oak-audit-spi` package Javadoc when a second domain implements an audit-event helper.

### 2.4 Files removed

```
oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/
  SecurityAuditEvent.java          DELETE
  MemberAddedEvent.java            DELETE
  MemberRemovedEvent.java          DELETE
  MembersAddedBulkEvent.java       DELETE
  MembersRemovedBulkEvent.java     DELETE

oak-security-spi/src/test/java/org/apache/jackrabbit/oak/spi/security/audit/
  SecurityAuditEventTest.java       DELETE (class no longer exists)
  MemberAddedEventTest.java         DELETE (semantics moved — see §3.3)
  MemberRemovedEventTest.java       DELETE
  MembersAddedBulkEventTest.java    DELETE
  MembersRemovedBulkEventTest.java  DELETE
```

### 2.5 Files added

```
oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/
  AuditEventImpl.java                            ADD (package-private impl of AuditEvent)

oak-security-spi/src/main/java/org/apache/jackrabbit/oak/spi/security/audit/
  SecurityAuditTypes.java                        ADD (type-string + payload-key constants)
  SecurityAuditEvents.java                       ADD (helper factories — see §2.3 open question)

oak-audit-spi/src/test/java/org/apache/jackrabbit/oak/spi/audit/
  AuditEventImplTest.java                        ADD
  AuditEventTest.java                            EXTEND (cover the two static factories)

oak-security-spi/src/test/java/org/apache/jackrabbit/oak/spi/security/audit/
  SecurityAuditTypesTest.java                    ADD (string equality, payload-key uniqueness)
  SecurityAuditEventsTest.java                   ADD (one test per factory method — bulk validation)
```

### 2.6 Files modified

```
oak-audit-spi/src/main/java/org/apache/jackrabbit/oak/spi/audit/AuditEvent.java
  + ADD static factory methods of(domain, type, payload) and of(domain, type)

oak-core/src/main/java/org/apache/jackrabbit/oak/security/user/UserManagerImpl.java
  - REMOVE imports for the four event subclasses
  + ADD import for SecurityAuditEvents
  - REPLACE call sites in recordSingleMembershipAuditEvent and
    recordBulkMembershipAuditEvent to use SecurityAuditEvents.* factories

oak-core/src/test/java/org/apache/jackrabbit/oak/security/audit/AuditWiringIT.java
  - ADJUST assertions that match by class type (instanceof MemberAddedEvent)
    to match by domain + type strings

oak-benchmarks/src/main/java/org/apache/jackrabbit/oak/benchmark/AuditCaptureSiteOverheadTest.java
  - JAVADOC ONLY at line 34 (alex verified: no `import` of the typed event classes; no compile dep).
    UPDATE the Javadoc reference from `MemberAddedEvent.of(...)` / `MemberRemovedEvent.of(...)` to
    `SecurityAuditEvents.memberAdded(...)` / `SecurityAuditEvents.memberRemoved(...)` so the doc
    points at the live API.

oak-audit-spi/docs/design.md
  - UPDATE §4.1 (AuditEvent) — add static factory section
  - UPDATE §10 (Migration) — reflect class removals and added constants/helper
  - ADD reference to this design-item-3.md note
```

---

## 3. Migration plan

### 3.1 Production code

1. **Add `SimpleAuditEvent` + factory methods** on `AuditEvent` — backward-compatible additive change to `oak-audit-spi`.
2. **Add `SecurityAuditTypes` + `SecurityAuditEvents`** in `oak-security-spi`.
3. **Migrate `UserManagerImpl`** capture sites (4 call sites — single + bulk × add/remove are in 2 helper methods at lines 419 and 444). One-shot replacement; no transition period needed because nothing else consumes the old subclasses.
4. **Migrate `AuditWiringIT`** assertions (oak-core integration test). It currently can do `instanceof MemberAddedEvent`; switch to checking `event.getDomain().equals("security") && event.getType().equals(SecurityAuditTypes.USER_MEMBER_ADDED)`.
5. **Migrate `AuditCaptureSiteOverheadTest`** benchmark — straight replacement of the construction call.
6. **Delete** the four subclasses + `SecurityAuditEvent` base class in the same commit.

The grep audit (`grep -rln "MemberAddedEvent\|MemberRemovedEvent\|MembersAddedBulkEvent\|MembersRemovedBulkEvent\|SecurityAuditEvent"`) shows **9 files** total touching these names; all are listed above. No external module references — clean migration.

### 3.2 Test migration

| Existing test | Fate |
|---|---|
| `SecurityAuditEventTest` | DELETE — the abstract class is gone; `SimpleAuditEvent` covers the field-storage semantics in `oak-audit-spi`. |
| `MemberAddedEventTest` (and the other 3) | DELETE the per-subclass tests. The semantics they verify split into: (a) field storage and immutability → covered by `SimpleAuditEventTest`; (b) factory captures the right values and emits the right domain/type → covered by `SecurityAuditEventsTest` (per-factory test); (c) payload-key contracts → covered by `SecurityAuditTypesTest` (verifies the string constants haven't drifted). |
| `AuditWiringIT` (in oak-core) | KEEP and adapt — same end-to-end behavior; assertions check string identifiers, not class identity. |

### 3.3 Coverage

Both `oak-security-spi` and `oak-audit-spi` are **100% line / 100% branch** modules. (`oak-audit-spi`'s design.md §11 currently states 0.99 / 1.0 — that's revised UP to 100%/100% in this freeze; turing pushed for honesty over "1% we got lazy on", and the new code is small enough that 100% is trivial. `design.md` §11 update is part of task #9.)

- `SecurityAuditTypes` — constants-only class. Private constructor reached via reflection (existing Oak pattern, e.g. `SecurityAuditDomainTest.privateConstructorIsReachableForCoverage`); substantive test asserts every constant is non-blank and the union of all string values is unique. 100%.
- `SecurityAuditEvents` — four factory methods + a private constructor. Per-factory happy-path test + the bulk-validation IAE path × 2 + private ctor reflection = ~7 tests. 100% line + 100% branch.
- `AuditEventImpl` — value-holder. Ctor + each getter + immutable-payload contract (post-construction mutation of the source Map does NOT affect the event; and `event.getPayload()` returns an unmodifiable Map) + factory IAE paths (blank `domain`, blank `type`). 100%.

---

## 4. Trade-offs explicitly accepted

1. **Capture sites lose compile-time validation that "type X has payload keys A, B".** The `MemberAddedEvent.of(groupPath, memberPath)` factory guaranteed two non-null strings via its signature. After this change, `SecurityAuditEvents.memberAdded(groupPath, memberPath)` keeps the typed signature, but anyone who skips the helper and uses `AuditEvent.of(...)` directly with a hand-rolled payload Map gets no compile-time check. *That's the price of flexibility, and the helper class mitigates it for the common case.*
2. **Listener-side discriminator changes from `instanceof` to string comparison.** A `switch` on `event.getType()` is the new pattern. Slightly more code at listener sites; eliminates the dependency on `oak-security-spi` for listeners that only need to read events.
3. **Public Javadoc burden moves from "one class per event" to "one constant per event".** Net zero — same number of docstrings, but consolidated into one file (`SecurityAuditTypes`) instead of scattered.

---

## 5. What this does NOT change

- The `AuditEventListener.onEvents(List<AuditEvent>)` signature — unchanged.
- The two pipelines (commit-attached / fire-and-forget) — unchanged.
- The `AuditEvents` static façade — unchanged (still has `record`, `dispatch`, `isEnabled`, `isEnabledFor`, `install`).
- The trust model (§9 of design.md) — unchanged.
- The drain-time `commit.*` payload decoration — unchanged.
- Existing performance characteristics — **modest improvement on the bulk path.** Today's `MembersAddedBulkEvent` allocates 6 objects per event (typed `Set<String>` copies for the accessors + the payload Map + `List<String>` copies inside the payload + the event instance). After this design, `SecurityAuditEvents.membersAddedBulk(...)` allocates 4 (no typed `Set<String>` accessors to populate; `Map.copyOf` is zero-cost when input is already an `ImmutableCollections.MapN` from `Map.of(...)`). The single-member path is unchanged: 2 allocations (`Map.of(K1,V1,K2,V2)` + `AuditEventImpl` instance) — `Map.copyOf` is again zero-cost on the `Map.of` literal. Detailed allocation analysis credited to grace.

---

## 6. Resolved decisions (from team review)

| Question | Resolution | Source |
|---|---|---|
| Keep or drop `SecurityAuditEvents` helper? | **KEEP** | sage, alex, grace — all strong-keep. `PrivilegeUtil`/`UserUtil` precedent. |
| Impl-class name (`SimpleAuditEvent` vs `DefaultAuditEvent` vs `AuditEventImpl`) | **`AuditEventImpl`** | grace (Oak `*Impl` convention for concrete pkg-private value-holders). |
| Validate non-empty `domain`/`type` in `AuditEvent.of(...)`? | **YES — throw IAE on blank** | sage Q2, grace Q3. |
| Constants class shape (interface vs class) | **`public final class`** with private ctor; NO `implements` clause on the helper. Effective Java Item 22 rationale recorded in class Javadoc. | alex Q1. |
| Convention codification (`<Domain>AuditEvents` for future audit consumers) | **Informal in v1.** Single Javadoc note on `SecurityAuditEvents`; formalize when a second domain implements. | alex Q2. |
| Downstream `instanceof MemberAddedEvent`? | **None.** Path α classes never released; only on this branch. | shannon (grep + commit-history verification). |
| Coverage gate for `oak-audit-spi` | **100% line / 100% branch** (was 0.99 / 1.0 in design.md §11). | turing. |
| Test ownership split | grace owns new co-located unit tests; turing owns cross-cutting + final coverage sweep. | turing's counter-proposal accepted. |
| Sage security commitments | All 4 ADOPTED: shallow-copy doc, IAE on blank domain/type, `@throws` on bulk helper, credential `@apiNote` on `AuditEvent.of`. | sage approval gate. |

---

## 7. Next step

[design-freeze] sent. grace proceeds with task #5; turing with the cross-cutting test changes in task #6 once grace's impl lands. `design.md` §4.1 + §10 + §11 update is task #9 (grace).
