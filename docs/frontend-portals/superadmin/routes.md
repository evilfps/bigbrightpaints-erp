# Routes

Recommended frontend route map:

| Route | Screen | Loader APIs | Primary actions | Guard |
|---|---|---|---|---|
| `/platform/dashboard` | platform overview | `GET /api/v1/superadmin/dashboard` | navigate to tenant detail or onboarding | `ROLE_SUPER_ADMIN` only |
| `/platform/tenants` | tenant list | `GET /api/v1/superadmin/tenants?status=` | filter by lifecycle, open tenant detail, start onboarding | `ROLE_SUPER_ADMIN` only |
| `/platform/tenants/new` | tenant onboarding wizard | `GET /api/v1/superadmin/tenants/coa-templates` | submit onboarding | `ROLE_SUPER_ADMIN` only |
| `/platform/tenants/:tenantId` | tenant detail overview | `GET /api/v1/superadmin/tenants/{id}` | read onboarding truth, usage, support timeline | `ROLE_SUPER_ADMIN` only |
| `/platform/tenants/:tenantId/lifecycle` | lifecycle change form | `GET /api/v1/superadmin/tenants/{id}` | `PUT /api/v1/superadmin/tenants/{id}/lifecycle` | `ROLE_SUPER_ADMIN` only |
| `/platform/tenants/:tenantId/limits` | quota management | `GET /api/v1/superadmin/tenants/{id}` | `PUT /api/v1/superadmin/tenants/{id}/limits` | `ROLE_SUPER_ADMIN` only |
| `/platform/tenants/:tenantId/modules` | module gating | `GET /api/v1/superadmin/tenants/{id}` | `PUT /api/v1/superadmin/tenants/{id}/modules` | `ROLE_SUPER_ADMIN` only |
| `/platform/tenants/:tenantId/support` | support context and recovery | `GET /api/v1/superadmin/tenants/{id}` | warning, support context, admin password reset, force logout | `ROLE_SUPER_ADMIN` only |
| `/platform/tenants/:tenantId/admin-access` | main-admin operations | `GET /api/v1/superadmin/tenants/{id}` | replace main admin, request email change, confirm email change | `ROLE_SUPER_ADMIN` only |

Route design rules:

- Use `tenantId` from the URL for all tenant mutations.
- Keep all destructive actions behind explicit confirmation modals.
- Re-fetch tenant detail after every mutation instead of optimistic local patching of nested support and limit objects.
