# ADR-006: Portal and Host Boundary Separation

Last reviewed: 2026-04-02

## Status

Accepted

## Context

The orchestrator-erp backend serves three distinct actor categories through different API host prefixes. Before this decision was formalized, endpoint ownership between admin/internal and dealer/self-service surfaces was inconsistent — some dealer-facing operations were mixed into admin controllers, and some admin-only endpoints lived under shared prefixes.

The system currently exposes these major host prefixes:

| Host prefix | Owner module | Primary actors |
| --- | --- | --- |
| `/api/v1/portal/*` | `portal` | Admin, Accounting (internal portal) |
| `/api/v1/dealer-portal/*` | `sales` (dealer portal) | Dealer contacts (self-service) |
| `/api/v1/superadmin/*` | `company` (control plane) | Super Admin (platform operations) |
| `/api/v1/admin/*` | `admin` | Admin (tenant-scoped operations) |
| `/api/v1/sales/*`, `/api/v1/dealers/*` | `sales` | Admin (dealer/order management) |
| `/api/v1/invoices/*` | `invoice` | Admin (invoice management) |
| `/api/v1/factory/*`, `/api/v1/production/*` | `factory`, `production` | Admin (operations) |
| `/api/v1/inventory/*` | `inventory` | Admin (stock management) |
| `/api/v1/purchasing/*`, `/api/v1/suppliers/*` | `purchasing` | Admin (procurement) |
| `/api/v1/accounting/*` | `accounting` | Admin, Accounting (finance) |
| `/api/v1/hr/*`, `/api/v1/payroll/*` | `hr` | Admin, HR |
| `/api/v1/reports/*` | `reports` | Admin, Accounting |

## Decision

1. **Admin/internal portal and dealer/self-service portal are separate host boundaries.**
   - `/api/v1/portal/*` is owned by the `portal` module and serves internal admin/accounting users.
   - `/api/v1/dealer-portal/*` is owned by the `sales` module and serves external dealer contacts.
   - These two surfaces must not share controllers or RBAC guards.

2. **Dealer self-service is read-mostly with explicit write exceptions.** The dealer portal allows dealers to view orders, invoices, statements, and aging, and to submit credit limit requests and support tickets. Mutation operations are limited to dealer-initiated actions (credit requests, support tickets) and are routed through dedicated dealer-portal controllers rather than shared admin endpoints.

3. **Role-action matrices enforce host ownership.** `PortalRoleActionMatrix` defines role predicates for portal controllers (`ADMIN_OR_ACCOUNTING` for internal, `DEALER_ONLY` for self-service). These predicates are applied at the controller class level via `@PreAuthorize`.

4. **Super-admin access is control-plane only.** Super admins are explicitly blocked from tenant business endpoints by `CompanyContextFilter`. They may access only `/api/v1/superadmin/*` lifecycle and policy routes, plus a narrow set of platform-scoped endpoints (`/api/v1/companies`, `/api/v1/auth/*`, `/api/v1/admin/settings`).

5. **Cross-portal references go through service facades, not shared controllers.** When the admin portal needs to display dealer-facing data (e.g., support ticket lists), it accesses it through the `portal` module's own controllers, which may call into `sales` or `admin` services internally.

## Alternatives Rejected

1. **Shared controllers with role-based branching** — creates confusing single endpoints that behave differently based on caller role, making testing and documentation harder.
2. **Single portal host for both admin and dealer** — would blur the security boundary between internal and external actors.
3. **Dealer portal as a separate microservice** — adds deployment complexity disproportionate to the current scale.
4. **Super-admin access to all tenant data** — would violate the principle of least privilege and create a single point of credential-compromise blast radius.

## Consequences

- Frontend teams can clearly identify which host prefix to call based on the actor type.
- RBAC enforcement is explicit at the controller level rather than hidden in service logic.
- Security audits can review admin/internal and dealer/self-service boundaries independently.
- Adding new dealer-facing features requires routing through the dealer-portal host, not adding dealer roles to admin controllers.
- Cross-portal data needs (admin viewing dealer data, dealer viewing limited admin data) must go through service facades, keeping controller ownership clean.

## Cross-references

- ADR-002 — multi-tenant auth scoping, which enforces the JWT company claim that underpins portal isolation
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — module map and host ownership
- [docs/SECURITY.md](../SECURITY.md) — security review policy
