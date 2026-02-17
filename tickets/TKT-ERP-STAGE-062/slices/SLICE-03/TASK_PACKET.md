# Task Packet

Ticket: `TKT-ERP-STAGE-062`
Slice: `SLICE-03`
Primary Agent: `sales-domain`
Reviewers: `qa-reliability, security-governance`
Lane: `w3`
Branch: `tickets/tkt-erp-stage-062/sales-domain`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec_worktrees/TKT-ERP-STAGE-062/sales-domain`

## Objective
Simplify messy backend workflows with deterministic reason-coded fail-closed behavior

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/o2c/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/CreditLimitOverrideController.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: SLICE-01
- External upstream agents to watch: auth-rbac-company, orchestrator-runtime
- External downstream agents to watch: inventory-domain
- Contract edges:
  - downstream -> accounting-domain (slice SLICE-01): o2c posting and receivable linkage
  - downstream-external -> inventory-domain: dispatch and stock movement linkage
  - upstream-external -> auth-rbac-company: tenant and role boundary contract
  - upstream-external -> orchestrator-runtime: async orchestration command contract

## Required Checks Before Done
- `cd erp-domain && mvn -B -ntp -Dtest='*Sales*' test`
- `bash ci/check-architecture.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `sales-domain`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity line: `I am sales-domain and I own SLICE-03.`
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
