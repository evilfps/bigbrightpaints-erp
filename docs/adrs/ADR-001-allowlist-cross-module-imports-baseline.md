# ADR-001: Allowlist Cross-Module Import Edges for Existing Couplings

Last reviewed: 2026-03-29

## Status

Accepted

## Context

The architecture allowlist (`ci/architecture/module-import-allowlist.txt`) enforces which cross-module Java import edges are permitted. Several existing couplings were not yet listed, causing `check-architecture.sh` to report violations. These are genuine, intentional couplings already present in the codebase:

- **company→rbac**: Company lifecycle checks reference RBAC roles during tenant operations.
- **portal→admin**: Portal read models import admin support/export surfaces.
- **production→factory**: Production catalog setup imports factory service helpers for item/product setup.
- **production→purchasing**: Production catalog imports purchasing helpers for material/supplier metadata.
- **production→sales**: Production catalog imports sales helpers for dealer-facing product metadata.
- **reports→admin**: Reports import admin service surfaces for export/approval gates.
- **reports→production**: Reports import production read models for factory/production dashboards.
- **reports→purchasing**: Reports import purchasing read models for procurement dashboards.
- **sales→admin**: Sales imports admin support ticket surfaces for dealer portal support.

## Why Needed

The architecture check must accurately reflect existing cross-module couplings. Leaving these edges out of the allowlist causes false-positive failures that block legitimate code. Documenting them makes the coupling visible and auditable rather than hidden.

## Decision

Add all nine missing edges to the allowlist. Each edge represents an existing, intentional cross-module dependency that is already exercised in production and covered by tests.

## Alternatives Rejected

1. **Refactor away the couplings** — would require significant architectural changes (introducing facades, read models, or event-driven decoupling) that are out of scope for the docs-foundation milestone.
2. **Suppress the check** — would hide real coupling rather than making it visible.
3. **Leave edges unlisted** — would cause CI failures on an unrelated docs change, which is worse than documenting the status quo.

## Boundary Preserved

The allowlist remains the single source of truth for permitted cross-module imports. Adding these edges does not weaken the boundary — it makes the actual boundary explicit. Any future import edge not on the allowlist will still fail the check and require the same ADR-gated process.

## Consequences

- `check-architecture.sh` passes with the updated allowlist.
- The nine edges are now visible as intentional couplings in the architecture governance.
- Future workers can see these edges and understand why they exist.
- Removing any of these edges would require a corresponding allowlist update with its own ADR.

## Cross-references

- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — canonical dependency edges and cross-module boundary documentation
- ADR-002 — multi-tenant auth scoping, which constrains cross-module data access patterns
