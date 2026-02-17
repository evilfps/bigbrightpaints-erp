# Task Packet

Ticket: `TKT-ERP-STAGE-062`
Slice: `SLICE-01`
Primary Agent: `accounting-domain`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-062/accounting-domain`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec_worktrees/TKT-ERP-STAGE-062/accounting-domain`

## Objective
Simplify messy backend workflows with deterministic reason-coded fail-closed behavior

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/accounting/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/`

## Cross-Workflow Dependencies
- Upstream slices: SLICE-02, SLICE-03
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, factory-production, hr-domain, orchestrator-runtime
- External downstream agents to watch: none
- Contract edges:
  - upstream -> purchasing-invoice-p2p (slice SLICE-02): ap/posting and settlement linkage
  - upstream -> sales-domain (slice SLICE-03): o2c posting and receivable linkage
  - upstream-external -> auth-rbac-company: finance/admin authority boundaries
  - upstream-external -> factory-production: wip/variance/cogs posting linkage
  - upstream-external -> hr-domain: payroll liability/payment posting linkage
  - upstream-external -> orchestrator-runtime: outbox/idempotency posting orchestration

## Required Checks Before Done
- `cd erp-domain && mvn -B -ntp -Dtest='*Accounting*' test`
- `bash scripts/verify_local.sh`

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
You are `accounting-domain`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity line: `I am accounting-domain and I own SLICE-01.`
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
