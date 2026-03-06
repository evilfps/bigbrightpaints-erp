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

## Session invalidation rules
- As of 2026-03-06, logout, password change, password reset, disablement, lockout, and support hard-reset all revoke prior access sessions through `UserTokenRevocation` and delete outstanding refresh tokens for the affected user.
- `JwtAuthenticationFilter` now fails closed for locked users in addition to disabled users and token-revocation markers, so previously issued bearer tokens stop authenticating once a lockout event lands.
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
