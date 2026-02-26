# Task Packet

Ticket: `TKT-ERP-STAGE-113`
Slice: `SLICE-04`
Primary Agent: `orchestrator-runtime`
Reviewers: `code_reviewer, merge-specialist, qa-reliability, release-ops, security-governance`
Lane: `w4`
Branch: `tickets/tkt-erp-stage-113/orchestrator-runtime`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/orchestrator-runtime`

## Ticket Context
- title: Production blocker remediation wave from TKT-ERP-STAGE-112
- goal: Implement and verify all critical/high production blockers from TKT-ERP-STAGE-112 with isolated blocker scopes, integration-safe merges, and release-grade QA evidence

## Problem Statement
Implement ticket objective for scoped ownership paths: erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/

## Task To Solve
- Implement ticket goal within explicit scope paths: erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Implement and verify all critical/high production blockers from TKT-ERP-STAGE-112 with isolated blocker scopes, integration-safe merges, and release-grade QA evidence

## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/orchestrator/`
- `scripts/guard_orchestrator_correlation_contract.sh`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/`

## Ticket-First Gate (Blocking)
- Assigned branch: `tickets/tkt-erp-stage-113/orchestrator-runtime`
- Assigned worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/orchestrator-runtime`
- Base branches are read-only for implementation: `harness-engineering-orchestrator`, `main`, `master`, `origin/harness-engineering-orchestrator`
- Claim evidence must be recorded in `ticket.yaml` and `TIMELINE.md` before edits.
- If any gate fails, stop and report blocker instead of patching.
- Mandatory orchestrator delegation sequence: `planning -> implementation -> merge_specialist -> code_reviewer -> qa_reliability -> release_ops`.

## Acceptance Criteria
- Changes are limited to declared scope paths for orchestrator-runtime.
- Deliver objective for: erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/.
- Targeted tests for changed behavior are added/updated and passing.

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: SLICE-01, SLICE-05, SLICE-09
- External upstream agents to watch: none
- External downstream agents to watch: none
- Contract edges:
  - downstream -> accounting-domain (slice SLICE-01): outbox/idempotency posting orchestration
  - downstream -> inventory-domain (slice SLICE-05): exactly-once side-effect orchestration
  - downstream -> sales-domain (slice SLICE-09): async orchestration command contract

## Required Checks Before Done
- `bash ci/check-architecture.sh`
- `bash scripts/guard_orchestrator_correlation_contract.sh`

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
- First output line must be: `I am orchestrator-runtime and I own SLICE-04.`

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
You are `orchestrator-runtime`.
Start your first line with: `I am orchestrator-runtime and I own SLICE-04.`
Use Codex custom multi-agent role `cross_module` from `.codex/agents/cross_module.toml`.
Ticket title: Production blocker remediation wave from TKT-ERP-STAGE-112
Problem statement: Implement ticket objective for scoped ownership paths: erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/
Task to solve: Implement ticket goal within explicit scope paths: erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/
Expected outcome: Required checks pass and acceptance criteria are satisfied.
Implement this slice with minimal safe patching and proof-backed output.

Execution minimum:
- validate current branch is `tickets/tkt-erp-stage-113/orchestrator-runtime` and working directory is `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/orchestrator-runtime`
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
