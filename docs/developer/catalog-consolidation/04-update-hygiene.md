# Catalog Consolidation Update Hygiene

This file defines how to keep the catalog flow docs and surrounding engineering
artifacts clean as the implementation changes.

## Purpose

The catalog surface is currently confusing because route ownership, write
ownership, and downstream readiness are split. These docs only stay useful if
they are updated as one package when the flow changes.

## Rules

### Keep Current-State And Target-State Separate

- `01-current-state-flow.md` is factual and should describe only what the code
  does right now
- `02-target-accounting-product-entry-flow.md` is the desired future state
- do not blur those two documents together

### Update The Doc Set In The Same Packet

When catalog route ownership or write behavior changes, update together:

- `docs/developer/catalog-consolidation/README.md`
- `docs/developer/catalog-consolidation/01-current-state-flow.md`
- `docs/developer/catalog-consolidation/02-target-accounting-product-entry-flow.md`
- `docs/developer/catalog-consolidation/03-definition-of-done-and-parallel-scope.md`
- repo-root `README.md`
- `openapi.json`
- `.factory/library/frontend-handoff.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- route-anchored tests/helpers that expose catalog hosts or contract examples

### Delete Stale Mentions, Do Not Leave Split Truth

- if a route host is retired, remove it from docs in the same packet
- if a service stops owning writes, remove that claim in the same packet
- if a referenced doc no longer exists, remove the dead reference instead of
  leaving a stale pointer behind

### Keep Docs Grounded In Code

When changing these docs, verify:

- controller annotations
- do docs still imply inline brand creation inside the product payload
- service entry methods
- persistence targets
- downstream inventory / production / sales dependencies

Do not update from assumption or ticket intent alone.

### Keep Proof And Docs Aligned

If the packet claims downstream readiness, the proof must include:

- product create
- downstream mirror creation
- production visibility
- sales order / availability visibility

If those tests do not exist, the packet is not done.

## Naming Guidance

- use `catalog` for the canonical public surface
- use `product-entry flow` for the user-facing create flow
- use `product family` or `variant group` for grouped SKU lineage
- avoid reintroducing vague terms like `accounting catalog path` once the
  consolidation is done

## Review Checklist

Before closing a catalog packet, check:

- do docs still mention more than one public catalog host
- do docs still describe more than one write engine
- do docs still imply that product create can stop before inventory mirror
  creation
- do docs and OpenAPI describe the same public surface
- do tests prove the same behavior the docs claim
