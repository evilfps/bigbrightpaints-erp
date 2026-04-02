# ADR-002: Multi-Tenant Auth Scoping via JWT Company Claims

Last reviewed: 2026-04-02

## Status

Accepted

## Context

The orchestrator-erp backend is a multi-tenant SaaS platform. Every authenticated request must be scoped to exactly one company (tenant) so that data isolation, access control, and lifecycle enforcement all operate within the correct tenant boundary.

The system serves three actor categories:

- **Tenant users** — admin, accounting, and other tenant-scoped roles who operate entirely within their own company context.
- **Dealer users** — external dealer contacts who access a self-service portal scoped to their own company.
- **Super admins** — platform operators who manage tenant lifecycle and control-plane operations across all companies but must not execute tenant business workflows.

Before this decision, some paths used numeric `X-Company-Id` headers, while others relied on JWT claims. This created ambiguity about the source of truth for tenant context and left room for cross-tenant access through header injection.

## Decision

Tenant context is derived exclusively from the JWT `companyCode` claim, enforced by `CompanyContextFilter` in the security filter chain:

1. **JWT claim is authoritative.** Every authenticated JWT carries a `companyCode` claim. The filter reads this claim and populates a `ThreadLocal`-backed `CompanyContextHolder` for downstream use.
2. **Header is optional but must agree.** An `X-Company-Code` header is accepted but must match the JWT claim. Mismatched headers are rejected.
3. **Legacy numeric header is rejected.** `X-Company-Id` is explicitly rejected with an error message directing callers to use `X-Company-Code`.
4. **Unauthenticated requests cannot set tenant context.** If no JWT is present, the header is ignored.
5. **Super admins operate in platform scope.** Super-admin requests to `/api/v1/superadmin/*` use a platform-scoped authentication model. Super admins are explicitly blocked from tenant business endpoints (sales, inventory, factory, purchasing, HR, etc.) and may only access lifecycle control-plane routes.
6. **Company lifecycle gates requests.** `CompanyLifecycleState` is checked for every tenant-scoped request: `ACTIVE` allows full access, `SUSPENDED` allows reads only, `DEACTIVATED` denies all access.
7. **Tenant runtime admission is enforced.** Beyond lifecycle state, `TenantRuntimeRequestAdmissionService` applies per-tenant runtime limits and module gating before admitting the request.

## Alternatives Rejected

1. **Header-only scoping** — vulnerable to header injection and inconsistent enforcement across endpoints.
2. **Database lookup per request** — would add latency to every request; the JWT claim is already validated cryptographically.
3. **Separate auth services per tenant** — adds operational complexity disproportionate to the current scale of a modular monolith.
4. **Open super-admin access to tenant workflows** — would violate the principle that platform operators should not impersonate tenant business actors.

## Consequences

- Every authenticated request has a clear, cryptographically verified tenant context.
- Downstream services use `CompanyContextHolder.getCompanyCode()` or `CompanyContextService` without worrying about how context was resolved.
- Super admins cannot accidentally or intentionally execute tenant business operations, reducing the blast radius of compromised super-admin credentials.
- Tenant lifecycle changes (suspend, deactivate) take effect immediately on the next request.
- The filter must remain the single enforcement point; bypassing it would require a corresponding security review and ADR update.

## Cross-references

- [docs/RELIABILITY.md](../RELIABILITY.md) — tenant isolation and data integrity guarantees
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — module map and tenant-scoping architecture
- [docs/SECURITY.md](../SECURITY.md) — security review policy and R2 escalation triggers
- ADR-006 — portal and host boundary separation for admin vs dealer surfaces
