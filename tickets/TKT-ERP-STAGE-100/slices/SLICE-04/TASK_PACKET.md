# Task Packet

Ticket: `TKT-ERP-STAGE-100`
Slice: `SLICE-04`
Primary Agent: `repo-cartographer`
Reviewers: `orchestrator`
Lane: `w4`
Branch: `tickets/tkt-erp-stage-100/repo-cartographer`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-100/repo-cartographer`

## Objective
Add truth-suite coverage for changed sales and purchasing approval paths so gate_fast reflects deterministic coverage.

## Custom Multi-Agent Role (Codex)
- role: `planning_architecture`
- config_file: `.codex/agents/planning_architecture.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `docs/`
- `ARCHITECTURE.md`
- `AGENTS.md`

## Requested Focus Paths
- `docs/CODE-RED/confidence-suite/TEST_CATALOG.json`

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am repo-cartographer and I own SLICE-04.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `repo-cartographer`.
Start your first line with: `I am repo-cartographer and I own SLICE-04.`
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
