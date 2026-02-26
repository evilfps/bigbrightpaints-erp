# Task Packet

Ticket: `TKT-ERP-STAGE-113`
Slice: `SLICE-06`
Primary Agent: `orchestrator`
Reviewers: `code_reviewer, merge-specialist, qa-reliability, release-ops, repo-cartographer, security-governance`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-113/orchestrator`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/orchestrator`

## Ticket Context
- title: Production blocker remediation wave from TKT-ERP-STAGE-112
- goal: Implement and verify all critical/high production blockers from TKT-ERP-STAGE-112 with isolated blocker scopes, integration-safe merges, and release-grade QA evidence

## Problem Statement
Implement ticket objective for scoped ownership paths: scripts/

## Task To Solve
- Implement ticket goal within explicit scope paths: scripts/
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Implement and verify all critical/high production blockers from TKT-ERP-STAGE-112 with isolated blocker scopes, integration-safe merges, and release-grade QA evidence

## Custom Multi-Agent Role (Codex)
- role: `orchestrator`
- config_file: `.codex/agents/orchestrator.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `docs/`
- `agents/`
- `skills/`
- `ci/`
- `tickets/`
- `asyncloop`
- `scripts/harness_orchestrator.py`

## Requested Focus Paths
- `scripts/`

## Ticket-First Gate (Blocking)
- Assigned branch: `tickets/tkt-erp-stage-113/orchestrator`
- Assigned worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/orchestrator`
- Base branches are read-only for implementation: `harness-engineering-orchestrator`, `main`, `master`, `origin/harness-engineering-orchestrator`
- Claim evidence must be recorded in `ticket.yaml` and `TIMELINE.md` before edits.
- If any gate fails, stop and report blocker instead of patching.
- Mandatory orchestrator delegation sequence: `planning -> implementation -> merge_specialist -> code_reviewer -> qa_reliability -> release_ops`.

## Acceptance Criteria
- Changes are limited to declared scope paths for orchestrator.
- Deliver objective for: scripts/.
- Targeted tests for changed behavior are added/updated and passing.

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`
- `bash ci/check-architecture.sh`
- `bash ci/check-enterprise-policy.sh`

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
- First output line must be: `I am orchestrator and I own SLICE-06.`

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
You are `orchestrator`.
Start your first line with: `I am orchestrator and I own SLICE-06.`
Use Codex custom multi-agent role `orchestrator` from `.codex/agents/orchestrator.toml`.
Ticket title: Production blocker remediation wave from TKT-ERP-STAGE-112
Problem statement: Implement ticket objective for scoped ownership paths: scripts/
Task to solve: Implement ticket goal within explicit scope paths: scripts/
Expected outcome: Required checks pass and acceptance criteria are satisfied.
Implement this slice with minimal safe patching and proof-backed output.

Execution minimum:
- validate current branch is `tickets/tkt-erp-stage-113/orchestrator` and working directory is `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/orchestrator`
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
