# Superadmin Control Plane

## Purpose
- `ROLE_SUPER_ADMIN` is the platform-owner role for ERP operators.
- This role manages tenants and platform limits; it is not the tenant's day-to-day operating role.

## What Superadmin Can Do
- View platform dashboard metrics across all tenants.
- Create tenant records with region and limit envelopes (users/API/storage/concurrency).
- Update tenant lifecycle and runtime control-plane policies (`ACTIVE`, `HOLD`, `BLOCKED`).
- Issue support warnings before service hold/block actions.
- Trigger tenant-admin credential reset for support cases.

## What Superadmin Must Not Do
- Tenant admins must not assign `ROLE_SUPER_ADMIN`.
- Tenant admin role lists must not expose `ROLE_SUPER_ADMIN` to non-superadmin users.
- Superadmin actions must be audit-evidenced with actor, target tenant, and reason metadata.

## Core APIs
- `GET /api/v1/companies/superadmin/dashboard`
- `POST /api/v1/companies/superadmin/tenants`
- `PUT /api/v1/companies/superadmin/tenants/{id}`
- `POST /api/v1/companies/{id}/support/warnings`
- `POST /api/v1/companies/{id}/support/admin-password-reset`
- `POST /api/v1/companies/{id}/lifecycle-state`
- `PUT /api/v1/companies/{id}/tenant-runtime/policy`

## Onboarding Contract
- Superadmin creates tenant with basic company profile + quotas + region.
- Superadmin can provide the first admin email at onboarding.
- Tenant admin receives credentials and must complete first-login password update flow.
