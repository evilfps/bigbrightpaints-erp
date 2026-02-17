# Task Packet

Ticket: `TKT-ERP-STAGE-038`
Slice: `SLICE-02`
Primary Agent: `repo-cartographer`
Reviewers: `orchestrator`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-038/repo-cartographer`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_harness_integrate_worktrees/TKT-ERP-STAGE-038/repo-cartographer`

## Objective
Enforce expiring quarantine policy and keep release decisions based on invariant signal quality

## Agent Write Boundary (Enforced)
- `docs/`
- `ARCHITECTURE.md`
- `AGENTS.md`

## Requested Focus Paths
- `docs/system-map/Goal/ENTERPRISE_BACKEND_STABILIZATION_PLAN.md`
- `docs/system-map/Goal/ERP_ENTERPRISE_DEPLOYMENT_DEEP_SPEC.md`

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
