# B08 Code Review Rerun 3

## Context
- Ticket: `TKT-ERP-STAGE-113`
- Blocker: `B08-auth-secret-hardening`
- Branch: `tickets/tkt-erp-stage-113/b08-auth-secret-hardening`
- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B08-auth-secret-hardening`
- Review target: verify closure of rerun-1 findings after rerun-2 remediation, then rerun required validation stack.
- Reviewed HEAD: `2b679312`

## Findings (severity-ordered)

### 1) [P2][high] Changed-files coverage gate still fails required thresholds (non-vacuous but insufficient)
- Impacted file(s):
  - `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-3.log:7`
  - `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-3.log:11`
  - `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-3.log:24`
  - `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-3.log:37`
  - `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-3.log:59`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerSecurityTest.java:39`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerTest.java:312`
- Failure/regression scenario:
  - The coverage command exits non-zero with `passes: false` (`line_ratio: 0.5895`, `branch_ratio: 0.4833`), so the branch is not gate-fast ready despite non-vacuous evidence.
  - `DataInitializer.java` changed lines remain unexecuted in this validation run (`line_covered: 0/17`, `branch_covered: 0/6`), leaving fail-closed bootstrap logic vulnerable to future regressions without coverage alarm.
- Why current tests/checks miss it:
  - The mandated rerun command `mvn -Dtest='*Auth*,*Security*' test` includes `DataInitializerSecurityTest` but excludes `DataInitializerTest` where the `seedDefaultUser_requiresPasswordWhenSeedingNewDevAdmin` and super-admin fail-closed contract assertions exist.
  - `ci/check-architecture.sh`, `ci/check-enterprise-policy.sh`, and `ci/check-orchestrator-layer.sh` validate policy/boundary contracts, not changed-line execution coverage.
- Minimal remediation guidance:
  - Move or duplicate `DataInitializer` fail-closed contract coverage into a `*Security*`-matched test class (for example, extend `DataInitializerSecurityTest` to cover `seedDefaultUser` new-user and existing-user branches).
  - Add focused `JwtProperties` branch tests for currently unmapped changed lines reported in rerun-3 coverage.
  - Re-run `python3 scripts/changed_files_coverage.py --diff-base origin/harness-engineering-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml` and require `passes: true` before merge.
- Missing regression tests for this fix:
  - `DataInitializerSecurityTest` coverage for `seedConfiguredDevAdmin` and `seedConfiguredSuperAdmin` new-user + existing-user paths under the mandated `*Security*` suite.
  - Additional `JwtPropertiesSecurityTest` cases to exercise remaining unmapped placeholder-detection branches.

## Prior finding closure verification

### 1) Reject placeholder JWT secrets outside test
- Status: **Closed**
- Evidence:
  - Non-test placeholder rejection enforced: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:58` and `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:64`.
  - Test-only allowance retained: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/JwtProperties.java:59`.
  - Regression tests present:
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/security/JwtPropertiesSecurityTest.java:59`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/security/JwtPropertiesSecurityTest.java:72`

### 2) Fail-closed seed bootstrap when password missing
- Status: **Closed**
- Evidence:
  - Dev initializer fail-closed guard: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:110`
  - Super-admin fail-closed guard: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:141`
  - Mock initializer fail-closed guard: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/MockDataInitializer.java:205`
  - Benchmark initializer fail-closed guard: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/BenchmarkDataInitializer.java:246`
  - Regression tests:
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerSecurityTest.java:39`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerSecurityTest.java:63`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerTest.java:209`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerTest.java:312`

### 3) Non-vacuous changed-files coverage evidence
- Status: **Closed (vacuous-mode issue resolved)**
- Evidence:
  - `files_considered: 4`, `vacuous: false`: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-3.log:4` and `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-3.log:13`.

## Validation rerun evidence (requested commands)
1. `cd erp-domain && mvn -B -ntp -Dtest='*Auth*,*Security*' test`
   - Result: `BUILD SUCCESS`
   - Summary: `Tests run: 108, Failures: 0, Errors: 0, Skipped: 0`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/01-mvn-auth-security-rerun-3.log:3340` and `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/01-mvn-auth-security-rerun-3.log:3347`
2. `bash ci/check-architecture.sh`
   - Result: `[architecture-check] OK`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/02-check-architecture-rerun-3.log:3`
3. `bash ci/check-enterprise-policy.sh`
   - Result: `[enterprise-policy] OK`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/03-check-enterprise-policy-rerun-3.log:4`
4. `bash ci/check-orchestrator-layer.sh`
   - Result: `[orchestrator-layer] OK`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/04-check-orchestrator-layer-rerun-3.log:1`
5. `python3 scripts/changed_files_coverage.py --diff-base origin/harness-engineering-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml`
   - Result: command failed threshold gate (`exit 1`) with non-vacuous output
   - Coverage summary:
     - `files_considered: 4`
     - `vacuous: false`
     - `line_ratio: 0.5895` (`56/95`) vs threshold `0.95`
     - `branch_ratio: 0.4833` (`29/60`) vs threshold `0.9`
     - `passes: false`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-3.log:4`, `:13`, `:7`, `:11`, `:24`

## Integration / merge quality check
- No post-remediation runtime code drift detected after `d7a52780`; `d7a52780..HEAD` adds only review/timeline artifacts.
- No conflict markers or dropped-hunk evidence observed in inspected remediation files.

## Review verdict
- Prior functional/security findings are closed for placeholder JWT enforcement, fail-closed seed bootstrap semantics, and non-vacuous coverage evidence.
- **Branch remains not merge-ready** due unresolved changed-files coverage threshold failure (Finding 1).

## Rerun-3 Finding Closure Response (Rerun-4)
- Date: `2026-02-26`
- Targeted closure: Finding 1 (changed-files coverage threshold failure)

### Remediation applied for closure
- Added `*Security*`-matched fail-closed coverage for `DataInitializer` dev-admin bootstrap branches and expanded mock/benchmark admin branch coverage:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerSecurityTest.java`
- Added targeted `JwtProperties` placeholder/fallback/default-profile branch tests under the same required selector:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/security/JwtPropertiesSecurityTest.java`

### Validation rerun-4 evidence (requested command stack)
1. `cd erp-domain && mvn -B -ntp -Dtest='*Auth*,*Security*' test`
   - Result: `BUILD SUCCESS`
   - Summary: `Tests run: 124, Failures: 0, Errors: 0, Skipped: 0`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/01-mvn-auth-security-rerun-4.log`
2. `bash ci/check-architecture.sh`
   - Result: `[architecture-check] OK`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/02-check-architecture-rerun-4.log`
3. `bash ci/check-enterprise-policy.sh`
   - Result: `[enterprise-policy] OK`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/03-check-enterprise-policy-rerun-4.log`
4. `bash ci/check-orchestrator-layer.sh`
   - Result: `[orchestrator-layer] OK`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/04-check-orchestrator-layer-rerun-4.log`
5. `python3 scripts/changed_files_coverage.py --diff-base origin/harness-engineering-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml`
   - Result: `passes: true` (`exit 0`)
   - Final coverage ratios:
     - `line_ratio: 0.9578947368421052` (`91/95`)
     - `branch_ratio: 0.9` (`54/60`)
     - `files_considered: 4`
     - `vacuous: false`
   - Evidence: `tickets/TKT-ERP-STAGE-113/reports/evidence/B08/05-changed-files-coverage-rerun-4.log`

### Closure decision update
- Finding 1 status: **Closed**
- B08 changed-files coverage gate now meets required thresholds and no longer blocks this branch.
