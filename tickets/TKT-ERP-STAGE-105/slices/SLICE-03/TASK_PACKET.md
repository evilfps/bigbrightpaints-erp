# Task Packet

Ticket: `TKT-ERP-STAGE-105`
Slice: `SLICE-03`
Primary Agent: `repo-cartographer`
Reviewers: `orchestrator`
Lane: `w3`
Branch: `tickets/tkt-erp-stage-105/repo-cartographer`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-105/repo-cartographer`

## Ticket Context
- title: Release-Cut Evidence Freeze and Sign-off Bundle
- goal: Freeze immutable gate evidence on canonical release-candidate SHA and produce final deploy sign-off bundle with rollback rehearsal linkage.

## Problem Statement
Freeze immutable gate evidence on canonical release-candidate SHA and produce final deploy sign-off bundle with rollback rehearsal linkage.

## Task To Solve
- Freeze immutable gate evidence on canonical release-candidate SHA and produce final deploy sign-off bundle with rollback rehearsal linkage.
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Freeze immutable gate evidence on canonical release-candidate SHA and produce final deploy sign-off bundle with rollback rehearsal linkage.

## Custom Multi-Agent Role (Codex)
- role: `planning_architecture`
- config_file: `.codex/agents/planning_architecture.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `docs/`
- `ARCHITECTURE.md`
- `AGENTS.md`

## Requested Focus Paths
- `docs/CODE-RED/GO_NO_GO_CHECKLIST.md`
- `docs/system-map/COMPLETION_GATES_STATUS.md`
- `docs/system-map/LIVE_EXECUTION_PLAN.md`

## Acceptance Criteria
- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am repo-cartographer and I own SLICE-03.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `repo-cartographer`.
Start your first line with: `I am repo-cartographer and I own SLICE-03.`
Use Codex custom multi-agent role `planning_architecture` from `.codex/agents/planning_architecture.toml`.
Ticket title: Release-Cut Evidence Freeze and Sign-off Bundle
Problem statement: Freeze immutable gate evidence on canonical release-candidate SHA and produce final deploy sign-off bundle with rollback rehearsal linkage.
Task to solve: Freeze immutable gate evidence on canonical release-candidate SHA and produce final deploy sign-off bundle with rollback rehearsal linkage.
Expected outcome: Required checks pass and acceptance criteria are satisfied.
Implement this slice with minimal safe patching and proof-backed output.

Execution minimum:
- diagnose current behavior in the requested focus paths
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
```
