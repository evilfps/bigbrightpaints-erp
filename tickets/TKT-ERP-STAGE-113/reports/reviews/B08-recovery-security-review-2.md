# B08 Recovery Security Review 2

- Ticket: `TKT-ERP-STAGE-113`
- Blocker: `B08 auth-secret-hardening (recovery)`
- Branch: `tickets/tkt-erp-stage-113/b08-auth-secret-hardening-recovery`
- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B08-auth-secret-hardening-recovery`
- Review timestamp (UTC): `2026-02-26T15:19:37Z`
- Reviewed HEAD: `3ee922ea4f2f5f39cd19542ab86d59a3b3136a75`
- Head ancestry check: `git merge-base --is-ancestor 3ee922ea4f2f5f39cd19542ab86d59a3b3136a75 HEAD` -> exit `0`

## Prior Finding Closure Verification

### 1) non-test JWT secretless fallback
- Status: `Closed`
- Evidence anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:42` (missing secret path)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:43` (ephemeral fallback restricted to test-only runtime)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:49` (non-test missing secret throws fail-closed)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/security/JwtPropertiesSecurityTest.java:16` (parameterized profiles include `dev/mock/openapi/benchmark`)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/security/JwtPropertiesSecurityTest.java:21` (asserts missing secret throws)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/security/JwtPropertiesSecurityTest.java:49` (test profile still allows ephemeral secret generation)
  - `erp-domain/src/main/resources/application-dev.yml:28`, `application-mock.yml:2`, `application-openapi.yml:29`, `application-benchmark.yml:25` (all rely on `JWT_SECRET`; no non-test in-code fallback remains)

### 2) predictable encryption defaults in configs
- Status: `Closed`
- Evidence anchors:
  - `erp-domain/src/main/resources/application-dev.yml:34` (`erp.security.encryption.key: ${ERP_ENCRYPTION_KEY}`; static literal removed)
  - `erp-domain/src/main/resources/application-benchmark.yml:38` (`erp.security.encryption.key: ${ERP_SECURITY_ENCRYPTION_KEY}`; static literal removed)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CryptoService.java:144` (encryption operation fails when key not configured)

### 3) existing dev-admin privilege elevation without password challenge
- Status: `Closed`
- Evidence anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:121` (blank password for existing user now rejected)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:126` (existing-user password hash must match before elevation)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:133` (role membership grant occurs only after challenge checks)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerSecurityTest.java:111` (existing-user blank password rejected)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerSecurityTest.java:136` (wrong password rejected)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerSecurityTest.java:160` (matching password allows controlled reuse)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerTest.java:346` (runner-level check for blank-password rejection)

## Findings (Severity-Ordered)
No findings.

## Residual Risks / Testing Gaps
- Closure evidence is strong at unit-test level; there is still no dedicated Spring profile integration test proving process startup fails for `dev/mock/openapi/benchmark` when `JWT_SECRET` is absent.
- There is no end-to-end bootstrap integration test covering full login continuity after the new existing-dev-admin password challenge path.
- Existing-user `super-admin` bootstrap path (`DataInitializer.seedConfiguredSuperAdmin`) still promotes by configured email without an explicit password challenge; this was not one of the three prior findings but remains a privileged startup mutation to monitor.

## Verification Commands and Outcomes
1. `git rev-parse --abbrev-ref HEAD` -> `tickets/tkt-erp-stage-113/b08-auth-secret-hardening-recovery`
2. `git rev-parse HEAD` -> `3ee922ea4f2f5f39cd19542ab86d59a3b3136a75`
3. `git merge-base --is-ancestor 3ee922ea4f2f5f39cd19542ab86d59a3b3136a75 HEAD` -> exit `0`
4. `cd erp-domain && mvn -B -ntp -Dtest=JwtPropertiesSecurityTest,DataInitializerSecurityTest,DataInitializerTest test` -> `BUILD SUCCESS` (`Tests run: 46, Failures: 0, Errors: 0, Skipped: 0`)
5. `bash ci/check-architecture.sh` -> `[architecture-check] OK`
6. `bash ci/check-enterprise-policy.sh` -> `[enterprise-policy] OK`
