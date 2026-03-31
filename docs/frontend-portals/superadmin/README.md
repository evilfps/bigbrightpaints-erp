# Superadmin Portal

## Purpose

This portal is the platform-owner control plane. It is the only frontend shell that may create tenants, seed their chart of accounts, mutate tenant lifecycle, change limits and modules, recover tenant admin access, and publish the global changelog.

## Users

- Allowed: `ROLE_SUPER_ADMIN`
- Disallowed: tenant admins, accounting users, sales, factory, dealer users

## What belongs here

- Platform dashboard and tenant list
- Tenant onboarding with COA template selection
- Tenant detail with onboarding truth, limits, usage, support context, support timeline, and main-admin summary
- Lifecycle mutation
- Quota and module mutation
- Warning issuance, forced logout, admin password reset, main-admin replacement, admin email change
- Superadmin changelog authoring

## What does not belong here

- Tenant approval inbox
- Tenant user CRUD
- Any accounting, sales, factory, or dealer workflow
- Any route under `/api/v1/admin/**`, `/api/v1/accounting/**`, `/api/v1/factory/**`, or `/api/v1/dealer-portal/**`

## Information architecture

Recommended navigation:

- `Dashboard`
- `Tenants`
- `Changelog`

Recommended tenant-detail tabs:

- `Overview`
- `Lifecycle`
- `Limits`
- `Modules`
- `Support`
- `Admin Access`

## Critical frontend rules

- Treat `POST /api/v1/superadmin/tenants/onboard` as successful only when `seededChartOfAccounts`, `defaultAccountingPeriodCreated`, and `tenantAdminProvisioned` are all `true`.
- Do not ask frontend operators to manage `companyCode` headers manually for control-plane routes. The tenant target is in the path.
- Do not reuse the tenant-admin shell chrome here.
- Keep lifecycle state vocabulary exact: `ACTIVE`, `SUSPENDED`, `DEACTIVATED`.
