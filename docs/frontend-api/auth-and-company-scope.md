# Auth And Company Scope

## Canonical auth routes

- `POST /api/v1/auth/login`
- `GET /api/v1/auth/me`
- `POST /api/v1/auth/refresh-token`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/password/forgot`
- `POST /api/v1/auth/password/reset`

## Scope rules

- Frontend must persist `companyCode`, never `companyId`, `cid`, or numeric
  tenant ids for tenant-scoped shells.
- Tenant-scoped requests must send `X-Company-Code` when the backend contract
  requires tenant context.
- `X-Company-Id` is a fail-closed legacy reject path, not a supported header.
- Do not build tenant switching around alternate company identifiers. Resolve
  the active tenant shell from `companyCode` and the current auth state only.
- Superadmin flows are not mounted inside tenant-scoped shells and should not
  attach tenant headers unless a specific support action requires it.
- The only safe use of a numeric tenant id in frontend code is as a
  superadmin-only route param such as `/platform/tenants/:tenantId`.

## Identity bootstrap contract

- Login success returns tokens and the scoped `companyCode`.
- `GET /api/v1/auth/me` is the only screen-bootstrap identity read.
- `/auth/me` response should be treated as the source of truth for:
  - `email`
  - `displayName`
  - `companyCode`
  - `roles`
  - `permissions`
  - `mfaEnabled`
  - `mustChangePassword`
- `/api/v1/auth/profile` is retired. Do not build fetch, edit, optimistic cache,
  or profile-retry logic for it.

Example shape:

```json
{
  "success": true,
  "data": {
    "email": "controller@acme.test",
    "displayName": "Tenant Controller",
    "companyCode": "ACME",
    "roles": ["ROLE_ADMIN"],
    "permissions": ["accounting.journals.write", "reports.export.request"],
    "mfaEnabled": true,
    "mustChangePassword": false
  }
}
```

Frontend contract:

- Hydrate the session shell from `/auth/me`, not from the login response alone.
- Treat `/auth/me` as the cache invalidation truth after refresh-token,
  password-change, role updates, or lifecycle changes.
- If `/auth/me` fails with tenant-scope denial, exit the current tenant shell
  and show a hard-stop state instead of retrying with fallback headers.

## Must-change-password corridor

When `/auth/me` or login state indicates `mustChangePassword=true`, the frontend
must route immediately into password change and suppress normal protected work.
Only these auth routes remain usable during that corridor:

- `GET /api/v1/auth/me`
- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/refresh-token`

Any `403` with `reason=PASSWORD_CHANGE_REQUIRED` should be handled as workflow
state, not generic auth drift.
