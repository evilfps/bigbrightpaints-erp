# Task Packet

Ticket: `TKT-ERP-STAGE-006`
Slice: `SLICE-02`
Primary Agent: `repo-cartographer`
Reviewers: `orchestrator`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-006/repo-cartographer`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-006/repo-cartographer`

## Identity Contract
- Start your response exactly with: `I am repo-cartographer and I own SLICE-02.`

## Objective
M18-S3A docs lane - publish canonical write-path and duplicate-path keep/merge/deprecate decisions for O2C/P2P/production/payroll.

## YAML Contract
- `agents/repo-cartographer.agent.yaml`

## Agent Write Boundary (Enforced)
- `docs/`
- `ARCHITECTURE.md`
- `AGENTS.md`

## Requested Focus Paths
- `docs/endpoint-inventory.md`
- `docs/system-map/CROSS_MODULE_WORKFLOWS.md`
- `docs/system-map/REPO_OVERVIEW.md`

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.
- Reviewers are review-only; do not modify reviewer evidence files.
- Stay inside `scope_paths` and `allowed_scope_paths`.

## Agent Prompt (Copy/Paste)
```text
You are `repo-cartographer`.
Implement this slice with minimal safe patching and proof-backed output.
Start your response exactly with: I am repo-cartographer and I own SLICE-02.

Required output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
