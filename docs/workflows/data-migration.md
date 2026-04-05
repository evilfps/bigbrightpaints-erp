# Data Migration Workflow (Business Operations View)

> ⚠️ **NON-CANONICAL**: This historical workflow guide is superseded by the canonical migration rollout guidance in [docs/runbooks/migrations.md](../runbooks/migrations.md). The companion [docs/migration-guide.md](../migration-guide.md) file is now an archival appendix only, not current truth.

**Audience:** Finance onboarding lead, warehouse lead, implementation manager

This guide complements the archived **`docs/migration-guide.md`** appendix by translating older migration packets into business execution steps and ownership.

> Use [docs/runbooks/migrations.md](../runbooks/migrations.md) for current rollout/rollback truth. Treat `docs/migration-guide.md` as archival context only.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Prepare migration checklist and owners | **Project Runbook** (internal) + reference `docs/migration-guide.md` | Clear owner per stream (accounts, stock, master data) and cutover date | No owner assigned, file formats mixed, or old templates used. Freeze template versions before upload day. |
| 2 | Import opening accounting balances | `POST /api/v1/accounting/opening-balances` (CSV upload) | Opening journal posted; debits and credits are balanced | File rejected for unbalanced totals, bad account type, or missing account fields. Fix rows and re-upload. |
| 3 | Import opening inventory stock for prepared SKUs only | `POST /api/v1/inventory/opening-stock` (CSV upload; header `Idempotency-Key` and query param `openingStockBatchKey` required), history via `GET /api/v1/inventory/opening-stock` | RM/FG opening stock and batch data load only for already-prepared SKUs, with per-row `results[]` and `errors[]` including readiness detail | Missing idempotency key, missing `openingStockBatchKey`, orphan SKU, missing mirror truth, missing readiness prerequisites, duplicate batch/SKU rows, invalid dates, or quantity/cost format errors. Fix setup first; retry the same batch with the original `Idempotency-Key`, or use a new `Idempotency-Key` plus a new `openingStockBatchKey` only for a materially distinct follow-up import. |
| 4 | Optional Tally XML migration | `POST /api/v1/migration/tally-import` | Ledger + opening references are mapped into ERP structures | Unmapped groups/items returned; source Tally grouping needs correction before re-run. |
| 5 | Validate post-migration balances and stock | Trial balance: `GET /api/v1/reports/trial-balance`; stock checks: `GET /api/v1/finished-goods/stock-summary`, `GET /api/v1/raw-materials/stock/inventory` | Financial opening and physical stock align with source system sign-off | Mismatch indicates missing rows or wrong mapping. Reconcile using migration error reports + statements before go-live. |

## Recommended Business Cutover Sequence

1. **Master data freeze** in legacy system (customers, suppliers, SKUs)
2. **Finance opening balance upload** and trial balance validation
3. **Warehouse opening stock upload for prepared SKUs only** and physical count sign-off
4. **Optional Tally import reconciliation** for legacy ledgers
5. **First controlled transaction in ERP** (one sale + one purchase)
6. **Formal go-live approval** from finance and operations owners

## Which Team Owns What

- **Finance team:** opening balances, AR/AP review, reconciliation sign-off
- **Warehouse team:** opening stock quantities, batch expiry/manufacture data
- **Admin/super admin:** tenant readiness, user access, module enablement
- **Implementation lead:** cutover checklist, issue triage, rollback decision

## Fast Issue Triage During Migration

1. **Import rejected immediately:** check required headers and value formats in `docs/migration-guide.md`.
2. **Partial import success:** isolate failed rows from `errors[]`, fix the readiness/setup blocker, then re-upload only the corrected subset with a new explicit idempotency key.
3. **Opening balances not matching source:** rerun trial balance after confirming no duplicate/replayed file upload.
4. **Stock import is blocked for a SKU:** use the returned readiness stage and blockers to fix catalog or inventory truth before retrying; opening stock is not a repair path.

## Go-Live Acceptance Criteria

- Trial balance is balanced and approved by finance
- Opening stock totals signed off by warehouse
- No unresolved critical errors in last import response
- First sale and first purchase flow complete without manual DB fixes
- Period close checklist baseline prepared for first month-end

For technical CSV examples, Tally mapping details, and error dictionary, see **`docs/migration-guide.md`**.
