# B08 Recovery Security Review 1

- Ticket: `TKT-ERP-STAGE-113`
- Blocker: `B08 auth-secret-hardening (recovery)`
- Branch: `tickets/tkt-erp-stage-113/b08-auth-secret-hardening-recovery`
- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B08-auth-secret-hardening-recovery`
- Review date (UTC): `2026-02-26`
- Reviewer focus: secret/credential exposure, fail-closed bootstrap, predictable defaults, config leakage, and unsafe non-test fallbacks

## Findings (Severity-Ordered)

### 1) [P2][high] JWT bootstrap still allows secretless startup in non-test profiles

- Why this matters:
  - `JwtProperties` now generates an ephemeral JWT secret when `jwt.secret` is missing for profiles in `dev/mock/openapi/benchmark`.
  - This is not a predictable static secret, but it is still a runtime fallback in non-test profiles and can mask secret misconfiguration instead of failing closed.
  - Governance impact: non-test deployments can start without explicit secret provisioning, violating strict "environment-backed secret + fail-closed" expectations.
- Anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:25`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:43`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:45`
  - `erp-domain/src/main/resources/application-dev.yml:28`
  - `erp-domain/src/main/resources/application-mock.yml:2`
  - `erp-domain/src/main/resources/application-openapi.yml:29`
  - `erp-domain/src/main/resources/application-benchmark.yml:25`
- Minimal remediation plan:
  - Restrict missing-secret fallback to `test` only, or gate non-test fallback behind an explicit opt-in property (default `false`) that is not enabled in committed non-test profiles.
  - Keep non-test profile defaults as required env-backed secret sources (no implicit fallback secret generation).
- Required verification steps:
  1. Add/adjust unit tests so `validate()` fails for missing `jwt.secret` in `dev`, `mock`, `openapi`, and `benchmark` unless explicit opt-in fallback flag is set.
  2. Run `DataInitializerSecurityTest` and `JwtPropertiesSecurityTest` plus targeted startup tests for each non-test profile.
  3. Capture evidence that startup fails (non-zero) when `JWT_SECRET` is absent in non-test profiles under default settings.

### 2) [P2][high] Predictable encryption-key defaults remain in non-test profile configs

- Why this matters:
  - `erp.security.encryption.key` still has static, repo-visible default values in committed non-test profiles.
  - If these profiles are run beyond local-only contexts, encrypted-at-rest data becomes decryptable with known defaults.
- Anchors:
  - `erp-domain/src/main/resources/application-dev.yml:34`
  - `erp-domain/src/main/resources/application-benchmark.yml:38`
- Minimal remediation plan:
  - Remove static defaults and require environment-provided key material for non-test profiles.
  - If local convenience is required, provide local-only opt-in override outside committed shared profile defaults.
- Required verification steps:
  1. Start app in `dev` and `benchmark` profiles without `ERP_SECURITY_ENCRYPTION_KEY`; confirm fail-closed startup/health behavior.
  2. Start with strong env-provided keys; confirm normal encryption/decryption paths still pass tests.
  3. Add a config policy check (or test) that rejects known/default encryption key literals in non-test profiles.

### 3) [P3][medium] Existing-account dev bootstrap can grant admin role without password challenge

- Why this matters:
  - In `dev/seed` bootstrap flow, if `erp.seed.dev-admin.email` points to an existing account, the code grants/ensures `ROLE_ADMIN` and company membership even when password input is blank.
  - This is a privileged mutation path that does not require password-based confirmation for existing users.
- Anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:35`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:109`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:120`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:122`
- Minimal remediation plan:
  - Require explicit promote-existing opt-in (default `false`) before granting admin role to an existing account, or require a non-empty bootstrap password for both new and existing account promotion paths.
  - Emit structured audit record when any startup path elevates an existing user’s role.
- Required verification steps:
  1. Add tests showing existing-user promotion is blocked by default when password is blank.
  2. Add tests showing promotion only succeeds when explicit opt-in (or explicit password requirement) is satisfied.
  3. Verify audit/event evidence is emitted for successful existing-user privilege elevation.

## Residual Risks

- B08 removed hardcoded demo users/passwords and replaced static JWT defaults, but non-test secret/key governance is still partially policy-driven rather than strictly fail-closed by default.
- Recovery evidence currently shows focused B08 tests and policy checks; full cross-profile runtime validation for secret/key absence scenarios is still needed to close the above findings.
