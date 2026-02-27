# Incoming Audit Findings Intake (2026-02-27)

Source: user-provided audit for `harness-engineering-orchestrator`
Audit compared: `HEAD bf9816b9` vs `session/agent_75c8802b... (50014a58)`
Recorded at: `2026-02-27T12:15:49Z`

## Intake Status

- status: accepted
- action_policy: fix in blocker order with criticals first
- tracking_ticket: `TKT-ERP-STAGE-113`

## Critical Findings (Must Fix)

- `C1` CompanyContextFilter early return on public password-reset bypasses tenant checks and finally clear.
- `C2` CompanyContextFilter `shouldNotFilter` null-path behavior creates noisy enforcement metrics.
- `C3` CompanyService still imports `jakarta.transaction.Transactional`.
- `C4` RoleService still imports `jakarta.transaction.Transactional`.
- `C5` IntegrationCoordinator `lockAutoApprovalState` returns detached entity and saga mutations not persisted.
- `C6` IntegrationCoordinator `health()` cross-tenant/global read risk without company scope.
- `C7` PasswordResetService super-admin reset flow transactional/race semantics and cleanup behavior gaps.
- `C8` PasswordResetService token race on reset path (`findByToken` without lock).

## High Findings (Queue Immediately After Critical)

- `H1` SUPER_ADMIN control-plane bypass can target any company by design without membership guard.
- `H2` CompanyService constructor/path can create tenant without provisioned admin in failure paths.
- `H3` TenantAdminProvisioningService reset ordering allows password change before guaranteed token revocation.
- `H4` EventPublisherService relies on thread-local company context in enqueue path.
- `H5` OrchestratorIdempotencyService orphan `IN_PROGRESS` risk around callback/crash windows.
- `H6` IntegrationCoordinator restores previous thread company context instead of always clearing.
- `H7` Company code comparison normalization gaps (hidden-char handling).
- `H8` ProductionCatalogService still imports `jakarta.transaction.Transactional`.
- `H9` PasswordResetService transaction-assertion fragility in token template path.
- `H10` CommandDispatcher double-failure marking semantics.

## Medium Findings (Backlog, Fix During Same Wave if Touched)

- `M1` CompanyContextFilter non-`UserPrincipal` access deny behavior lacks explicit traceability/logging.
- `M2` CompanyContextFilter path-coupled policy-control authority check fragility.
- `M3` CompanyService concurrent company-code create race before DB unique constraint.
- `M4` PasswordResetService sends email inside transaction.
- `M5` TenantAdminProvisioningService sends credential email before transaction commit.
- `M6` IntegrationCoordinator payroll command path can leave idempotency state churn under hard-throw flow.
- `M7` OrchestratorIdempotencyService weak hash fallback.
- `M8` RoleService permission creation race (`ensurePermissionExists`).
- `M9` JwtProperties `isTestOnlyRuntime` profile handling edge case.
- `M11` CompanyService error-rate guard can understate when activity/error counters are inconsistent.

## Blocker Ownership Mapping

- Primary `B10` (auth/company/rbac recovery continuity): `C1,C2,C3,C4,C7,C8,H1,H2,H3,H7,M1,M2,M3,M4,M5,M8,M9,M11`
- Primary `B11` (factory/rework/reopen + orchestrator continuity): `C5,C6,H6,M6`
- Primary `B12`/`B09` follow-up (orchestrator/report denial contracts): `H4,H5,H10,M7`
- Primary `B13`/inventory hardening follow-up: `H8`

## Next Execution Order

1. Claim `B10` and implement transaction + tenant-context/auth recovery criticals first.
2. Validate with targeted auth/company/rbac suites and changed-files coverage.
3. Move to `B11` integration continuity criticals (`C5,C6,H6`).
4. Carry orchestrator residuals (`H4,H5,H10,M7`) as explicit follow-up slice if not fully covered by B11/B12.
