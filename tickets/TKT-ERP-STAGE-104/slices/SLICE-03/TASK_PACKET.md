# Task Packet

Ticket: `TKT-ERP-STAGE-104`
Slice: `SLICE-03`
Primary Agent: `repo-cartographer`
Reviewers: `orchestrator`
Lane: `w3`
Branch: `tickets/tkt-erp-stage-104/repo-cartographer`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-104/repo-cartographer`

## Objective
Re-run all completion gates on canonical head after coverage closure and update immutable evidence for staging sign-off.

## Custom Multi-Agent Role (Codex)
- role: `planning_architecture`
- config_file: `.codex/agents/planning_architecture.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `docs/`
- `ARCHITECTURE.md`
- `AGENTS.md`

## Requested Focus Paths
- `docs/system-map/COMPLETION_GATES_STATUS.md`
- `docs/system-map/LIVE_EXECUTION_PLAN.md`

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
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
