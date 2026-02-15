# Inventory Module Agent Map

Last reviewed: 2026-02-15
Scope: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory`

## Purpose
Maintain stock correctness, costing integrity, and inventory-to-accounting linkage invariants.

## Canonical References
- `erp-domain/docs/MODULE_FLOW_MAP.md`
- `docs/posting-path-inventory.md`
- `docs/idempotency-inventory.md`
- `docs/RELIABILITY.md`

## Hard Invariants
- No invalid negative stock transitions.
- Dispatch/issue/intake operations remain idempotent where required.
- COGS/inventory journal linkages remain exact and non-duplicative.

## Required Checks Before Done
- Targeted inventory tests for changed flows.
- Cross-module tests if accounting/sales linkages are affected.
- `bash ci/check-architecture.sh`
- `bash scripts/verify_local.sh` for posting-coupled inventory edits.

## Escalate to Human (R2)
- Costing method policy changes.
- Inventory valuation semantics affecting financial reports.
- Migrations that reshape movement/batch history data.

## Anti-Patterns
- Recomputing historical values without migration/runbook evidence.
- Skipping linkage checks between inventory movement and journal entries.
