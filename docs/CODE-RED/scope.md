# V1 Canonicalization Scope (CODE-RED)

## Source of Truth
- Production code is the only source of truth.
- Tests/docs are not authoritative.

## In Scope (must be unified)
1) Sales -> Inventory -> Dispatch -> Invoice -> Accounting
   - Invoice and postings are dispatch-truth only.
   - Partial dispatch/backorder is supported.
   - Packaging slip contains quantities only (no pricing).

2) HR / Payroll
   - Single canonical payroll algorithm.
   - Idempotent payroll runs per company + period + runType.
   - Reruns allowed only in DRAFT.
   - Posting owned by Accounting.

3) Manual Journal Policy (India reality, enterprise invariants)
   - Period lock enforced.
   - Balanced entry enforced.
   - Audit trail mandatory (who/when/why).
   - Reference namespace collision prevention.

4) Manufacturing/WIP
   - WIP postings and packing postings are deterministic and auditable.
   - Company timezone is used for all period boundaries.

5) Manufacturing & Packaging (Bulk -> Size SKUs)
   - Bulk batches are the only source for finished pack sizes.
   - Packing converts bulk liters to size SKUs using per-product variants + BOM (no legacy mapping fallback).
   - Canonical flow: `docs/CODE-RED/packaging-flow.md`.

6) Module Boundaries (organization only)
   - Accounting owns postings.
   - Sales/Inventory/HR/Purchasing emit requests/events but do not mutate ledger directly.
   - Forbidden cross-calls are removed or routed through facades.

7) Schema convergence
   - Eliminate schema drift via convergence migrations (no IF NOT EXISTS reliance).
   - Backfills must be deterministic.

## Out of Scope (V1)
- New UI features
- New accounting features beyond correctness (e.g., GRNI accrual), unless required to prevent ledger corruption
- New integrations unless they replace unsafe existing ones
