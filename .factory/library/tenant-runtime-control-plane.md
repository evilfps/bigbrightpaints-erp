# Tenant Runtime Control Plane

Canonical tenant/runtime policy behavior for the Lane 01 packet.

**What belongs here:** canonical writer/reader surfaces, stale-path retirement notes, exact catching-lane expectations, and contract mappings that workers should preserve.

---

## Canonical writer

- Public mutation path: `PUT /api/v1/companies/{id}/tenant-runtime/policy`
- Path owner: `modules.company.controller.CompanyController`
- Service path: `CompanyService.updateTenantRuntimePolicy(...)` -> `modules.company.service.TenantRuntimeEnforcementService.updatePolicy(...)`
- Canonical snapshot fields: `companyCode`, `state`, `reasonCode`, `auditChainId`, `updatedAt`, `maxConcurrentRequests`, `maxRequestsPerMinute`, `maxActiveUsers`, `metrics`

## Stale path to retire in this packet

- `PUT /api/v1/admin/tenant-runtime/policy`
- `modules.admin.service.TenantRuntimePolicyService.updatePolicy(...)` as an independent public writer
- Any `CompanyContextFilter` / helper recognition that still treats the admin path as a separate privileged policy-control writer
- Published contract references that keep the stale admin writer alive (`openapi.json`, snapshot tests, manifest coverage)

## Canonical consumers to align

- `CompanyContextFilter` runtime admission and control-plane rebinding
- `modules.auth.service.AuthService` login/refresh/runtime denials
- `modules.portal.service.TenantRuntimeEnforcementInterceptor`
- `GET /api/v1/admin/tenant-runtime/metrics` as the tenant-scoped reader that must map canonical `auditChainId`/`updatedAt` to `policyReference`/`policyUpdatedAt`

## Catching lane

- Exact PR catching lane: `pr-auth-tenant`
- Repo commands:
  - `.factory/services.yaml -> commands.pr-auth-tenant`
  - `.factory/services.yaml -> commands.gate-fast`
  - `.factory/services.yaml -> commands.gate-core`
  - `.factory/services.yaml -> commands.lane01-targeted`
  - `.factory/services.yaml -> commands.lane01-router-check`

## Packet rules

- One branch, one slice, one catching lane.
- Delete stale writer code/tests/routes/compatibility paths in the same diff.
- If public API changes, update `.factory/library/frontend-handoff.md` and `.factory/library/frontend-v2.md` in the same packet; otherwise state explicitly that no frontend-handoff update was needed.
