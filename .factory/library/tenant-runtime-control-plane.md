# Tenant Runtime Control Plane

Canonical tenant/runtime policy behavior for the Lane 01 packet.

**What belongs here:** canonical writer/reader surfaces, stale-path retirement notes, exact catching-lane expectations, and contract mappings that workers should preserve.

---

## Canonical writer

- Canonical control-plane mutation path: `PUT /api/v1/superadmin/tenants/{id}/limits`
- Canonical lifecycle companion path: `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
- Path owner: `modules.company.controller.SuperAdminController`
- Service path: `SuperAdminTenantControlPlaneService.updateLimits(...)` / `updateLifecycleState(...)`
- Canonical snapshot fields: tenant detail plus limits/lifecycle response DTOs under the superadmin tenant control plane

## Retired aliases

- Old admin/company runtime-policy aliases stay retired from the published contract.
- `modules.admin.service.TenantRuntimePolicyService` remains an internal implementation seam, not a separate public writer.
- `CompanyContextFilter` and runtime helpers should recognize only the canonical `/api/v1/superadmin/tenants/{id}/...` control plane as the public mutation story.

## Canonical consumers to align

- `CompanyContextFilter` runtime admission and control-plane rebinding
- `modules.auth.service.AuthService` login/refresh/runtime denials
- `modules.portal.service.TenantRuntimeEnforcementInterceptor`
- Superadmin tenant detail / limits docs and proofs that explain the same lifecycle/quota ownership

## Catching lane

- Feature proof lives on `.factory/services.yaml -> commands.targeted-security-proof`
- Contract drift guard lives on `.factory/services.yaml -> commands.contract-guards`
- `gate-fast` remains the broader merge-confidence lane

## Packet rules

- One branch, one slice, one catching lane.
- Delete stale writer code/tests/routes/compatibility paths in the same diff.
- If public API changes, update `.factory/library/frontend-handoff.md` and `.factory/library/frontend-v2.md` in the same packet; otherwise state explicitly that no frontend-handoff update was needed.
