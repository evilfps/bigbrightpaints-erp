# Task Packet

Ticket: `TKT-ERP-STAGE-061`
Slice: `SLICE-03`
Primary Agent: `refactor-techdebt-gc`
Reviewers: `qa-reliability`
Lane: `w3`
Branch: `tickets/tkt-erp-stage-061/refactor-techdebt-gc`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec_worktrees/TKT-ERP-STAGE-061/refactor-techdebt-gc`

## Objective
Close remaining accounting/data safety risks for v1 staging go-no-go

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/`
- `erp-domain/src/test/java/`
- `docs/`

## Requested Focus Paths
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/accounting/`

## Required Checks Before Done
- `bash ci/check-architecture.sh`
- `cd erp-domain && mvn -B -ntp test`

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
You are `refactor-techdebt-gc`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity line: `I am refactor-techdebt-gc and I own SLICE-03.`
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
