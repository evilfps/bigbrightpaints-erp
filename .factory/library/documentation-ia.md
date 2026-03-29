# Documentation Information Architecture

How the backend truth library should be organized and what each packet type owns.

**What belongs here:** docs tree structure, packet ownership rules, cross-link expectations, and stale-doc handling guidance.

---

## Canonical Packet Types

- **Root index** — top-level navigation and section map
- **Module packets** — controllers, services, DTO families, entities, helpers, events, boundaries
- **Flow packets** — actors, entrypoints, preconditions, lifecycle, current definition of done, non-canonical paths, limitations
- **Frontend handoff packets** — host/path families, payload families, RBAC assumptions, read/write boundaries
- **ADRs** — accepted reasons for current architecture and product choices
- **Deprecated/incomplete registry** — retired, partial, duplicated, or dead-end surfaces and their replacements

## Recommended Docs Tree

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

## Ownership Rules

- Module packets own **structural truth**
- Flow packets own **behavioral truth**
- Frontend handoff packets own **consumer framing**, not implementation ownership
- ADRs own **decision truth**
- Deprecated registry owns **retirement truth**

If a packet needs to reference another packet's truth, it should link to it rather than duplicate it.

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

## Stale-Doc Policy

- If a legacy doc is still useful, mark it **redirected**, **archived**, or **non-canonical**
- If a legacy doc still mentions live endpoints or behavior, align it with the new canonical packet or add a redirect note
- Do not leave two active packets claiming to own the same truth
