# Auth and Company Scope

Last reviewed: 2026-04-16

## Canonical Auth Routes

- `POST /api/v1/auth/login`
- `GET /api/v1/auth/me`
- `POST /api/v1/auth/refresh-token`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/password/forgot`
- `POST /api/v1/auth/password/reset`
- `POST /api/v1/auth/mfa/setup`
- `POST /api/v1/auth/mfa/activate`
- `POST /api/v1/auth/mfa/disable`

## Scope Rules

- Persist `companyCode`, never numeric `companyId`, for tenant shells.
- Tenant-scoped requests must send `X-Company-Code`.
- `X-Company-Id` is retired and must not be sent.
- Do not build tenant switching around alternate company identifiers.
- Superadmin shell is separate and must not be mounted inside tenant-admin routes.
- Platform-only superadmin hosts (`settings`, `roles`, `notify`) require the
  platform auth scope code; tenant-scoped superadmin sessions are denied on
  those hosts.

## Bootstrap Contract

Use this as the sole frontend identity bootstrap endpoint:

```text
GET /api/v1/auth/me
```

Important fields:

- `email`
- `displayName`
- `companyCode`
- `mfaEnabled`
- `mustChangePassword`
- `roles`
- `permissions`

### Must-change-password corridor

If `/auth/me` returns `mustChangePassword=true`:

1. Route to password-change immediately.
2. Block normal tenant shell routes until password change succeeds.
3. Re-hydrate session state with `GET /api/v1/auth/me` after change.

### Session refresh

```text
POST /api/v1/auth/refresh-token
```

Use refreshed token pair, then re-fetch `/auth/me`.

### Logout

```text
POST /api/v1/auth/logout
```

May include refresh token for explicit revocation.

## Tenant-admin self/settings integration

Tenant-admin self/settings UX uses:

- `GET /api/v1/admin/self/settings` for self settings read model
- auth APIs for password and MFA mutations

Do not use `/api/v1/auth/profile`; it is non-canonical for frontend identity bootstrap.

## Role Boundaries

| Role | Portal shell | Canonical route ownership |
| --- | --- | --- |
| `ROLE_SUPER_ADMIN` | superadmin | `/api/v1/superadmin/**` control-plane routes |
| `ROLE_ADMIN` | tenant-admin | tenant-scoped `/api/v1/admin/**` workflows |
| `ROLE_ACCOUNTING` | accounting | `/api/v1/accounting/**`, accounting portal workflows |
| `ROLE_SALES` | sales | `/api/v1/sales/**`, sales portal workflows |
| `ROLE_FACTORY` | factory | `/api/v1/factory/**`, dispatch/production workflows |
| `ROLE_DEALER` | dealer-client | `/api/v1/dealer-portal/**` |

Boundary notes:

- A `ROLE_SUPER_ADMIN` user must not be routed into tenant-admin shell routes.
- Tenant-admin workflows are tenant-scoped; control-plane actions remain superadmin-only.
- Tenant-scoped superadmin sessions are explicitly denied on `/api/v1/superadmin/settings`,
  `/api/v1/superadmin/roles`, and `/api/v1/superadmin/notify`.
