# Sales Module Agent Map

Last reviewed: 2026-02-15
Scope: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales`

## Purpose
Keep O2C workflows deterministic across order, dispatch, invoice, receipt, and return paths.

## Canonical References
- `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`
- `erp-domain/docs/MODULE_FLOW_MAP.md`
- `docs/sales-payment-accounting-contract.md`
- `docs/SECURITY.md`

## Hard Invariants
- Dealer/company isolation must hold for all portal and API reads/writes.
- Dispatch -> invoice -> accounting links remain consistent and idempotent.
- Credit/approval logic must not bypass policy checks.

## Required Checks Before Done
- Targeted sales + dealer portal tests for changed surfaces.
- Accounting linkage tests when posting side effects are touched.
- `bash ci/check-architecture.sh`
- `bash scripts/verify_local.sh` for high-risk O2C changes.

## Escalate to Human (R2)
- Credit approval semantics, dealer access expansion, or role boundary changes.
- Changes to posting behavior triggered by dispatch/invoice/returns.

## Anti-Patterns
- Returning foreign-dealer data due weak filtering.
- Adding alternate posting paths that bypass canonical service flow.
