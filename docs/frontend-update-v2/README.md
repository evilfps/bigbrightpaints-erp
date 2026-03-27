# Frontend update v2 tracker

This folder is the dedicated review surface for frontend follow-up from the `security-auth-hardening` mission. Every auth/admin/lifecycle hardening change in this branch is listed here, including explicit no-op entries when no frontend code change is required.

## Overall contract summary

- `GET /api/v1/admin/approvals` now returns typed approval items with `originType` and `ownerType`; the legacy `type` and `sourcePortal` fields are removed. Export approval rows keep machine-readable `reportType` for all inbox viewers, while `parameters`, `requesterUserId`, and `requesterEmail` are redacted for accounting-only viewers.
- `GET /api/v1/admin/exports/pending` is retired. Tenant-scoped admin/accounting approval consumers should use `GET /api/v1/admin/approvals` as the single inbox; platform super-admins remain blocked from that tenant-admin prefix.
- `POST /api/v1/superadmin/tenants/onboard` now makes seeded bootstrap explicit via `bootstrapMode`, `seededChartOfAccounts`, `defaultAccountingPeriodCreated`, and `tenantAdminProvisioned`.
- `GET /api/v1/companies/superadmin/dashboard` is retired; `GET /api/v1/superadmin/dashboard` remains the public aggregate-count dashboard route and is not a drop-in replacement for the retired detailed tenant payload.
- `POST /api/v1/auth/password/forgot` now requires `companyCode` alongside `email`, and the retired `POST /api/v1/auth/password/forgot/superadmin` alias is deleted from the canonical contract.
- `POST /api/v1/superadmin/tenants/onboard` no longer returns plaintext password fields or `credentialsEmailSent`; onboarding now exposes only explicit bootstrap-status fields plus `adminEmail`.
- `POST /api/v1/auth/password/forgot` still masks unknown or disabled identities behind the generic success payload, but known scoped accounts now fail closed when reset-token storage or email delivery/configuration fails.
- Tenant-admin foreign-target `suspend`, `unsuspend`, `mfa/disable`, and `delete` flows keep the same masked `400 User not found` contract; the latest regression fix only removes an internal cross-tenant lock side effect.
- Hardened error semantics and authorization boundaries are tracked below so review does not rely on implicit knowledge.

## Per-change tracker

| Feature | Area | Frontend action | Review status | Detail |
| --- | --- | --- | --- | --- |
| `auth-token-secret-storage-hardening` | Auth token storage | None | No frontend code change required | [auth-token-secret-storage-hardening.md](./auth-token-secret-storage-hardening.md) |
| `auth-session-revocation-hardening` | Session lifecycle | Review session-clearing UX | Confirm rejected stale-token paths route back to login consistently | [auth-session-revocation-hardening.md](./auth-session-revocation-hardening.md) |
| `auth-reset-recovery-contract-hardening` | Reset and recovery | Required request update | Send `companyCode` with forgot-password and remove any use of the retired super-admin alias | [auth-reset-recovery-contract-hardening.md](./auth-reset-recovery-contract-hardening.md) |
| `reset-token-issuance-race-hardening` | Reset issuance | None | No frontend code change required | [reset-token-issuance-race-hardening.md](./reset-token-issuance-race-hardening.md) |
| `must-change-password-corridor-hardening` | Auth workflow | Required workflow confirmation | Keep `mustChangePassword` users in the password-change corridor | [must-change-password-corridor-hardening.md](./must-change-password-corridor-hardening.md) |
| `global-security-settings-authorization` | Admin settings RBAC | Required RBAC/UI follow-up | Hide or disable global settings mutation for tenant admins | [global-security-settings-authorization.md](./global-security-settings-authorization.md) |
| `control-plane-approval-bootstrap-contract-cleanup` | Admin and tenant control plane | Required contract review | Switch tenant-scoped approval consumers to `originType`/`ownerType`, remove any onboarding plaintext-password UI, and read explicit tenant bootstrap success fields | [control-plane-approval-bootstrap-contract-cleanup.md](./control-plane-approval-bootstrap-contract-cleanup.md) |
| `controlled-auth-error-contracts` | Auth error handling | Review only | Optional UX mapping for new explicit error codes and reasons | [controlled-auth-error-contracts.md](./controlled-auth-error-contracts.md) |
| `privileged-user-boundary-hardening` | Admin user controls | None | No frontend code change required; later masking refinement noted separately | [privileged-user-boundary-hardening.md](./privileged-user-boundary-hardening.md) |
| `auth-compatibility-regression-handoff` | Contract verification | None | No frontend code change required; documentation alignment only | [auth-compatibility-regression-handoff.md](./auth-compatibility-regression-handoff.md) |
| `tenant-lifecycle-rollout-safety-hardening` | Tenant lifecycle | None | No frontend code change required | [tenant-lifecycle-rollout-safety-hardening.md](./tenant-lifecycle-rollout-safety-hardening.md) |
| `masked-admin-target-lookup-hardening` | Admin target masking | Review only | Treat foreign-target and missing-target 400s the same | [masked-admin-target-lookup-hardening.md](./masked-admin-target-lookup-hardening.md) |
| `masked-admin-lock-scope-regression-fix` | Admin lock scope | None | No frontend code change required; masked foreign-target behavior is unchanged while cross-tenant contention is removed | [masked-admin-lock-scope-regression-fix.md](./masked-admin-lock-scope-regression-fix.md) |
| `runtime-policy-cache-invalidation-regression-fix` | Company runtime policy | None | No frontend code change required; same-node enforcement now refreshes immediately after canonical policy updates | [runtime-policy-cache-invalidation-regression-fix.md](./runtime-policy-cache-invalidation-regression-fix.md) |
| `forgot-password-persistence-failure-regression-fix` | Forgot-password error handling | Review only | Allow controlled non-success responses when reset-token persistence fails before dispatch | [forgot-password-persistence-failure-regression-fix.md](./forgot-password-persistence-failure-regression-fix.md) |
