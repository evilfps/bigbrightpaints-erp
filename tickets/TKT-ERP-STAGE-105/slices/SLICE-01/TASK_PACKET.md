# Task Packet

Ticket: `TKT-ERP-STAGE-105`
Slice: `SLICE-01`
Primary Agent: `orchestrator`
Reviewers: `qa-reliability, repo-cartographer, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-105/orchestrator`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-105/orchestrator`

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
- `scripts`

## Acceptance Criteria
- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`
- `bash ci/check-architecture.sh`
- `bash ci/check-enterprise-policy.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am orchestrator and I own SLICE-01.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `orchestrator`.
Start your first line with: `I am orchestrator and I own SLICE-01.`
Use Codex custom multi-agent role `orchestrator` from `.codex/agents/orchestrator.toml`.
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
