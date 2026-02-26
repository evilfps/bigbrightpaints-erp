# B08 Recovery Checks

- Executed (UTC): `2026-02-26T15:39:17Z`
- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B08-auth-secret-hardening-recovery`
- Branch: `tickets/tkt-erp-stage-113/b08-auth-secret-hardening-recovery`
- Current HEAD: `a81f2f8045310e27d4248416884d2c47d1b52ba0`

## Required Gate Commands (Latest Head)

- `cd erp-domain && mvn -B -ntp -Dtest='DataInitializerSecurityTest,JwtPropertiesSecurityTest,DataInitializerTest' test`
  - Exit: `0`
  - Result: `BUILD SUCCESS`
  - Tests:
    - `DataInitializerSecurityTest`: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`
    - `DataInitializerTest`: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`
    - `JwtPropertiesSecurityTest`: `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`
    - Aggregate: `Tests run: 46, Failures: 0, Errors: 0, Skipped: 0`

- `bash ci/check-architecture.sh`
  - Exit: `0`
  - Result: `[architecture-check] OK`
  - Guard detail: `[allowlist-evidence] OK: allowlist not changed (range mode)`

- `bash ci/check-enterprise-policy.sh`
  - Exit: `0`
  - Result: `[enterprise-policy] OK`
  - Guard detail: `[guard_workflow_canonical_paths] OK`

- `python3 scripts/changed_files_coverage.py --diff-base tickets/tkt-erp-stage-113/blocker-remediation-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml`
  - Exit: `0`
  - Result: `passes=true`
  - Coverage ratios:
    - `files_considered=4`
    - `line_ratio=0.9680851063829787` (threshold `0.95`)
    - `branch_ratio=0.9166666666666666` (threshold `0.90`)
  - Diff base used: `tickets/tkt-erp-stage-113/blocker-remediation-orchestrator`
