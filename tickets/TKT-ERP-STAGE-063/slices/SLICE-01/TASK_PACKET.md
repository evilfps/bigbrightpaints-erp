# Task Packet

Ticket: `TKT-ERP-STAGE-063`
Slice: `SLICE-01`
Primary Agent: `frontend-documentation`
Reviewers: `repo-cartographer`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-063/frontend-documentation`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec_worktrees/TKT-ERP-STAGE-063/frontend-documentation`

## Objective
Deliver deploy-ready portal-by-portal frontend handoff contracts and onboarding flows

## Agent Write Boundary (Enforced)
- `docs/accounting-portal-endpoint-map.md`
- `docs/admin-portal-endpoint-map.md`
- `docs/dealer-portal-endpoint-map.md`
- `docs/manufacturing-portal-endpoint-map.md`
- `docs/sales-portal-endpoint-map.md`
- `docs/*portal*handoff*.md`

## Requested Focus Paths
- `docs/accounting-portal-endpoint-map.md`
- `docs/accounting-portal-frontend-engineer-handoff.md`
- `docs/admin-portal-endpoint-map.md`
- `docs/dealer-portal-endpoint-map.md`
- `docs/frontend-v1-portal-handoff.yaml`
- `docs/manufacturing-portal-endpoint-map.md`
- `docs/sales-portal-endpoint-map.md`

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
You are `frontend-documentation`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity line: `I am frontend-documentation and I own SLICE-01.`
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
