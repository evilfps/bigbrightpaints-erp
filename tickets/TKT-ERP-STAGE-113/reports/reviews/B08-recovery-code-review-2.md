# B08 Recovery Code Review 2

- Ticket: `TKT-ERP-STAGE-113`
- Branch: `tickets/tkt-erp-stage-113/b08-auth-secret-hardening-recovery`
- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B08-auth-secret-hardening-recovery`
- Commit reviewed (head-only): `3ee922ea4f2f5f39cd19542ab86d59a3b3136a75`
- Scope: regressions, behavioral risk, and test adequacy for JWT fallback policy, dev-admin bootstrap challenge, and config hardening

## Findings (Severity-Ordered)

### 1) [P1][high] Dev profile hardening reads a different encryption env var than the documented/runtime contract

- Impacted file anchors:
  - `erp-domain/src/main/resources/application-dev.yml:34`
  - `docker-compose.yml:65`
  - `erp-domain/docs/STABILIZATION_LOG.md:59`
- Concrete failure/regression scenario:
  1. Operator follows the existing dev runbook and supplies `ERP_SECURITY_ENCRYPTION_KEY` (as documented and used by compose/runtime references).
  2. With this head, `application-dev.yml` now resolves `erp.security.encryption.key` from `${ERP_ENCRYPTION_KEY}` with no default.
  3. `ERP_ENCRYPTION_KEY` is not set in that flow, so Spring fails placeholder resolution and the app does not start under `dev` profile.
- Why current tests/checks would miss it:
  - The remediation validation is unit-level (`DataInitializerSecurityTest`, `DataInitializerTest`, `JwtPropertiesSecurityTest`) and never boots a `dev` profile context against the env-var contract.
  - No config-contract test/guard checks variable-name consistency between profile YAML and runtime artifacts (`docker-compose`/ops runbooks).
- Minimal remediation guidance:
  - Align `application-dev.yml` to the canonical env var used by runtime artifacts (or temporarily dual-read for compatibility).
  - Example compatibility pattern: `erp.security.encryption.key: ${ERP_SECURITY_ENCRYPTION_KEY:${ERP_ENCRYPTION_KEY:}}`.
  - Add a regression test/guard that boots with `dev` profile and only canonical key set, and separately asserts fail-closed behavior when neither key is provided.
  - Missing regression tests to add for this behavioral fix: profile-level startup/config-binding coverage for `dev` with canonical encryption key and without any key.

## Residual Risks / Testing Gaps

- `benchmark` config hardening changed to required env key, but there is still no startup-level regression test proving fail-closed behavior when the key is absent and successful boot when present.
- JWT fallback hardening is currently covered by unit tests only; there is no profile integration startup test that demonstrates non-test profile boot failure when `JWT_SECRET` is missing.
- Integration/merge quality check on head: no conflict markers or dropped-hunk artifacts were observed in the reviewed patch.
