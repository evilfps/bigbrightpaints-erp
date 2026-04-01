# Documentation Information Architecture

How the backend truth library should be organized and what each packet type owns.

**What belongs here:** docs tree structure, packet ownership rules, cross-link expectations, and stale-doc handling guidance.

---

## Mainline Catch-Up Override

The original docs-refresh mission on this branch built a canonical tree around `docs/INDEX.md`, module packets, flow packets, ADRs, and single-file frontend handoff packets. Read-only comparison against `origin/main` now shows that mainline has moved to a different frontend-facing canonical model:

- `docs/frontend-portals/` — per-portal ownership docs
- `docs/frontend-api/` — shared cross-portal API rules

During catch-up work, workers should treat that mainline model as the target canonical structure. Earlier module/flow/handoff packets on this branch may still be mined for truth, but they must not silently remain co-equal canonical docs if the new portal/API model supersedes them.

## Canonical Packet Types

- **Root index** — top-level navigation and section map
- **Module packets** — controllers, services, DTO families, entities, helpers, events, boundaries
- **Flow packets** — actors, entrypoints, preconditions, lifecycle, current definition of done, non-canonical paths, limitations
- **Frontend handoff packets** — host/path families, payload families, RBAC assumptions, read/write boundaries
- **Authoritative recommendations register** — the single canonical answer set for formerly open product/bug/plan decisions
- **ADRs** — accepted reasons for current architecture and product choices
- **Deprecated/incomplete registry** — retired, partial, duplicated, or dead-end surfaces and their replacements

## Recommended Docs Tree

### Original branch docs model

- `docs/INDEX.md`
- `docs/ARCHITECTURE.md`
- `docs/RELIABILITY.md`
- `docs/modules/`
- `docs/flows/`
- `docs/frontend-handoff/`
- `docs/adrs/`
- `docs/deprecated/`
- `docs/agents/`
- `docs/approvals/`

### Mainline catch-up target

- `docs/frontend-portals/README.md`
- `docs/frontend-portals/portal-matrix.md`
- `docs/frontend-portals/{superadmin,tenant-admin,accounting,sales,factory,dealer-client}/`
- `docs/frontend-api/README.md`
- `docs/frontend-api/auth-and-company-scope.md`
- `docs/frontend-api/idempotency-and-errors.md`
- `docs/frontend-api/pagination-and-filters.md`
- `docs/frontend-api/exports-and-approvals.md`
- `docs/frontend-api/accounting-reference-chains.md`
- `docs/frontend-api/dto-examples.md`

If older packet families remain after catch-up, they must be explicitly marked non-canonical/reference-only or aligned so they do not compete with the mainline portal/API structure.

## Ownership Rules

- Module packets own **structural truth**
- Flow packets own **behavioral truth**
- Frontend handoff packets own **consumer framing**, not implementation ownership
- The authoritative recommendations register owns **recommendation truth** for formerly open items
- ADRs own **decision truth**
- Deprecated registry owns **retirement truth**

If a packet needs to reference another packet's truth, it should link to it rather than duplicate it.

## Authoritative Recommendations Follow-Up

During the post-catch-up follow-up, the user supplied explicit verdicts for the previously open items. Workers should treat those verdicts as the canonical recommendation layer and place them in one authoritative canonical surface rather than leaving the answer spread across many flow-level Open Decisions tables.

That means:
- the authoritative recommendations register should be discoverable from the current docs navigation
- older flow/module open-decision sections may keep brief factual context, but should summarize or defer to the register instead of restating competing recommendations
- audit-related recommendations must stay aligned with the canonical audit docs and current surviving audit read surfaces

## Minimum Contents Per Packet

### Module packet

- what the module owns
- primary controllers/routes
- key services/facades/engines
- DTO families and public payload groups
- key entities/repositories/helpers/events
- important cross-module seams
- deprecated/confusing surfaces
- links to flows, ADRs, handoffs

### Flow packet

- actors
- entrypoints
- preconditions
- canonical lifecycle/status progression
- current definition of done / completion boundary
- non-canonical or deprecated paths
- event/listener or access-control seams where material
- current limitations / incomplete areas
- links to modules, ADRs, handoffs

### Frontend handoff packet

- canonical host/path family
- payload families / DTO groups
- RBAC assumptions
- read/write boundary
- frontend-avoid / deprecated paths
- links to canonical module/flow docs

### Frontend portal packet (mainline target)

- portal-specific README
- routes
- api contracts
- workflows
- role boundaries
- states and errors
- playwright journeys

### Shared frontend API packet (mainline target)

- auth and company scoping rules
- shared idempotency/error rules
- pagination/filter rules
- export/approval rules
- accounting reference chains
- DTO examples

## Stale-Doc Policy

- If a legacy doc is still useful, mark it **redirected**, **archived**, or **non-canonical**
- If a legacy doc still mentions live endpoints or behavior, align it with the new canonical packet or add a redirect note
- Do not leave two active packets claiming to own the same truth
