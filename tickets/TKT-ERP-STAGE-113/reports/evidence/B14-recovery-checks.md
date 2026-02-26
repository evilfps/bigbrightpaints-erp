# B14 Recovery Checks

Date: 2026-02-26

## Recovery Setup

- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B14-verifylocal-bash32-portability-recovery`
- Branch: `tickets/tkt-erp-stage-113/b14-verifylocal-bash32-portability-recovery`
- Base before cherry-pick: `63b3daaf`
- Cherry-pick command: `git cherry-pick 7aa45ecca0749c9377688c82792edeef24bda68e`
- Cherry-pick result: success, new commit `ce506c89` (`6 files changed, 49 insertions(+), 1 deletion(-)`)
- Conflict resolution: none required

## Blocker Checks

1. `bash scripts/guard_flyway_v2_migration_ownership.sh`
   - Exit: `0`
   - Evidence: `[guard_flyway_v2_migration_ownership] OK`
2. `VERIFY_LOCAL_SKIP_TESTS=true bash scripts/verify_local.sh`
   - Exit: `0`
   - Duration: `13s`
   - Evidence:
     - `[guard_flyway_v2_migration_ownership] OK`
     - `[guard_flyway_v2_migration_ownership_fixture_matrix] OK`
     - `[guard_flyway_v2_referential_contract] OK: checked 157 FK references against 138 PK/UNIQUE targets`
     - `[guard_flyway_v2_referential_contract_fixture_matrix] OK`
     - `[guard_flyway_guard_contract] OK`
     - Maven verify (tests skipped) ended with `BUILD SUCCESS`
     - `[verify_local] OK`
3. `bash ci/check-architecture.sh`
   - Exit: `0`
   - Duration: `3s`
   - Evidence:
     - `[allowlist-evidence] OK: allowlist not changed (range mode)`
     - `[architecture-check] OK`
4. `bash ci/check-enterprise-policy.sh`
   - Exit: `0`
   - Duration: `1s`
   - Evidence:
     - `[guard_workflow_canonical_paths] OK`
     - `[enterprise-policy] high-risk paths changed; enforcing R2 enterprise controls`
     - `[enterprise-policy] OK`
