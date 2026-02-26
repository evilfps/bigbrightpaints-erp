# B09 Recovery Checks

## Scope
- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B09-orchestrator-correlation-sanitization-recovery`
- Branch: `tickets/tkt-erp-stage-113/b09-orchestrator-correlation-sanitization-recovery`
- Base before remediation commit: `f67e9fc2d855c7cd5ebc792d5e28c73a19dc26ac`
- Remediation commit: `355ca643bc9dfbb1f82c5db9140caf95f2fb36ae`
- Resulting HEAD: `355ca643bc9dfbb1f82c5db9140caf95f2fb36ae`

## Command Outcomes
1. `bash scripts/guard_orchestrator_correlation_contract.sh`
- Exit: `0`
- Evidence: `[guard_orchestrator_correlation_contract] OK`

2. `cd erp-domain && mvn -B -ntp -Dtest='*Orchestrator*' test`
- Exit: `0`
- Evidence: `Tests run: 65, Failures: 0, Errors: 0, Skipped: 0`
- Evidence: `BUILD SUCCESS`

3. `bash ci/check-architecture.sh`
- Exit: `0`
- Evidence: `[architecture-check] OK`

4. `bash ci/check-enterprise-policy.sh`
- Exit: `0`
- Evidence: `[enterprise-policy] OK`

5. `python3 scripts/changed_files_coverage.py --diff-base tickets/tkt-erp-stage-113/blocker-remediation-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml`
- Exit: `0`
- Evidence: `passes=true`
- Evidence: `line_ratio=0.9672131147540983` (threshold `0.95`)
- Evidence: `branch_ratio=0.9285714285714286` (threshold `0.9`)
- Evidence: `files_considered=7`, `line_covered=118/122`, `branch_covered=52/56`
- Evidence: `per_file highlights:`
  - `OrchestratorController line_ratio=1.0 branch_ratio=1.0`
  - `CommandDispatcher line_ratio=1.0`
  - `CorrelationIdentifierSanitizer line_ratio=0.948051948051948 branch_ratio=0.9130434782608695`
  - `IntegrationCoordinator line_ratio=1.0 branch_ratio=1.0`
  - `OrchestratorIdempotencyService line_ratio=1.0 branch_ratio=1.0`
  - `TraceService line_ratio=1.0`

## Residual Risks
- `files_with_unmapped_lines` remains non-empty for several orchestrator files due non-instrumented/structural changed lines, while changed-files thresholds still pass.
- Malformed `orderId` values now fail-closed with `VAL_*` rejection semantics; any direct service callers previously relying on permissive fallbacks will now receive validation exceptions.
