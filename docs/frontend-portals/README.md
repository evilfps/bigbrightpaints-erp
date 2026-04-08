# Frontend Portal Contract

`docs/frontend-portals/` is the frontend ownership map for the ERP shell split.
Older tracker-style notes under `docs/frontend-update-v2/` are delta/history
references only. They are not a second source of truth for routing, API
ownership, or portal placement.

Last reviewed: 2026-04-08

Exact portal folders:
- `superadmin`
- `tenant-admin`
- `accounting`
- `sales`
- `factory`
- `dealer-client`

Every portal folder in this tree is part of the canonical handoff contract.
Each folder must carry the same file set:

- `README.md`
- `routes.md`
- `api-contracts.md`
- `workflows.md`
- `role-boundaries.md`
- `states-and-errors.md`
- `playwright-journeys.md`

## Canonical Portal Navigation Map

Use this map to navigate every active portal contract surface from this README.

### Superadmin

- [README](./superadmin/README.md)
- [routes](./superadmin/routes.md)
- [api-contracts](./superadmin/api-contracts.md)
- [workflows](./superadmin/workflows.md)
- [role-boundaries](./superadmin/role-boundaries.md)
- [states-and-errors](./superadmin/states-and-errors.md)
- [playwright-journeys](./superadmin/playwright-journeys.md)

### Tenant Admin

- [README](./tenant-admin/README.md)
- [routes](./tenant-admin/routes.md)
- [api-contracts](./tenant-admin/api-contracts.md)
- [workflows](./tenant-admin/workflows.md)
- [role-boundaries](./tenant-admin/role-boundaries.md)
- [states-and-errors](./tenant-admin/states-and-errors.md)
- [playwright-journeys](./tenant-admin/playwright-journeys.md)

### Accounting

- [README](./accounting/README.md)
- [routes](./accounting/routes.md)
- [api-contracts](./accounting/api-contracts.md)
- [workflows](./accounting/workflows.md)
- [role-boundaries](./accounting/role-boundaries.md)
- [states-and-errors](./accounting/states-and-errors.md)
- [playwright-journeys](./accounting/playwright-journeys.md)

### Sales

- [README](./sales/README.md)
- [routes](./sales/routes.md)
- [api-contracts](./sales/api-contracts.md)
- [workflows](./sales/workflows.md)
- [role-boundaries](./sales/role-boundaries.md)
- [states-and-errors](./sales/states-and-errors.md)
- [playwright-journeys](./sales/playwright-journeys.md)
- [frontend-engineer-handoff (reference-only)](./sales/frontend-engineer-handoff.md)

### Factory

- [README](./factory/README.md)
- [routes](./factory/routes.md)
- [api-contracts](./factory/api-contracts.md)
- [workflows](./factory/workflows.md)
- [role-boundaries](./factory/role-boundaries.md)
- [states-and-errors](./factory/states-and-errors.md)
- [playwright-journeys](./factory/playwright-journeys.md)

### Dealer Client

- [README](./dealer-client/README.md)
- [routes](./dealer-client/routes.md)
- [api-contracts](./dealer-client/api-contracts.md)
- [workflows](./dealer-client/workflows.md)
- [role-boundaries](./dealer-client/role-boundaries.md)
- [states-and-errors](./dealer-client/states-and-errors.md)
- [playwright-journeys](./dealer-client/playwright-journeys.md)

## Non-negotiable current-state rules

- Use `GET /api/v1/auth/me` as the only frontend identity bootstrap.
- Persist tenant scope as `companyCode`, never `companyId`.
- Tenant-scoped requests use `X-Company-Code`; do not send `X-Company-Id`.
- Do not wire retired surfaces such as `/api/v1/auth/profile`.
- Superadmin UX is platform control-plane only and must stay on `/api/v1/superadmin/**`.
- Tenant-admin UX owns `/api/v1/admin/users/**` and `GET /api/v1/admin/approvals`.
- Export approvals belong to tenant-admin, not accounting.
- Numeric tenant ids are allowed only as superadmin route params inside
  `/platform/tenants/:tenantId`. They are not tenant-shell auth identifiers.

## Folder ownership summary

| Folder | Owns | Must not own |
|---|---|---|
| `superadmin` | tenant onboarding, COA template selection, lifecycle, limits, modules, support recovery, platform changelog | tenant user CRUD, accounting execution, factory execution, dealer self-service |
| `tenant-admin` | session bootstrap, user lifecycle, export approvals, support tickets, tenant changelog | superadmin control plane, accounting journals/reconciliation, factory dispatch execution |
| `accounting` | COA, default accounts, GST or tax setup, product-account readiness, journals, reversals, reconciliation, period close, opening stock, AR/AP settlement, reports | tenant onboarding, tenant user CRUD, dispatch confirm |
| `sales` | dealer master, sales orders, reservation visibility, credit escalation, commercial dashboards | dispatch confirm, production or packing, accounting correction |
| `factory` | production, packing, packaging mappings, batch lineage, dispatch preparation, dispatch confirm | COA/default accounts/tax setup, settlements, tenant administration |
| `dealer-client` | dealer dashboard, orders, invoices, ledger, aging, support, credit requests | internal sales editing, factory execution, accounting correction, admin approvals |

## How frontend engineers should use this tree

1. Read [`portal-matrix.md`](./portal-matrix.md) to place a screen in the correct folder.
2. Open the target portal `README.md` first.
3. Build the page using that portal's `routes.md`, `api-contracts.md`, and `workflows.md`.
4. Use `states-and-errors.md` to implement loading, empty, validation, and failure UX.
5. Convert `playwright-journeys.md` into browser tests after screens are wired.
6. Use `docs/frontend-update-v2/` only when you need branch-history context for
   a specific older hardening note; if it disagrees with this tree or
   `docs/frontend-api/`, this tree wins.

## Shared shell rules

- `superadmin` runs with a platform-scoped session and must not render tenant business navigation.
- `tenant-admin` runs with a tenant-scoped session and must always derive the active tenant from `companyCode`.
- `accounting`, `sales`, `factory`, and `dealer-client` are also tenant-scoped shells and must derive the active tenant from `companyCode`, not from a numeric tenant id.
- If `GET /api/v1/auth/me` reports `mustChangePassword=true`, route into the password-change corridor before showing any normal tenant-scoped shell.
- A user with `ROLE_SUPER_ADMIN` must never be sent to tenant-admin screens even if the approval payload shape would otherwise match.
