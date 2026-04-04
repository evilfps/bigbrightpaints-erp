# Documentation Conventions

Last reviewed: 2026-04-04

This document defines the writing conventions, cross-link expectations, and stale-doc handling policy for the orchestrator-erp backend documentation library.

---

## 1) Source of Truth Rules

### 1.1 Code is the primary truth source

Documentation must be grounded in the actual implementation. The evidence hierarchy is:

1. Controller annotations and DTOs for route/payload truth.
2. Service/facade/engine code for lifecycle and side-effect truth.
3. Events/listeners and config for hidden coupling and runtime gating.
4. Tests for executed/guarded behavior.
5. Existing docs only as secondary inputs to preserve or retire.

### 1.2 Do not invent behavior

- If a capability is implemented, document it as implemented.
- If a capability is planned but not yet built, label it as **pending**, **planned**, or **future work** — never as done.
- If a capability is partially built, document what exists today and explicitly note what is incomplete.

### 1.3 No duplicate truth

- Each truth concern should be owned by exactly one canonical packet.
- Module packets own structural truth.
- Flow packets own behavioral truth.
- Frontend handoff packets own consumer framing.
- ADRs own decision truth.
- Deprecated registry owns retirement truth.
- If a packet needs to reference another packet's truth, it should link to it rather than duplicate it.

## 2) Writing Style

### 2.1 Truth-first writing

- Describe the system as it is today, not as it should be.
- When behavior is partial, confusing, or controlled by configuration rather than hard enforcement, say so explicitly.
- Preserve the distinction between module ownership and flow ownership — they often differ.

### 2.2 Backend + frontend readable

- Docs should be readable by both backend and frontend engineers.
- Use clear headings, tables, and bullet lists.
- Avoid jargon that only makes sense to one team without explanation.

### 2.3 Explicit about limitations

- If something is partial, dead-end, deprecated, or ambiguous, the docs must say so clearly.
- Use callout sections for known gaps, deprecated surfaces, and configuration-guarded behavior.

## 3) Implemented vs Planned Language

### 3.1 Status labeling

When writing or updating docs, use explicit status labels:

- **Implemented** — the behavior exists in the codebase today and is covered by tests. No special label is needed; the absence of a qualifier implies implemented.
- **Pending** / **Planned** / **Future work** — the behavior is designed or discussed but not yet built. Always label explicitly.
- **Partial** — the behavior is partially built. Document what exists today and explicitly note what is incomplete.
- **Deprecated** — the behavior was once canonical but has been replaced. Always point to the replacement.
- **No replacement** — the behavior was retired and nothing replaces it. State this explicitly so readers know it was an intentional retirement rather than an accidental gap.

### 3.2 Avoid aspirational prose

- Do not describe the system as it *should* be. Describe it as it *is*.
- Do not document a future improvement as though it is already in place.
- If a surface is controlled by a feature toggle or configuration flag, say so explicitly and note the default posture.

### 3.3 Caveat discipline

- When a behavior has a known gap, edge case, or fail-open posture, document it as a **caveat** or **known limitation** rather than hiding it or treating it as a temporary oversight.
- Caveats should be specific: name the affected path, the expected behavior, and the actual behavior.

## 4) Cross-Link Expectations

### 4.1 Every packet links to related packets

- Module packets link to relevant flow packets, ADRs, and frontend handoff packets.
- Flow packets link back to their owning module packets and related ADRs.
- Frontend handoff packets link back to canonical module and flow docs.
- ADRs link to the module/flow packets they affect.
- Deprecated registry entries link to their canonical replacement or state explicitly that no replacement exists.

### 4.2 Navigation is bidirectional

- If module A links to flow B, then flow B should link back to module A.
- The root index (`docs/INDEX.md`) is the top-level entrypoint and must link to all major sections.

### 4.3 Link format

- Use relative paths for internal links within the docs tree.
- Use `[text](path)` markdown format.
- Verify links exist when editing docs.

## 5) Freshness Markers

### 5.1 Required marker

Every markdown file in the canonical docs tree must carry a `Last reviewed: YYYY-MM-DD` marker near the top of the file. This is enforced by `ci/lint-knowledgebase.sh`.

### 5.2 Updating markers

- When you edit a canonical docs file, update the `Last reviewed:` date to the current date.
- The date must be a valid ISO calendar date and must not be in the future.

## 6) Stale-Doc Handling Policy

### 6.1 When docs become stale

A doc is stale when:

- It describes behavior that has changed in the implementation.
- It references endpoints, DTOs, or services that no longer exist.
- It contradicts the current canonical documentation.

### 6.2 Handling stale docs

- **Redirect:** If a legacy doc is replaced by a new canonical packet, add a redirect notice at the top of the legacy doc pointing to the new canonical location.
- **Archive:** If a legacy doc has historical value but is no longer active truth, add an archive notice at the top stating it is no longer maintained and pointing to the current canonical source.
- **Mark non-canonical:** If a legacy doc must remain for reference, clearly label it as `non-canonical` or `superseded`.
- **Do not silently delete or ignore.** Stale docs that still mention live endpoints or behavior must be aligned with the new canonical packet or explicitly redirected.

### 6.3 Competing truth is not allowed

- No two active packets may claim to own the same truth.
- If a new packet replaces an old one, the old packet must be redirected, archived, or marked non-canonical in the same change that introduces the new packet.

### 6.4 Canonical banner template for legacy docs

When marking a legacy doc as non-canonical or reference-only, use one of these standardized banner formats at the top of the file:

**NON-CANONICAL (superseded by canonical docs):**
```markdown
> ⚠️ **NON-CANONICAL**: This document is superseded by the canonical flow packets in [docs/flows/](flows/). The current [area] behavior is documented in [docs/flows/AREA-FLOW.md](../flows/AREA-FLOW.md).
```

**REFERENCE ONLY (replaced by primary truth source):**
```markdown
> ⚠️ **REFERENCE ONLY**: This document is no longer the canonical source of truth. Use `openapi.json` and the module packets in [docs/modules/](modules/) as the primary API contract reference. The authoritative [area] inventory is now available through [docs/modules/MODULE-INVENTORY.md](modules/MODULE-INVENTORY.md).
```

**Key elements:**
- Start with `> ⚠️` (warning emoji + blockquote) for visual prominence
- Use **bold** for the banner type (NON-CANONICAL or REFERENCE ONLY)
- State what the reader should use instead (canonical packet path)
- Keep the message brief but informative

Use these templates consistently so future legacy-doc redirects follow one uniform format across the docs tree.

## 7) Docs-Only Lane

Docs-only packets are limited to the canonical docs/governance lane:

- repo-root signposts/governance: `README.md`, `AGENTS.md`, `ARCHITECTURE.md`, `CHANGELOG.md`
- canonical docs spine files: `docs/INDEX.md`, `docs/ARCHITECTURE.md`, `docs/CONVENTIONS.md`, `docs/SECURITY.md`, `docs/RELIABILITY.md`, `docs/BACKEND-FEATURE-CATALOG.md`, `docs/RECOMMENDATIONS.md`
- canonical directories: `docs/adrs/**`, `docs/agents/**`, `docs/approvals/**`, `docs/deprecated/**`, `docs/modules/**`, `docs/flows/**`, `docs/frontend-api/**`, `docs/frontend-portals/**`
- internal worker-guidance lane: `.factory/library/**`

Markdown outside that lane is **not** docs-only. That includes `docs/platform/**`, `docs/runbooks/**`, `docs/design/**`, `docs/code-review/**`, `docs/developer/**`, `docs/frontend-update-v2/**`, root worklogs/reports, and any mixed markdown-plus-code/config/test/script/OpenAPI diff.

Docs-only packets:

- run `bash ci/lint-knowledgebase.sh` only
- skip Codex review/subagent review
- skip runtime validators and service startup
- must not change backend runtime behavior

## Cross-references

- [docs/INDEX.md](INDEX.md) — canonical documentation index
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — architecture reference
- [docs/RELIABILITY.md](RELIABILITY.md) — reliability posture
