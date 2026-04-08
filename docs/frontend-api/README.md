# Frontend API Contract

`docs/frontend-api/` documents the shared API contracts and rules that apply across all frontend portal shells. This is the canonical source for cross-portal frontend contracts and replaces any older handoff docs that referenced deprecated routes or tenant-scoping assumptions.

Last reviewed: 2026-04-07

## Purpose

This folder provides the shared topic files that all frontend portal shells (`superadmin`, `tenant-admin`, `accounting`, `sales`, `factory`, `dealer-client`) share. It explains:

- How frontend applications bootstrap and authenticate
- What tenant-scoping identifiers to use and persist
- How to handle errors, idempotency, pagination, and filters consistently
- Export and approval workflows that span multiple portals
- Accounting reference chains and DTO examples

## Current Mainline Rules

### Bootstrap and Authentication

- **Only bootstrap endpoint:** `GET /api/v1/auth/me` is the sole supported frontend identity surface. This endpoint returns claim/context-derived data and requires a valid JWT bearer token.
- **Do not use:** `/api/v1/auth/profile` — this endpoint is retired and no longer wired into the current frontend contract.
- **Session refresh:** Use `POST /api/v1/auth/refresh-token` to obtain a fresh access token from a valid refresh token.
- **Logout:** Use `POST /api/v1/auth/logout` to invalidate the current session (optionally pass the refresh token to revoke it).

### Tenant Scope

- **Tenant identifier:** Use `companyCode` as the persisted tenant scope identifier in frontend state, localStorage, and API calls.
- **Do not use:** `companyId` (numeric) as a tenant-shell auth identifier — this is retired and only valid for superadmin route params (`/platform/tenants/:tenantId`).
- **Request header:** Send `X-Company-Code` header (not `X-Company-Id`) for tenant-scoped requests.

### Portal Placement

- **superadmin:** Platform control-plane only — routes to `/api/v1/superadmin/**`
- **tenant-admin:** Owns `/api/v1/admin/users/**` and `GET /api/v1/admin/approvals`
- **accounting:** COA, journals, reconciliation, period close, reports
- **sales:** Dealer master, sales orders, credit escalation
- **factory:** Production, packing, dispatch preparation and confirmation
- **dealer-client:** Dealer dashboard, orders, invoices, aging, support

See [`docs/frontend-portals/README.md`](../frontend-portals/README.md) for detailed folder ownership.

### Additional Current Contract Rules

- **Manual journals:** `POST /api/v1/accounting/journal-entries` is the only public manual journal create route.
- **Reversals:** `POST /api/v1/accounting/journal-entries/{entryId}/reverse` is the only public reversal route.
- **Inventory reads:** `GET /api/v1/raw-materials/stock` returns `RawMaterialStockEntryDto[]`; `GET /api/v1/finished-goods` returns a paginated `PageResponse<FinishedGoodDto>`; `GET /api/v1/finished-goods/stock-summary` returns `FinishedGoodStockSummaryDto[]`; `GET /api/v1/finished-goods/{id}/batches` returns `FinishedGoodBatchInventoryDto[]`; and `GET /api/v1/inventory/batches/{id}/movements` returns `InventoryBatchMovementHistoryDto[]`.
- **M6 create status codes:** `POST /api/v1/inventory/adjustments`, `POST /api/v1/suppliers`, `POST /api/v1/purchasing/purchase-orders`, and `POST /api/v1/purchasing/goods-receipts` return **`201 Created`**.
- **Opening stock import response:** `OpeningStockImportResponse` includes `importedCount` alongside row/batch counts.
- **Supplier read model:** `SupplierResponse` includes `outstandingBalance`.
- **Purchase-order timeline shape:** timeline rows include `status`, `timestamp`, and `actor` (in addition to transition metadata).
- **Settlement writes:** dealer and supplier settlement routes both accept the same `PartnerSettlementRequest` body; use `partnerType` + `partnerId`, not retired dealer/supplier-specific request DTOs, and do not send a separate `payments` list.
- **Period writes:** both `POST /api/v1/accounting/periods` and `PUT /api/v1/accounting/periods/{periodId}` use `AccountingPeriodRequest`; close request/approve/reject use `PeriodCloseRequestActionRequest`, and reopen uses `AccountingPeriodReopenRequest`.
- **Period close:** frontend must follow maker-checker flow: request close → tenant-admin approvals inbox → approve/reject close.
- **Exports:** export approval belongs to `tenant-admin`; report consumption stays in `accounting`.
- **Dispatch:** dispatch confirmation belongs to `factory` even when invoice or journal side effects follow.

## Shared Topic Files

| Topic | Description |
|---|---|
| [auth-and-company-scope.md](./auth-and-company-scope.md) | Bootstrap, auth corridor, tenant-scoping, retired route warnings |
| [idempotency-and-errors.md](./idempotency-and-errors.md) | Idempotency keys, retry behavior, error contracts, failure handling |
| [pagination-and-filters.md](./pagination-and-filters.md) | List query patterns, cursor vs offset, filter syntax, sorting |
| [exports-and-approvals.md](./exports-and-approvals.md) | Export request workflows, approval gates, approval ownership |
| [accounting-reference-chains.md](./accounting-reference-chains.md) | Cross-document reference chains, audit trail, provenance fields |
| [dto-examples.md](./dto-examples.md) | Sample request/response payloads for common operations |

## Role-Oriented Handoff Files

| Role | File |
|---|---|
| Admin (`ROLE_ADMIN`) | [admin-role.md](./admin-role.md) |
| Accounting (`ROLE_ACCOUNTING`) | [accounting-role.md](./accounting-role.md) |
| Sales (`ROLE_SALES`) | [sales-role.md](./sales-role.md) |
| Dealer (`ROLE_DEALER`) | [dealer-role.md](./dealer-role.md) |

## Retired Routes

The following routes are no longer part of the current frontend contract:

| Retired Route | Replacement |
|---|---|
| `GET /api/v1/auth/profile` | Use `GET /api/v1/auth/me` for all identity data |
| `X-Company-Id` header | Use `X-Company-Code` header with `companyCode` value |
| `companyId` in frontend state | Use `companyCode` in frontend state and localStorage |
| `POST /api/v1/accounting/journals/manual` | Use `POST /api/v1/accounting/journal-entries` |
| `POST /api/v1/accounting/journal-entries/{entryId}/cascade-reverse` | Use `POST /api/v1/accounting/journal-entries/{entryId}/reverse` |
| `POST /api/v1/accounting/periods/{periodId}/close` | Use maker-checker flow: request close → admin approvals → approve/reject close |
| `DealerSettlementRequest` / `SupplierSettlementRequest` | Use `PartnerSettlementRequest` on both settlement routes |
| `AccountingPeriodUpsertRequest` / `AccountingPeriodUpdateRequest` | Use `AccountingPeriodRequest` for period create and update |

## Relationship to Frontend Portals

- Each portal in [`docs/frontend-portals/`](../frontend-portals/) has its own `api-contracts.md` that references this shared folder for common patterns.
- Portal-specific contracts are not repeated here — see individual portal folders.
- If this file disagrees with a portal's `api-contracts.md`, this `docs/frontend-api/` folder is the canonical source.

## Validation

This contract is validated against `openapi.json` and the mainline backend behavior. Run:

```bash
bash ci/lint-knowledgebase.sh
bash scripts/guard_openapi_contract_drift.sh
```
