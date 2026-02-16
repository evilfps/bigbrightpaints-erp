# Task Packet

Ticket: `TKT-ERP-STAGE-014`
Slice: `SLICE-01`
Primary Agent: `repo-cartographer`
Reviewers: `orchestrator`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-014/repo-cartographer`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-014/repo-cartographer`

## Objective
M18-S3B docs closure: refresh canonical workflow/duplicate-path decisions from latest merged slices

## Agent Write Boundary (Enforced)
- `docs/`
- `ARCHITECTURE.md`
- `AGENTS.md`

## Requested Focus Paths
- `docs/CODE-RED/decision-log.md`

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

## Agent Prompt (Copy/Paste)
```text
You are `repo-cartographer`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
