# Task Packet

Ticket: `TKT-ERP-STAGE-106`
Slice: `SLICE-02`
Primary Agent: `repo-cartographer`
Reviewers: `orchestrator`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-106/repo-cartographer`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-106/repo-cartographer`

## Ticket Context
- title: R3 Go-No-Go Checkpoint Package
- goal: Produce a deterministic human R3 checkpoint package for release-candidate SHA with explicit go/no-go decision fields and immutable evidence links.

## Problem Statement
Produce a deterministic human R3 checkpoint package for release-candidate SHA with explicit go/no-go decision fields and immutable evidence links.

## Task To Solve
- Produce a deterministic human R3 checkpoint package for release-candidate SHA with explicit go/no-go decision fields and immutable evidence links.
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Produce a deterministic human R3 checkpoint package for release-candidate SHA with explicit go/no-go decision fields and immutable evidence links.

## Custom Multi-Agent Role (Codex)
- role: `planning_architecture`
- config_file: `.codex/agents/planning_architecture.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `docs/`
- `ARCHITECTURE.md`
- `AGENTS.md`

## Requested Focus Paths
- `docs/CODE-RED`
- `docs/approvals`
- `docs/system-map`

## Acceptance Criteria
- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am repo-cartographer and I own SLICE-02.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `repo-cartographer`.
Start your first line with: `I am repo-cartographer and I own SLICE-02.`
Use Codex custom multi-agent role `planning_architecture` from `.codex/agents/planning_architecture.toml`.
Ticket title: R3 Go-No-Go Checkpoint Package
Problem statement: Produce a deterministic human R3 checkpoint package for release-candidate SHA with explicit go/no-go decision fields and immutable evidence links.
Task to solve: Produce a deterministic human R3 checkpoint package for release-candidate SHA with explicit go/no-go decision fields and immutable evidence links.
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
