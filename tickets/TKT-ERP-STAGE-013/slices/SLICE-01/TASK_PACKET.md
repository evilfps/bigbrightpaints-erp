# Task Packet

Ticket: `TKT-ERP-STAGE-013`
Slice: `SLICE-01`
Primary Agent: `accounting-domain`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-013/accounting-domain`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-013/accounting-domain`

## Objective
M18-S5B: harden settlement idempotency replay/race diagnostics and deterministic conflict handling

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/accounting/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, factory-production, hr-domain, orchestrator-runtime, purchasing-invoice-p2p, sales-domain
- External downstream agents to watch: none
- Contract edges:
  - upstream-external -> auth-rbac-company: finance/admin authority boundaries
  - upstream-external -> factory-production: wip/variance/cogs posting linkage
  - upstream-external -> hr-domain: payroll liability/payment posting linkage
  - upstream-external -> orchestrator-runtime: outbox/idempotency posting orchestration
  - upstream-external -> purchasing-invoice-p2p: ap/posting and settlement linkage
  - upstream-external -> sales-domain: o2c posting and receivable linkage

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
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
