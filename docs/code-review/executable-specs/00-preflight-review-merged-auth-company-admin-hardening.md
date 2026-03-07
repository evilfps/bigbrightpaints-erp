# Preflight review: merged auth/company/admin hardening on `Factory-droid`

## Source of truth and review window

- Executable-spec source of truth: `docs/packet-governance-baseline:docs/code-review/executable-specs/**`
- Reviewed base branch window: `origin/Factory-droid~15..origin/Factory-droid`
- Governing packets/specs used for classification:
  - `00-current-auth-merge-gate.md`
  - `01-lane-control-plane-runtime/EXEC-SPEC.md`
  - `02-lane-auth-secrets-incident/EXEC-SPEC.md`
  - validation assertions `VAL-WFLOW-002` and `VAL-PREFLIGHT-001`

## Inventory mapped to the executable-spec package

| Merged change on `origin/Factory-droid` | Evidence reviewed | Executable-spec mapping | Verdict |
| --- | --- | --- | --- |
| `d150c976` `fix(company): invalidate canonical runtime policy cache immediately` | `CompanyContextFilter`, `TenantRuntimeEnforcementService`, `CompanyControllerIT`, `TenantRuntimeEnforcementServiceTest` | Merge-gate `TEN-10`; Lane 01 Packet 2 (`runtime-policy truth`) | **Closed already**. Canonical `PUT /api/v1/companies/{id}/tenant-runtime/policy` is tracked as privileged policy control, and the targeted runtime-policy suites pass. |
| `ece11e57` `fix(auth): surface forgot-password token persistence failures` | `PasswordResetService`, `AuthPasswordResetPublicContractIT`, `PasswordResetServiceTest` | Merge-gate `AUTH-09`; Lane 02 Packet 0 (`close current auth-branch regressions`) | **Still open**. The public forgot path still calls `dispatchResetEmail(..., suppressFailures=true)`, and the current tests explicitly accept `200 OK` masking when token persistence fails. |
| `4eaafbf0` `fix(auth): scope masked admin lock acquisition to tenant lookup` | `AdminUserService`, `AdminUserServiceTest`, `AdminUserSecurityIT` | Merge-gate `ADMIN-14`; Lane 02 Packet 0 (`masked foreign-tenant boundary fix`) | **Closed already**. Tenant-admin masked foreign-user actions now use `lockByIdAndCompanyId(...)`; the global lock path is reserved for super-admin flows. |
| `f1219ef4` + `81ca3d94` token-revocation timing follow-up | `TokenBlacklistService`, `JwtTokenService`, `JwtAuthenticationFilter`, `TokenBlacklistServiceTest` | Validation contract `VAL-MERGE-004`; merge-gate adjunct needed before Lane 02 session hardening | **Still open**. `TokenBlacklistService` still truncates issuance and revocation instants to milliseconds and compares with `isBefore(...)`, so same-millisecond earlier issuance is not revoked. |
| Earlier merged auth/company/admin foundations (`7f793d64`, `e8fd1195`, `07e617af`) | git history plus current auth/company/admin review docs | Lane 01 / Lane 02 foundations, not the remaining merge gate | **Already merged context**. These stay outside the remaining gate unless a new regression is found. |

## Exact regression classification

| Item | Status on `origin/Factory-droid` | Why |
| --- | --- | --- |
| `TEN-10` | **Closed** | Canonical company runtime-policy writes are now admitted/tracked as policy-control requests, and the targeted runtime-policy suites pass. |
| `AUTH-09` | **Open** | Public forgot-password still suppresses token-persistence failures behind the generic success contract instead of returning a controlled non-success result. |
| `ADMIN-14` | **Closed** | Tenant-admin masked foreign-target flows no longer acquire a foreign global write lock before scope resolution. |
| token-revocation precision | **Open** | Same-millisecond ordering is still lossy: the current `TokenBlacklistServiceTest` fails on `origin/Factory-droid` because truncation to milliseconds hides sub-millisecond ordering. |

## Packet-boundary note

### What belongs in the merge-gate fix packet now

Only the still-open branch-cleanliness regressions:

1. `AUTH-09` — change the public forgot-password path so unknown-user and delivery/configuration outcomes stay masked, but token-persistence/storage failure becomes a controlled non-success result.
2. token-revocation precision — remove the millisecond-truncation behavior so `isUserTokenRevoked(...)` preserves ordering when issuance and revocation happen inside the same millisecond.
3. Re-proof only the already-closed guardrails (`TEN-10`, `ADMIN-14`) in the same regression pack so the gate exits with an explicit clean verdict.

### What does **not** belong in the merge-gate fix packet

- token-digest migration / schema rollout
- lifecycle vocabulary redesign
- global-vs-tenant settings authorization redesign
- broader session/lockout/MFA/incident-response cleanup
- runtime-policy model consolidation beyond the already-closed cache invalidation regression

### What remains for Lane 01

Lane 01 still owns the control-plane/runtime backlog after the gate is clean: `TEN-01`, `TEN-02`, `TEN-04`, `TEN-05`, `TEN-06`, `TEN-07`, `TEN-09`, `ADMIN-01`, `ADMIN-09`, and the broader “one authoritative runtime-policy truth” convergence work. `TEN-10` should now be treated as a regression guardrail, not as open Lane 01 scope.

### What remains for Lane 02

Lane 02 still owns the broader auth/secrets/incident-response backlog after the gate is clean: `TEN-03`, `AUTH-01`, `AUTH-02`, `AUTH-03`, `AUTH-04`, `AUTH-05`, `AUTH-06`, `AUTH-07`, `ADMIN-08`, and `OPS-02`, plus any larger session-hardening work beyond the narrow token-revocation precision fix. `AUTH-09` is blocked from Lane 02 expansion until the merge gate closes.

## Review conclusion

The originally named merge-gate trio is no longer fully open: `TEN-10` and `ADMIN-14` are already closed on `origin/Factory-droid`, while `AUTH-09` remains open. The executable-spec package should therefore be interpreted as requiring one last **narrow** merge-gate packet for `AUTH-09` plus token-revocation precision, followed by normal Lane 01 and Lane 02 work on the remaining backlog.
