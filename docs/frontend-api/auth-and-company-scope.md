# Auth and Company Scope

Last reviewed: 2026-03-31

## Bootstrap Surface

The only supported frontend identity bootstrap endpoint is:

```
GET /api/v1/auth/me
```

This endpoint returns a claim/context-derived payload containing:

- `email` — the authenticated user's email address
- `displayName` — the user's display name
- `companyCode` — the tenant identifier (required for tenant-scoped sessions)
- `mfaEnabled` — whether MFA is active for this user
- `mustChangePassword` — whether the user must change password before accessing any other surface
- `roles` — flattened role list (e.g., `ROLE_TENANT_ADMIN`, `ROLE_ACCOUNTING`)
- `permissions` — flattened permission list

### Response Example

```json
{
  "success": true,
  "data": {
    "email": "admin@example.com",
    "displayName": "John Admin",
    "companyCode": "ACME01",
    "mfaEnabled": false,
    "mustChangePassword": false,
    "roles": ["ROLE_TENANT_ADMIN", "ROLE_ADMIN_SALES_FACTORY_ACCOUNTING"],
    "permissions": [
      "user:read", "user:write", "export:approve", "report:read"
    ]
  },
  "message": "Current user retrieved",
  "timestamp": "2026-03-31T10:00:00Z"
}
```

## Non-Canonical Identity Endpoints

The following endpoints exist in the API but are **not the canonical bootstrap surface**:

| Endpoint | Status | Replacement |
|---|---|---|
| `GET /api/v1/auth/profile` | Exists but **not recommended** for frontend use | Use `GET /api/v1/auth/me` for all identity data |

### Why `/api/v1/auth/profile` is Not Recommended

- `/api/v1/auth/me` is claim/context-derived and returns the authoritative identity for the current session.
- `/api/v1/auth/profile` is a thin CRUD wrapper over the `app_users` table and does not reflect session state, roles, or permissions.
- Frontend code that calls `/api/v1/auth/profile` may reflect stale or inconsistent user state.
- **Recommendation:** Use `GET /api/v1/auth/me` as the sole identity bootstrap surface.

## Auth Corridor

### Must-Change-Password Behavior

If `GET /api/v1/auth/me` returns `mustChangePassword: true`, the frontend **must** route into the password-change corridor before showing any normal tenant-scoped shell:

1. Show the password-change form instead of any dashboard or navigation.
2. Block access to all other routes until the password is changed.
3. Use `POST /api/v1/auth/password/change` to complete the corridor.

### Session Refresh

Use the refresh token flow to obtain a fresh access token:

```
POST /api/v1/auth/refresh-token
```

**Request:**

```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**Response:**

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 3600
  },
  "message": "Token refreshed",
  "timestamp": "2026-03-31T10:00:00Z"
}
```

### Logout

```
POST /api/v1/auth/logout
```

Optionally pass the refresh token to revoke it:

```
POST /api/v1/auth/logout?refreshToken=eyJhbGciOiJIUzI1NiJ9...
```

## Tenant Scope

### Tenant Identifier

**Use `companyCode` as the persisted tenant scope identifier:**

- Store `companyCode` in frontend state and localStorage.
- Send `companyCode` in API requests via the `X-Company-Code` header.
- Do not use `companyId` (numeric) for tenant-shell auth — it is retired.

### Request Header

For tenant-scoped requests, send:

```
X-Company-Code: ACME01
```

**Do not send:**

- `X-Company-Id` header — this is retired.
- Numeric tenant IDs in the request body or query parameters for tenant-scoped operations.

### Superadmin Exception

Numeric tenant IDs are allowed **only** as superadmin route parameters:

```
GET /api/v1/superadmin/tenants/{tenantId}
```

In this context, `{tenantId}` is a numeric ID because superadmin operates across tenants. For all tenant-scoped operations, use `companyCode`.

## Role Boundaries

| Role | Portal Shell | Authorized Routes |
|---|---|---|
| `ROLE_SUPER_ADMIN` | superadmin | `/api/v1/superadmin/**` |
| `ROLE_TENANT_ADMIN` | tenant-admin | `/api/v1/admin/**` (except superadmin-only paths) |
| `ROLE_ACCOUNTING` | accounting | `/api/v1/accounting/**` |
| `ROLE_SALES` | sales | `/api/v1/sales/**` |
| `ROLE_FACTORY` | factory | `/api/v1/factory/**` |
| `ROLE_DEALER` | dealer-client | `/api/v1/dealer-portal/**` |

A user with `ROLE_SUPER_ADMIN` must never be sent to tenant-admin screens even if the approval payload shape would otherwise match.

## Password Change

```
POST /api/v1/auth/password/change
```

**Request:**

```json
{
  "currentPassword": "oldPassword123",
  "newPassword": "NewPassword456!",
  "confirmPassword": "NewPassword456!"
}
```

Password policy requirements (if enforced):
- Minimum length
- Uppercase, lowercase, number, special character
- History check (cannot reuse last N passwords)

## MFA

### Setup

```
POST /api/v1/auth/mfa/setup
```

Returns raw secret, otpauth URI, and recovery codes.

### Activate

```
POST /api/v1/auth/mfa/activate
```

**Request:**

```json
{
  "code": "123456"
}
```

### Disable

```
POST /api/v1/auth/mfa/disable
```

**Request:**

```json
{
  "code": "123456"
}
```

## Links

- See [`docs/frontend-portals/README.md`](../frontend-portals/README.md) for portal ownership.
- See [`auth-identity.md`](../code-review/flows/auth-identity.md) for backend flow details.
- See ADR-001 for multi-tenant auth scoping decisions.
