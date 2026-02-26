# Task Packet

Ticket: `TKT-ERP-STAGE-113`
Slice: `SLICE-01`
Primary Agent: `accounting-domain`
Reviewers: `code_reviewer, merge-specialist, qa-reliability, release-ops, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-113/accounting-domain`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/accounting-domain`

## Ticket Context
- title: Production blocker remediation wave from TKT-ERP-STAGE-112
- goal: Implement and verify all critical/high production blockers from TKT-ERP-STAGE-112 with isolated blocker scopes, integration-safe merges, and release-grade QA evidence

## Problem Statement
Implement ticket objective for scoped ownership paths: erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/

## Task To Solve
- Implement ticket goal within explicit scope paths: erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Implement and verify all critical/high production blockers from TKT-ERP-STAGE-112 with isolated blocker scopes, integration-safe merges, and release-grade QA evidence

## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/accounting/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`

## Ticket-First Gate (Blocking)
- Assigned branch: `tickets/tkt-erp-stage-113/accounting-domain`
- Assigned worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/accounting-domain`
- Base branches are read-only for implementation: `harness-engineering-orchestrator`, `main`, `master`, `origin/harness-engineering-orchestrator`
- Claim evidence must be recorded in `ticket.yaml` and `TIMELINE.md` before edits.
- If any gate fails, stop and report blocker instead of patching.
- Mandatory orchestrator delegation sequence: `planning -> implementation -> merge_specialist -> code_reviewer -> qa_reliability -> release_ops`.

## Acceptance Criteria
- Changes are limited to declared scope paths for accounting-domain.
- Deliver objective for: erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/.
- Targeted tests for changed behavior are added/updated and passing.

## Cross-Workflow Dependencies
- Upstream slices: SLICE-02, SLICE-03, SLICE-04, SLICE-07, SLICE-09
- Downstream slices: none
- External upstream agents to watch: factory-production
- External downstream agents to watch: none
- Contract edges:
  - upstream -> auth-rbac-company (slice SLICE-02): finance/admin authority boundaries
  - upstream -> hr-domain (slice SLICE-03): payroll liability/payment posting linkage
  - upstream -> orchestrator-runtime (slice SLICE-04): outbox/idempotency posting orchestration
  - upstream -> purchasing-invoice-p2p (slice SLICE-07): ap/posting and settlement linkage
  - upstream -> sales-domain (slice SLICE-09): o2c posting and receivable linkage
  - upstream-external -> factory-production: wip/variance/cogs posting linkage

## Required Checks Before Done
- `cd erp-domain && mvn -B -ntp -Dtest='*Accounting*' test`
- `bash scripts/verify_local.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Testing Responsibility Split
- Implementation agents own targeted tests for changed behavior in-slice.
- `merge-specialist` owns integration merge/conflict evidence before code-review phase.
- `qa-reliability` owns cross-workflow regression, gate evidence, and release-readiness signal.
- `release-ops` owns docs/release evidence sync before final merge.

## Agent Identity Contract
- First output line must be: `I am accounting-domain and I own SLICE-01.`

## Required Output Contract
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
- ticket_claim_evidence
- worktree_validation
- codebase_impact_analysis:
  - upstream dependencies/contracts touched
  - downstream modules/portals at risk
  - API/event/schema/test surface changed or intentionally unchanged

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `accounting-domain`.
Start your first line with: `I am accounting-domain and I own SLICE-01.`
Use Codex custom multi-agent role `cross_module` from `.codex/agents/cross_module.toml`.
Ticket title: Production blocker remediation wave from TKT-ERP-STAGE-112
Problem statement: Implement ticket objective for scoped ownership paths: erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/
Task to solve: Implement ticket goal within explicit scope paths: erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/
Expected outcome: Required checks pass and acceptance criteria are satisfied.
Implement this slice with minimal safe patching and proof-backed output.

Execution minimum:
- validate current branch is `tickets/tkt-erp-stage-113/accounting-domain` and working directory is `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/accounting-domain`
- treat base branches as read-only for implementation: `harness-engineering-orchestrator`, `main`, `master`, `origin/harness-engineering-orchestrator`
- confirm claim evidence exists in ticket.yaml + TIMELINE.md before edits
- diagnose current behavior in the requested focus paths
- perform codebase impact analysis (upstream dependencies, downstream consumers, contracts/events/APIs)
- implement the root-cause fix in allowed scope
- add/adjust tests that prove acceptance criteria
- run required checks and report evidence

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
- ticket_claim_evidence
- worktree_validation
- codebase_impact_analysis
```
