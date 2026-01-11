# API Portal Matrix

This matrix maps portal expectations to endpoint groups and roles for RBAC review.
For the full endpoint list, see `erp-domain/docs/endpoint_inventory.tsv`.

## Permit-all endpoints
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh-token`
- `POST /api/v1/auth/password/forgot`
- `POST /api/v1/auth/password/reset`
- `GET /api/integration/health`
- `GET /actuator/health` (+ subpaths)
- `/swagger-ui/**`, `/v3/api-docs/**`, `/v3/api-docs.yaml`

## Admin portal
- `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`, `POST /api/v1/auth/password/change` -> authenticated
- `/api/v1/admin/**` -> `ROLE_ADMIN`
- `/api/v1/roles/**` -> `ROLE_ADMIN`
- `/api/v1/companies` (list) -> `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`
- `/api/v1/companies/{id}` (update/delete) -> `ROLE_ADMIN`
- `POST /api/v1/multi-company/companies/switch` -> authenticated + membership check
- `/api/v1/portal/**` -> `ROLE_ADMIN`
- `/api/v1/orchestrator/dashboard/admin` -> `ROLE_ADMIN`
- `/api/v1/orchestrator/dashboard/finance` -> `ROLE_ADMIN`, `ROLE_ACCOUNTING`
- `/api/v1/orchestrator/dashboard/factory` -> `ROLE_ADMIN`, `ROLE_FACTORY`

## Accounting portal
- `/api/v1/accounting/**` -> `ROLE_ADMIN`, `ROLE_ACCOUNTING`
- `/api/v1/reports/**` -> `ROLE_ADMIN`, `ROLE_ACCOUNTING`

## Sales portal
- `/api/v1/sales/**` -> `ROLE_ADMIN`, `ROLE_SALES`
- `/api/v1/dealers/**` -> `ROLE_ADMIN`, `ROLE_SALES`
- `/api/v1/credit-limit/**` -> `ROLE_ADMIN`, `ROLE_SALES` (and accounting for approvals)

## Manufacturing portal
- `/api/v1/factory/**` -> `ROLE_ADMIN`, `ROLE_FACTORY`
- `/api/v1/production/**` -> `ROLE_ADMIN`, `ROLE_FACTORY`

## Inventory portal
- `/api/v1/inventory/**` -> `ROLE_ADMIN`, `ROLE_FACTORY` (some endpoints include sales/accounting)

## HR / Payroll portal
- `/api/v1/hr/**` -> `ROLE_ADMIN`, `ROLE_ACCOUNTING`

## Dealer portal (read-only, self-scoped)
- `/api/v1/dealer-portal/**` -> `ROLE_DEALER`
- Dealer access to `/api/v1/dealers/{dealerId}/ledger|invoices|aging` is intentionally restricted to admin/sales.
