# Frontend API Contract

`docs/frontend-api/` documents shared API rules across all frontend portal shells.

Last reviewed: 2026-04-16

## Purpose

This folder defines canonical frontend contracts for:

- auth bootstrap and corridor handling
- tenant scoping conventions
- shared error/idempotency/pagination behavior
- cross-portal approval/reporting boundaries

## Current Mainline Rules

### Bootstrap and Authentication

- Only identity bootstrap endpoint: `GET /api/v1/auth/me`.
- Do not use `GET /api/v1/auth/profile` for frontend identity bootstrap.
- Use `POST /api/v1/auth/refresh-token` for access-token refresh.
- Use `POST /api/v1/auth/logout` for session termination.

### Tenant Scope

- Persist tenant scope as `companyCode`.
- Send `X-Company-Code` for tenant-scoped requests.
- Do not use `companyId`/`X-Company-Id` in tenant-shell flows.

### Portal Placement

- **superadmin:** platform control-plane routes (`/api/v1/superadmin/**`); tenant-scoped superadmin sessions are denied on platform-only hosts (`settings`, `roles`, `notify`), while tenant-targeted control routes remain tenant-scoped.
- **tenant-admin:** canonical `/api/v1/admin/**` product surfaces (`/dashboard`, `/users`, `/approvals`, `/support`, `/self/settings`, `/audit`) plus `/api/v1/changelog`; legacy admin insight reads `/api/v1/portal/dashboard|operations|workforce` are still live until backend retirement.
- **accounting:** COA, journals, reconciliation, period close, reports.
- **sales:** dealer master, sales orders, commercial credit escalation.
- **factory:** production, packing, dispatch confirmation.
- **dealer-client:** dealer self-service dashboard/orders/invoices/aging/support.

### Additional Contract Rules

- **Manual journals:** `POST /api/v1/accounting/journal-entries` is canonical.
- **Reversals:** `POST /api/v1/accounting/journal-entries/{entryId}/reverse` is canonical.
- **Period close:** frontend must follow maker-checker flow: request close -> tenant-admin approval inbox -> approve/reject.
- **Exports and approvals:** tenant-admin approval decisions use generic admin decision endpoint; accounting consumes approved report outputs.

## Shared Topic Files

| Topic | Description |
| --- | --- |
| [auth-and-company-scope.md](./auth-and-company-scope.md) | Auth bootstrap, corridor, tenant scope rules |
| [idempotency-and-errors.md](./idempotency-and-errors.md) | Idempotency and error contracts |
| [pagination-and-filters.md](./pagination-and-filters.md) | List, filter, and paging conventions |
| [exports-and-approvals.md](./exports-and-approvals.md) | Export and approval flow contracts |
| [accounting-reference-chains.md](./accounting-reference-chains.md) | Accounting linkage and reference-chain contracts |
| [dto-examples.md](./dto-examples.md) | Canonical DTO examples |

## Role-Oriented Handoff Files

| Role | File |
| --- | --- |
| Admin (`ROLE_ADMIN`) | [admin-role.md](./admin-role.md) |
| Accounting (`ROLE_ACCOUNTING`) | [accounting-role.md](./accounting-role.md) |
| Sales (`ROLE_SALES`) | [sales-role.md](./sales-role.md) |
| Dealer (`ROLE_DEALER`) | [dealer-role.md](./dealer-role.md) |

## Relationship to Portal Docs

- Portal-specific contracts live in `docs/frontend-portals/**`.
- If shared contract text conflicts with portal docs, this `docs/frontend-api/` folder is canonical for shared behavior.

## Validation

```bash
bash ci/lint-knowledgebase.sh
bash scripts/guard_openapi_contract_drift.sh
```
