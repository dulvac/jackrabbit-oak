# Research Notes — Audit SPI (Path α)

Author: shannon · Scope: ~30 min, < 800 words. Sources cited inline.

---

## 1. Whiteboard ranking semantics — **CRITICAL FINDING**

`Tracker.getServices()` is **not uniformly ranked** across Oak's two `Whiteboard` implementations. The SPI cannot rely on the whiteboard to sort.

### `DefaultWhiteboard` (used by non-OSGi callers, tests, `Oak.with(...)` standalone)
File: `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/spi/whiteboard/DefaultWhiteboard.java`

- Line 35: `Map<Class<?>, Set<Service>> registry = new HashMap<>()`.
- Line 40: registry uses `SetUtils.newIdentityHashSet()` — identity-hash semantics, **arbitrary iteration order**.
- Lines 53–64 (`lookup(Class<T>)`): `services.stream().map(Service::getService).collect(Collectors.toList())` — **no sort step**, no read of any `service.ranking` property.
- Line 101 (`track(Class)`): the returned `Tracker.getServices()` simply delegates to `lookup(type)`.

**Verdict:** `DefaultWhiteboard.track(...).getServices()` returns services in undefined order. Ranking is silently ignored.

### `OsgiWhiteboard` (production OSGi runtime)
File: `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/osgi/OsgiWhiteboard.java`

- Lines 181–194 (`getServiceList`): builds a `TreeMap<ServiceReference, T>` from currently-tracked services and returns `new ArrayList<>(sorted.values())`.
- The natural order of `org.osgi.framework.ServiceReference` (OSGi Core R7+ §5.2.5) is **service.ranking descending**, with `service.id` ascending as tie-breaker.
- The atomic snapshot is rebuilt on every add/modify/remove (lines 130, 146, 152).

**Verdict:** `OsgiWhiteboard.track(...).getServices()` is sorted by ranking descending. Production is correct; tests are not.

### `AbstractServiceTracker`
File: `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/spi/whiteboard/AbstractServiceTracker.java:96-98`

- `getServices()` is a pass-through to the underlying `Tracker`. Inherits whichever ordering (or lack thereof) the whiteboard provides.

### Recommendation to grace + ada

The SPI MUST sort on dispatch, not on retrieval. The design brief's fallback ("`default int getRank() { return 0; }` on `AuditEventListener` and sort on dispatch") is **required, not optional**:

1. `AuditEventListener` exposes `default int getRank() { return 0; }`.
2. `WhiteboardAuditEventListenerRegistry.dispatch()` sorts `tracker.getServices()` by `Comparator.comparingInt(AuditEventListener::getRank).reversed()` (and document tie-break behavior; stable sort preserves whiteboard order on ties).
3. Listeners running in OSGi register with `service.ranking = N` *and* override `getRank()` to return the same `N` — explicit, debuggable, framework-independent.

Without this, multi-listener test scenarios will be non-deterministic and CI flakes will hide under HashSet iteration order quirks.

→ Sent to grace + ada.

---

## 2. Prior Oak audit-related work

### Doc tree
`grep -ri "audit" oak-doc/src/site/markdown/` returns **no hits**. There is no architectural-level documentation for an Oak audit subsystem.

### Git history (all branches, all paths)

Only four commits reference "audit" project-wide:

| Commit | Ticket | Summary |
|---|---|---|
| `9b4fb1168c` (2015-02-13, Chetan Mehrotra) | [OAK-2516](https://issues.apache.org/jira/browse/OAK-2516) | Adds an SLF4J logger named `org.apache.jackrabbit.oak.audit` in `SessionDelegate` that emits user/operation text at DEBUG. **Not a structured framework** — one logger, no schema, no domains, no listeners. |
| `bc4737879e` (2015-02-13) | [OAK-2516](https://issues.apache.org/jira/browse/OAK-2516) | Follow-up: remove redundant Marker arg from the same audit log call. |
| `298dec13e2` (2024) | [OAK-11401](https://issues.apache.org/jira/browse/OAK-11401) | Raises log level for FullGC "audit log" — unrelated; this is GC progress logging, not security audit. |
| `b2d89194a9` (2025) | [OAK-11795](https://issues.apache.org/jira/browse/OAK-11795) | Fixes FullGC audit logs when run via `revisions` command. Same FullGC log channel. Not relevant. |

### Constraints / informational notes for Path α

- The string `"org.apache.jackrabbit.oak.audit"` is already taken by the SLF4J logger in `oak-jcr/src/main/java/org/apache/jackrabbit/oak/jcr/delegate/SessionDelegate.java:81`. The new SPI must **not** reuse that logger name (collision with existing operator dashboards). Suggest `org.apache.jackrabbit.oak.security.audit` or per-domain `org.apache.jackrabbit.oak.audit.<domain>` for the noop fallback / dispatch-error log.
- No deprecated audit API exists in Oak today, so the SPI in `oak-security-spi/.../security/audit/` introduces a new public namespace with no migration burden.
- No earlier design discussion was found (no JIRA, no commit). Path α is greenfield from Oak's perspective.

### Out-of-scope flag
- RECOMMEND DEEPER INVESTIGATION: how downstream consumers (AEM/Sling) currently consume the existing `org.apache.jackrabbit.oak.audit` SLF4J logger — risk that turning it off in favor of the new framework breaks an undocumented contract. Out of scope for this design pass; flag for a follow-up before v1 ships.

---

## 3. Context7 spot-check — Observer / commit-hook lifecycle

Resolved library: `/apache/jackrabbit` (Jackrabbit content repository docs). Queried for recent changes to `CommitHook` / `PostValidationHook` / `ChangeDispatcher` lifecycle. Context7 surfaces only the high-level JCR observation contract (async `EventListener` fired after persistence; `ObservationManager.addEventListener` registration), with no signal that Oak's internal hook chain semantics or the synchronous-on-merge-thread guarantee of `ChangeDispatcher.contentChanged(...)` have changed. The design brief's quoted `MutableRoot.java:282-308` chain composition and `ChangeDispatcher.addObserver` JavaDoc (`oak-store-spi/.../ChangeDispatcher.java:55-58`) remain authoritative; no recent patch invalidates Path α's commit-path assumptions.

---

## Summary

1. **Whiteboard ranking is not portable** — `OsgiWhiteboard` sorts, `DefaultWhiteboard` does not. SPI must add `getRank()` and sort on dispatch. Notified grace + ada.
2. **No prior Oak audit framework** beyond the OAK-2516 SLF4J text logger. Avoid the logger name `org.apache.jackrabbit.oak.audit` to prevent collisions with existing AEM/Sling dashboards.
3. **Hook/observer lifecycle is stable.** Context7 surfaces no contradictions with the brief's trunk citations.
