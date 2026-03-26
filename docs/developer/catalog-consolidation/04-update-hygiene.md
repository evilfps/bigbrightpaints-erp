# Catalog Consolidation Update Hygiene

This file defines how to keep the surviving item setup docs and surrounding engineering artifacts clean as the implementation changes.

## Purpose

The catalog surface only stays understandable if setup truth, readiness truth, and downstream operator handoff are updated together when the contract changes.

## Rules

### Keep Current-State And Consumer-Facing Flow Separate

- `01-current-state-flow.md` is factual and should describe only what the code does right now
- `02-target-accounting-product-entry-flow.md` is the accounting-facing explanation of that surviving contract
- do not blur those two documents together

### Update The Doc Set In The Same Packet

When catalog setup ownership or execution handoff changes, update together:

- `docs/developer/catalog-consolidation/README.md`
- `docs/developer/catalog-consolidation/01-current-state-flow.md`
- `docs/developer/catalog-consolidation/02-target-accounting-product-entry-flow.md`
- `docs/developer/catalog-consolidation/03-definition-of-done-and-parallel-scope.md`
- repo-root `README.md` when top-level public flow wording changes
- `openapi.json`
- `.factory/library/frontend-handoff.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- route-anchored tests/helpers that expose setup or operator contract examples

### Delete Stale Mentions, Do Not Leave Split Truth

- if a setup host is retired, remove it from docs in the same packet
- if a controller/service stops owning setup or execution writes, remove that claim in the same packet
- if a referenced doc no longer exists, remove the dead reference instead of leaving a stale pointer behind

### Keep Docs Grounded In Code

When changing these docs, verify:

- controller annotations on `CatalogController`, `ProductionLogController`, `PackingController`, `DispatchController`, and `SalesController`
- whether docs still imply `legacy product routes` or `legacy accounting-prefixed product setup routes`
- item read/write ownership and readiness visibility on `/api/v1/catalog/items`
- downstream dependencies from item setup into batch -> pack -> dispatch

Do not update from assumption or ticket intent alone.

### Keep Proof And Docs Aligned

If the packet claims one coherent operator story, the proof must include:

- item create on `/api/v1/catalog/items`
- readiness reads before execution
- production logs as the only batch-create surface
- packing records as the only pack mutation
- factory-owned dispatch confirm as the only dispatch-confirm write

If those proofs do not exist, the packet is not done.

## Naming Guidance

- use `catalog` for the canonical public setup surface
- use `item setup` for the surviving stock-bearing create flow
- use `Product Family`, `Production Batch`, `Pack`, and `Dispatch` for the operator story
- use `Packaging Setup` / `Packaging Rules` for pack prerequisites
- avoid reintroducing vague terms like `accounting catalog path` or `product preview flow` once the hard-cut is done

## Review Checklist

Before closing a catalog packet, check:

- do docs still mention more than one public setup host
- do docs still imply `legacy product routes` or `legacy accounting-prefixed product setup routes` are current
- do docs still describe more than one batch, pack, or dispatch write owner
- do docs and OpenAPI describe the same public surface
- do tests prove the same behavior the docs claim
