# Tenant Runtime Dual Property Names

**Discovered during:** docs-platform-config milestone scrutiny review (2026-03-30)

## Observation

The tenant runtime subsystem has two services that read similar-sounding but **different** property names with **different** defaults:

| Service | Property | Default |
| --- | --- | --- |
| `TenantRuntimeEnforcementService` | `erp.tenant.runtime.default-max-concurrent-requests` | 200 |
| `TenantRuntimeEnforcementService` | `erp.tenant.runtime.default-max-requests-per-minute` | 5000 |
| `TenantRuntimeAccessService` | `erp.tenant.runtime.default.max-concurrent-requests` | 0 |
| `TenantRuntimeAccessService` | `erp.tenant.runtime.default.max-requests-per-minute` | 0 |

## Key differences

1. **Naming convention**: EnforcementService uses hyphenated (`default-max-...`), AccessService uses dot-separated (`default.max-...`).
2. **Defaults**: EnforcementService defaults to generous limits (200/5000), AccessService defaults to 0 (meaning disabled/no limit enforcement).
3. **Documentation gap**: The config-feature-toggles docs packet only documents the EnforcementService properties.

## Impact

- Maintainers configuring tenant runtime limits may set one set of properties and not realize the other service reads different keys.
- The docs at `docs/platform/config-feature-toggles.md` §2.1 should eventually cover both services' properties to avoid confusion.

## Source

- `TenantRuntimeEnforcementService.java` lines 62-66
- `TenantRuntimeAccessService.java` lines 79-83
