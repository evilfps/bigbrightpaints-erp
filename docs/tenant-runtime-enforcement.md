# Tenant Runtime Enforcement (Quota + Hold/Block + Audit Chain)

Last reviewed: 2026-02-18
Owner: Platform control-plane runtime

## Purpose
- Enforce tenant hold/block and quota policy at request runtime.
- Keep a metrics surface per tenant for superadmin observability.
- Preserve an audit chain when requests are denied by tenant controls.

## Runtime Entry Point
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/TenantRuntimeEnforcementService.java`

## Policy Keys (`system_settings`)
Keys are read from `system_settings.setting_key` / `setting_value`.

Default policy:
- `tenant.runtime.default.state` (`ACTIVE`, `HOLD`, `BLOCKED`)
- `tenant.runtime.default.quota.max-concurrent` (integer, `0` = unlimited)
- `tenant.runtime.default.quota.max-requests-per-minute` (integer, `0` = unlimited)

Per-tenant override:
- `tenant.runtime.<tenant-token>.state`
- `tenant.runtime.<tenant-token>.reason-code`
- `tenant.runtime.<tenant-token>.quota.max-concurrent`
- `tenant.runtime.<tenant-token>.quota.max-requests-per-minute`

Notes:
- `<tenant-token>` is normalized from company code (`lowercase`, non `[a-z0-9-]` -> `_`).
- Policy lookup is cached in-memory for a short TTL (default `15s`) for runtime efficiency.

## Enforcement Semantics
- `BLOCKED`: deny all tenant-scoped requests (`423 Locked`).
- `HOLD`: deny mutating tenant-scoped requests (`POST/PUT/PATCH/DELETE`, `423 Locked`).
- Quota breach:
  - concurrent requests -> `429 Too Many Requests`
  - requests per minute -> `429 Too Many Requests`

## Tenant Metrics Tracked
- total requests seen
- allowed requests
- denied requests (total + hold + block + quota)
- in-flight active requests
- requests in current minute window

Micrometer gauges (tag: `tenant`):
- `tenant.runtime.requests.active`
- `tenant.runtime.requests.total`
- `tenant.runtime.requests.denied`

## Audit Chain On Denial
Every enforcement denial emits:
- Legacy audit log surface:
  - `AuditService.logFailure(AuditEvent.ACCESS_DENIED, metadata)`
- Immutable enterprise audit trail surface:
  - `EnterpriseAuditTrailService.recordBusinessEvent(...)`
  - module/action: `tenant-control-plane` / `TENANT_RUNTIME_ENFORCEMENT_DENIED`

Metadata includes company code, reason code, tenant state, quota limits, request method/path, and runtime counters.
