Apache Jackrabbit Oak Audit SPI
===============================

Domain-neutral audit SPI. Defines:

- `AuditEvent` — generic event interface (domain, type, timestamp, payload).
- `AuditEventListener` — single-method consumer interface (`onEvents`).
- `AuditEventEmitter` — OSGi service for any bundle to emit events.
- `AuditEvents` — static façade for Oak-internal capture sites.
- `AuditConfiguration` — typed handle on the pipeline's runtime state
  (`isActive()`, `NAME`, `NOOP`). Not a `SecurityConfiguration` — audit is a
  top-level Oak concern.

Consumed by `oak-security-spi`, `oak-core`, and any consumer bundle.

This module does NOT depend on `oak-security-spi`, `oak-core`, or any
Oak-internal storage modules.

Further reading
---------------

- [`docs/design-overview.md`](docs/design-overview.md) — one-page text/ASCII
  view of the pipeline (both producer paths, key components, module layering).
- [`docs/design.md`](docs/design.md) — full design spec (SPI shape, OSGi /
  embedded wiring, threading invariants, test patterns).
- [`docs/performance/README.md`](docs/performance/README.md) — performance
  characterization (macro slice + per-commit / per-event microbenchmarks),
  with reproduction recipes.
- User-facing guide: [`oak-doc/src/site/markdown/security/audit.md`](../oak-doc/src/site/markdown/security/audit.md).

License
-------

(see the top-level [LICENSE.txt](../LICENSE.txt) for full license details)

Collective work: Copyright 2012 The Apache Software Foundation.

Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
