# Auth Hardening

Mission-specific guidance for the security/auth remediation wave.

**What belongs here:** Current security/auth/authz priorities, compatibility rules, regression hotspots, and rollout caveats.

---

## Priority findings to address in this mission
- `AUTH-02` — plaintext refresh/reset token storage
- `AUTH-03` — `mustChangePassword` is advisory only
- `AUTH-05` / `AUTH-07` — active session invalidation gaps
- `AUTH-01` — broken super-admin forgot/recovery alias drift
- `AUTH-04` — reset delivery integrity gaps
- `ADMIN-08` — inconsistent cross-tenant privileged user-control behavior
- `ADMIN-01` — tenant admins can currently mutate global security-sensitive settings
- `TEN-03` — temporary credential exposure risk

## Compatibility rules
- Preserve existing auth/admin request and response shapes wherever possible.
- Prefer additive change over breaking change.
- If a shape change is unavoidable, update `.factory/library/frontend-handoff.md` in the same feature and say exactly what changed.
- Characterization tests come before implementation whenever a frontend-sensitive endpoint is touched.

## Rollout caveats
- `V158__auth_token_digest_storage.sql` adds `token_digest` columns for refresh-token and password-reset storage while keeping legacy token columns nullable during the transition.
- `AuthSecretStorageBackfillRunner` backfills legacy plaintext rows to digest-only storage at startup.
- Runtime lookup/revoke/reset flows still fall back to legacy raw-token columns so pre-existing rows remain usable during rollout even before every node has restarted onto the backfill-capable build.
- As of 2026-03-06 `auth-reset-recovery-contract-hardening`, public forgot-password suppresses delivery/configuration failures without leaving a newly issued undispatched reset token behind, tenant-admin force-reset now requires successful reset-email delivery before it returns success, and the stale `/api/v1/auth/password/forgot/superadmin` alias returns a controlled `410 Gone` compatibility contract pointing callers at the canonical self-service and support-recovery paths.
- As of 2026-03-06 `reset-token-issuance-race-hardening`, public forgot-password and admin-triggered reset issuance now serialize on the target user and replace prior reset tokens inside the same issuance transaction, so overlapping requests follow deterministic last-writer-wins semantics and never strand the user without a valid latest reset link.
- As of 2026-03-07 `forgot-password-persistence-failure-regression-fix`, public forgot-password still masks unknown-user, disabled-user, and reset-email delivery/configuration failures behind the generic success response, but reset-token persistence failures now surface as a controlled non-success application error instead of returning false-success `200 OK`.
- As of 2026-03-06 `privileged-user-boundary-hardening`, tenant-admin out-of-scope privileged user-control attempts now fail with the existing `403` auth envelope plus `ACCESS_DENIED` audit metadata across force-reset, status disable/enable, suspend, unsuspend, delete, and MFA-disable flows, while super-admins use the same endpoints cross-tenant. The company control-plane lifecycle path also re-accepts `HOLD`/`BLOCKED` compatibility aliases while persisting the internal lifecycle mapping expected by current services.
- As of 2026-03-06 `must-change-password-corridor-hardening`, authenticated users flagged `mustChangePassword` are restricted to login/refresh plus `/auth/me`, `GET /auth/profile`, password change, and logout until they successfully change the password. Denied out-of-corridor requests return a `403` `ApiResponse` with `reason=PASSWORD_CHANGE_REQUIRED` and `mustChangePassword=true`, and access tokens now carry an `iatMs` claim so revocation checks stay precise even when password change/logout and relogin happen in the same second.
- As of 2026-03-06 `global-security-settings-authorization`, tenant admins can still read tenant runtime metrics but can no longer mutate `PUT /api/v1/admin/settings`; that platform-wide settings path now requires `ROLE_SUPER_ADMIN` so global CORS, mail, auto-approval, export-approval, and comparable runtime-security toggles cannot be changed from a tenant-scoped admin session.
- As of 2026-03-06 `controlled-auth-error-contracts`, auth-sensitive failures no longer leak raw `sendError` or framework `ProblemDetail` responses on the hardened paths. Login lockout now emits `AUTH_005`, company-context mismatches and control-plane tenant-binding denials emit `403` `ApiResponse` contracts with `AUTH_004` plus explicit `reason` / `reasonDetail`, and login/auth tenant runtime hold-block-quota denials emit controlled `ApiResponse` bodies with their runtime denial codes and audit-chain metadata.
- As of 2026-03-06 `tenant-lifecycle-rollout-safety-hardening`, supported runtime and integration-test paths stay on Flyway v2, tenant lifecycle writes continue to persist schema-compatible `ACTIVE` / `HOLD` / `BLOCKED` backing values during suspend/activate/deactivate flows, and any unrecognized stored lifecycle value now fails closed as `DEACTIVATED` instead of silently reopening tenant access.
- As of 2026-03-07 `masked-admin-target-lookup-hardening`, tenant-admin requests to auth-sensitive admin user-control endpoints now mask foreign-tenant user ids behind the same `User not found` validation contract used for missing ids while still emitting internal `ACCESS_DENIED` audit metadata. The role-management controller also enforces the shared-role mutation boundary up front via the same super-admin authorization rule already required by `RoleService`, so tenant admins are rejected before role mutation logic executes.
- As of 2026-03-07 `masked-admin-lock-scope-regression-fix`, tenant-admin suspend, unsuspend, delete, and MFA-disable denials still return the same masked `400 User not found` contract for foreign ids, but those paths now acquire pessimistic locks only through company-scoped lookup and fall back to non-locking existence checks solely for internal denial auditing. This preserves the masked external behavior while removing the cross-tenant lock contention side effect.
- As of 2026-03-07 `runtime-policy-cache-invalidation-regression-fix`, canonical `PUT /api/v1/companies/{id}/tenant-runtime/policy` updates persist the refreshed tenant runtime policy and invalidate the live cache immediately, so successful company-scoped runtime-policy changes take effect on the handling node without waiting for TTL expiry.
- As of 2026-03-15 `lane01-tenant-runtime-canonicalization`, the stale public writer `PUT /api/v1/admin/tenant-runtime/policy` is retired; `PUT /api/v1/companies/{id}/tenant-runtime/policy` is the only public runtime-policy mutation path, while `GET /api/v1/admin/tenant-runtime/metrics` remains the tenant-scoped read surface.

## Session invalidation rules
- As of 2026-03-06, logout, password change, password reset, disablement, lockout, and support hard-reset all revoke prior access sessions through `UserTokenRevocation` and delete outstanding refresh tokens for the affected user.
- `JwtAuthenticationFilter` now fails closed for locked users in addition to disabled users and token-revocation markers, so previously issued bearer tokens stop authenticating once a lockout event lands.
- `JwtAuthenticationFilter` prefers the custom JWT `iatMs` claim when present, then falls back to the standard `iat` claim, so revocation markers and immediately reissued tokens compare on millisecond precision instead of second-level JWT rounding.
- There is no per-device session lineage model in the current auth stack, so logout hardening intentionally invalidates all active sessions for the authenticated user instead of trying to preserve sibling sessions.

## Adjacent regression flows to recheck
- login / refresh / logout
- `/auth/me` and `/auth/profile`
- public forgot / reset password
- admin force reset password
- support tenant-admin reset
- suspend / unsuspend / disable / delete / MFA disable
- admin settings authz and tenant binding
- company-code / company-id header enforcement on auth-sensitive endpoints

## Strict review expectation
- Every feature handoff must name the adjacent ERP-sensitive flows that were rechecked.
- Scrutiny should treat missing regression evidence as a quality failure, not a documentation nit.
- If a fix closes one auth/security gap by widening another boundary, return to the orchestrator instead of guessing.
