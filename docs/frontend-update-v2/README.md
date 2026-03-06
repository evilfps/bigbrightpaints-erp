# Frontend update v2 tracker

This folder is the dedicated review surface for frontend follow-up from the `security-auth-hardening` mission. Every auth/admin/lifecycle hardening change in this branch is listed here, including explicit no-op entries when no frontend code change is required.

## Overall contract summary

- No auth/admin request-body shapes or success-response payload shapes were changed for the supported mission endpoints.
- The only request/response contract delta that may require endpoint migration is the retired compatibility alias `POST /api/v1/auth/password/forgot/superadmin`, which now returns `410 Gone` with canonical migration pointers.
- Hardened error semantics and authorization boundaries are tracked below so review does not rely on implicit knowledge.

## Per-change tracker

| Feature | Area | Frontend action | Review status | Detail |
| --- | --- | --- | --- | --- |
| `auth-token-secret-storage-hardening` | Auth token storage | None | No frontend code change required | [auth-token-secret-storage-hardening.md](./auth-token-secret-storage-hardening.md) |
| `auth-session-revocation-hardening` | Session lifecycle | Review session-clearing UX | Confirm rejected stale-token paths route back to login consistently | [auth-session-revocation-hardening.md](./auth-session-revocation-hardening.md) |
| `auth-reset-recovery-contract-hardening` | Reset and recovery | Conditional migration review | Verify no shipped client still calls the retired super-admin forgot alias | [auth-reset-recovery-contract-hardening.md](./auth-reset-recovery-contract-hardening.md) |
| `reset-token-issuance-race-hardening` | Reset issuance | None | No frontend code change required | [reset-token-issuance-race-hardening.md](./reset-token-issuance-race-hardening.md) |
| `must-change-password-corridor-hardening` | Auth workflow | Required workflow confirmation | Keep `mustChangePassword` users in the password-change corridor | [must-change-password-corridor-hardening.md](./must-change-password-corridor-hardening.md) |
| `global-security-settings-authorization` | Admin settings RBAC | Required RBAC/UI follow-up | Hide or disable global settings mutation for tenant admins | [global-security-settings-authorization.md](./global-security-settings-authorization.md) |
| `controlled-auth-error-contracts` | Auth error handling | Review only | Optional UX mapping for new explicit error codes and reasons | [controlled-auth-error-contracts.md](./controlled-auth-error-contracts.md) |
| `privileged-user-boundary-hardening` | Admin user controls | None | No frontend code change required; later masking refinement noted separately | [privileged-user-boundary-hardening.md](./privileged-user-boundary-hardening.md) |
| `auth-compatibility-regression-handoff` | Contract verification | None | No frontend code change required; documentation alignment only | [auth-compatibility-regression-handoff.md](./auth-compatibility-regression-handoff.md) |
| `tenant-lifecycle-rollout-safety-hardening` | Tenant lifecycle | None | No frontend code change required | [tenant-lifecycle-rollout-safety-hardening.md](./tenant-lifecycle-rollout-safety-hardening.md) |
| `masked-admin-target-lookup-hardening` | Admin target masking | Review only | Treat foreign-target and missing-target 400s the same | [masked-admin-target-lookup-hardening.md](./masked-admin-target-lookup-hardening.md) |
| `runtime-policy-cache-invalidation-regression-fix` | Company runtime policy | None | No frontend code change required; same-node enforcement now refreshes immediately after canonical policy updates | [runtime-policy-cache-invalidation-regression-fix.md](./runtime-policy-cache-invalidation-regression-fix.md) |
