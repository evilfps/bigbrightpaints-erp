# Task Packet

Ticket: `TKT-ERP-STAGE-036`
Slice: `SLICE-02`
Primary Agent: `orchestrator`
Reviewers: `qa-reliability, repo-cartographer, security-governance`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-036/orchestrator`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_harness_integrate_worktrees/TKT-ERP-STAGE-036/orchestrator`

## Objective
Eliminate worktree base-branch drift and execute Section 14.3 full gate closure on integration branch

## Agent Write Boundary (Enforced)
- `docs/`
- `agents/`
- `skills/`
- `ci/`
- `tickets/`
- `asyncloop`
- `scripts/harness_orchestrator.py`

## Requested Focus Paths
- `scripts/harness_orchestrator.py`

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`
- `bash ci/check-architecture.sh`
- `bash ci/check-enterprise-policy.sh`

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
You are `orchestrator`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
