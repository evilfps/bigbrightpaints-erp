# Task Packet

Ticket: `TKT-ERP-STAGE-037`
Slice: `SLICE-01`
Primary Agent: `release-ops`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-037/release-ops`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_harness_integrate_worktrees/TKT-ERP-STAGE-037/release-ops`

## Objective
Fail closed on real coverage gaps while avoiding false vacuous failures for interface-only diffs

## Agent Write Boundary (Enforced)
- `.github/workflows/`
- `scripts/`
- `docs/runbooks/`
- `docker-compose.yml`
- `erp-domain/Dockerfile`

## Requested Focus Paths
- `scripts/changed_files_coverage.py`
- `scripts/gate_fast.sh`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: data-migration
- External downstream agents to watch: none
- Contract edges:
  - upstream-external -> data-migration: migration rehearsal and release gating

## Required Checks Before Done
- `bash scripts/gate_release.sh`
- `bash scripts/gate_reconciliation.sh`

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
You are `release-ops`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
