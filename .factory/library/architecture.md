# Architecture

High-level system map for the platform-owner-first ERP hard-cut mission.

This file is internal worker guidance for the current mission infrastructure, not a public canonical docs packet.

**What belongs here:** system shape, canonical route ownership, cross-module boundaries, mission invariants, and the target-state seams workers must preserve while simplifying the repo.

**Important:** this is the high-level architectural summary. `validation-contract.md` remains the route-complete definition of done for milestone validation.

---

## System Shape

The repository is a Java 21 / Spring Boot modular monolith rooted under `erp-domain/`.

Major runtime layers:
- `core/` — security, JWT/session handling, error envelopes, audit, health, infra utilities
- `modules/auth` — login, logout, refresh, password flows, MFA, `auth/me`
- `modules/company` — superadmin control plane, onboarding, lifecycle, limits, modules, billing-plan, support context, warnings, admin recovery, platform audit
- `modules/admin` — tenant-admin workflows, approvals inbox, tenant-admin support, tenant-admin self settings
- `modules/sales` — dealer directory, sales dashboard, promotions, order/credit/override request flows, dealer-commercial behavior
- `modules/portal` — tenant-business portal surfaces such as accounting-hosted portal support; this mission must respect portal ownership and not collapse it into platform or sales ownership
- other business modules (`accounting`, `inventory`, `factory`, `reports`, etc.) remain downstream tenant-business surfaces that platform-owner must not read directly

There is no in-repo frontend app for this mission. Frontend-facing truth lives in canonical docs under `docs/frontend-portals/**` and `docs/frontend-api/**`.

## Canonical Route Matrix

Treat these as canonical target-state route families for this mission's touched areas.

### Platform control plane (`/api/v1/superadmin/**`)
- `GET /api/v1/superadmin/dashboard`
- `GET /api/v1/superadmin/tenants`
- `GET /api/v1/superadmin/tenants/{id}`
- `GET /api/v1/superadmin/tenants/coa-templates`
- `POST /api/v1/superadmin/tenants/onboard`
- `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
- `PUT /api/v1/superadmin/tenants/{id}/limits`
- `PUT /api/v1/superadmin/tenants/{id}/modules`
- `PUT /api/v1/superadmin/tenants/{id}/billing-plan`
- `PUT /api/v1/superadmin/tenants/{id}/support/context`
- `POST /api/v1/superadmin/tenants/{id}/support/warnings`
- `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`
- `POST /api/v1/superadmin/tenants/{id}/force-logout`
- `PUT /api/v1/superadmin/tenants/{id}/admins/main`
- `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request`
- `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm`
- `GET /api/v1/superadmin/audit/platform-events`

### Platform support workspace (privacy-wall exception)
- `GET /api/v1/superadmin/support/tickets`
- `GET /api/v1/superadmin/support/tickets/{ticketId}`
- `PUT /api/v1/superadmin/support/tickets/{ticketId}/status`

This is the only platform surface allowed to expose full cross-tenant support-ticket content.

### Shared self-service (`/api/v1/auth/**`)
- `GET /api/v1/auth/me`
- `PUT /api/v1/auth/me/profile`
- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/password/forgot`
- `POST /api/v1/auth/password/reset`
- MFA setup/activate/disable under `/api/v1/auth/mfa/**`

### Tenant-admin-owned surfaces (`/api/v1/admin/**`)
- `GET /api/v1/admin/self/settings` is tenant-admin-only and is not shared self-service
- tenant-admin support remains under `/api/v1/admin/support/tickets/**`
- tenant-admin approvals inbox and decision ownership remain under `/api/v1/admin/approvals/**`
- canonical credit review decision: `POST /api/v1/admin/approvals/{originType}/{id}/decisions`

### Tenant business portal and dealer portal hosts (must remain distinct)
- accounting-hosted support remains under `/api/v1/portal/support/tickets/**`
- dealer self-service support remains under `/api/v1/dealer-portal/support/tickets/**`
- this mission must not resurrect the retired shared `/api/v1/support/**` host

### Sales ownership
- canonical dealer directory/create host: `/api/v1/dealers/**`
- canonical sales dashboard is sales-owned and must stay free of dispatch/accounting write affordances
- canonical promotions CRUD is sales-owned and promotions-only
- canonical sales order detail: `GET /api/v1/sales/orders/{id}`
- sales owns credit-limit request creation and credit override request creation
- tenant-admin approval inbox + generic decision route own the decision act for credit requests and overrides

## Core Mission Invariants

- Platform-owner must never receive tenant business data from platform dashboard/list/detail APIs.
- The only privacy-wall exception is the platform support workspace over ticket content plus bounded admin recovery actions.
- Platform audit is control-plane only and must not degrade into a tenant business audit feed.
- Manual billing-plan fields are phase-one billing truth; ARR/MRR rollups derive from billing-plan state, not tenant accounting ledgers.
- Shared self-service is for all authenticated human users; tenant-admin-only settings remain separate.
- Canonical dealer create must not auto-provision dealer portal identity unless an explicit user-provisioning flow is used.
- Generic tenant-admin approval inbox + decision route are the surviving review surfaces for credit requests and overrides.
- Retired routes and aliases must fail closed and disappear from canonical docs/OpenAPI when their hard cut lands.

## Boundary Map

### Platform vs tenant business
- platform-owned: onboarding, lifecycle, limits, modules, billing-plan, platform support workspace, admin recovery, platform audit
- tenant-owned business: accounting, inventory, factory execution, payroll, tenant business audit feeds, dealer business surfaces

### Shared self-service vs tenant-admin shell settings
- shared self-service is identity-bound and follows the authenticated user across scopes
- tenant-admin self settings remain host-owned by the tenant-admin shell and must not become the shared profile update path

### Sales vs admin approval ownership
- sales owns request creation, dealer commercial state, dashboard truth, promotions, and commercial order state
- tenant-admin approval owns the canonical review list and decision act for credit requests and overrides
- cleanup removes direct module-specific credit decision routes once generic approval ownership is live

### Support host splitting
- platform support workspace owns cross-tenant ticket visibility and lifecycle at the control-plane level
- tenant-admin support host remains tenant-admin-owned
- portal support host remains accounting-owned
- dealer-portal support host remains dealer-owned
- shared `/api/v1/support/**` stays retired

## Highest-Risk Retirements

Workers should treat these as the highest-risk hard-cut seams:
- shared `/api/v1/support/**` host
- `/api/v1/sales/dealers*` aliases once `/api/v1/dealers/**` becomes singular
- nested `/api/v1/companies/**` control-plane aliases
- direct module-specific credit decision routes once generic admin approval decisions are canonical
- contradictory docs/deprecated entries for `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`

## Runtime Boundary

Approved runtime boundary for this mission:
- Postgres `5433`
- RabbitMQ `5672`
- MailHog SMTP/UI `1025` / `8025`
- App HTTP `8081`
- Actuator `9090`

Workers must not introduce alternate application ports.

## Validation Model

Primary validation surfaces:
- compose-backed backend HTTP/API proof with `curl`
- MailHog capture for onboarding/reset emails
- repo-static OpenAPI/docs/retired-route proof
- targeted Maven proof packs for risky code paths

High-value end-to-end flow spine for this mission:
1. platform login -> `auth/me` -> dashboard/list/detail
2. onboarding -> MailHog credential capture -> tenant login -> `auth/me`
3. control-plane mutation -> tenant runtime effect -> platform recovery action
4. tenant support ticket creation -> platform support workspace visibility -> tenant business routes still denied
5. dealer create -> sales dashboard/credit flows -> tenant-admin approval decision

## Cleanup Posture

This mission is a hard cut, not a compatibility mission.

- Prefer deleting aliases/fallbacks/duplicate truth over preserving them.
- Do not leave two public write or review surfaces alive in touched mission areas.
- Treat docs/OpenAPI/route inventory as part of the architecture: a cleanup is incomplete if those artifacts still teach the old truth.
