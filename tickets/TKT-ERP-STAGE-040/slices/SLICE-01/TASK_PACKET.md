# Task Packet

Ticket: `TKT-ERP-STAGE-040`
Slice: `SLICE-01`
Primary Agent: `orchestrator`
Reviewers: `qa-reliability, repo-cartographer, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-040/orchestrator`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_harness_integrate_worktrees/TKT-ERP-STAGE-040/orchestrator`

## Objective
Close stale ticket metadata and align active ledger with merged evidence

## Agent Write Boundary (Enforced)
- `docs/`
- `agents/`
- `skills/`
- `ci/`
- `tickets/`
- `asyncloop`
- `scripts/harness_orchestrator.py`

## Requested Focus Paths
- `tickets/TKT-ERP-STAGE-001/SUMMARY.md`
- `tickets/TKT-ERP-STAGE-001/TIMELINE.md`
- `tickets/TKT-ERP-STAGE-001/ticket.yaml`
- `tickets/TKT-ERP-STAGE-030/SUMMARY.md`
- `tickets/TKT-ERP-STAGE-030/TIMELINE.md`
- `tickets/TKT-ERP-STAGE-030/ticket.yaml`

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
