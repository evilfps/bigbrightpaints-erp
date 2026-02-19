# Task Packet

Ticket: `TKT-ERP-STAGE-093`
Slice: `SLICE-02`
Primary Agent: `repo-cartographer`
Reviewers: `orchestrator`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-093/repo-cartographer`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-093/repo-cartographer`

## Objective
Execute full Section 14.3 ledger gate closure on one canonical SHA with fixed release anchor and immutable evidence capture

## Custom Multi-Agent Role (Codex)
- role: `planning_architecture`
- config_file: `.codex/agents/planning_architecture.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `docs/`
- `ARCHITECTURE.md`
- `AGENTS.md`

## Requested Focus Paths
- `docs/ASYNC_LOOP_OPERATIONS.md`
- `docs/system-map/Goal/ERP_STAGING_MASTER_PLAN.md`

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
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
