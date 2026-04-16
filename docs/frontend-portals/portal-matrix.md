# Portal Matrix

Last reviewed: 2026-04-15

| Folder | Primary route base | Backend prefixes this portal owns | Core screens | Must not own |
| --- | --- | --- | --- | --- |
| `superadmin` | `/platform/*` | `/api/v1/superadmin/tenants/**`, `/api/v1/superadmin/changelog`, `/api/v1/superadmin/dashboard` | tenant onboarding/lifecycle/limits/modules/support recovery, platform changelog | tenant-admin product screens, accounting/factory/dealer shells |
| `tenant-admin` | `/tenant/*` | `/api/v1/admin/dashboard`, `/api/v1/admin/users/**`, `/api/v1/admin/approvals`, `POST /api/v1/admin/approvals/{originType}/{id}/decisions`, `/api/v1/admin/support/tickets/**`, `/api/v1/admin/audit/events`, `/api/v1/admin/self/settings`, `/api/v1/changelog`, shell bootstrap from `GET /api/v1/auth/me` | dashboard, users, approvals, audit, support, settings, changelog | control plane, accounting journals, production execution, dealer self-service |
| `accounting` | `/accounting/*` | `/api/v1/accounting/**`, `GET /api/v1/reports/**`, accounting-owned `/api/v1/portal/support/tickets/**` | COA, journals, reconciliation, period close, finance reports, accounting support workflows | tenant onboarding, tenant-admin user CRUD, dispatch execution, dealer self-service |
| `sales` | `/sales/*` | `/api/v1/sales/**`, `/api/v1/dealers/**`, `/api/v1/credit/**` | dealer management, sales orders, credit escalation | dispatch confirm, accounting journals, tenant-admin approvals |
| `factory` | `/factory/*` | `/api/v1/factory/**`, dispatch operational endpoints | production, packing, dispatch execution | accounting governance, tenant-admin product workflows |
| `dealer-client` | `/dealer/*` | `/api/v1/dealer-portal/**` | dealer dashboard/orders/invoices/aging/support | internal admin/accounting/sales/factory controls |

Placement rules:

- Tenant lifecycle, module, limits, and support recovery always stay in `superadmin`.
- Tenant-admin decisions always use `POST /api/v1/admin/approvals/{originType}/{id}/decisions`.
- Tenant-admin internal support UX must use `/api/v1/admin/support/tickets/**`.
- Accounting portal support workflows use `/api/v1/portal/support/tickets/**`.
