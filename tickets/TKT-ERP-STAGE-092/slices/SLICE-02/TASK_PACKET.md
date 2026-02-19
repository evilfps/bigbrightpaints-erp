# Task Packet

Ticket: `TKT-ERP-STAGE-092`
Slice: `SLICE-02`
Primary Agent: `data-migration`
Reviewers: `qa-reliability, release-ops, security-governance`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-092/data-migration`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/TKT-ERP-STAGE-092/data-migration`

## Objective
Implement superadmin-governed tenant quota fields and fail-closed update/read contract baseline on canonical branch

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/resources/db/migration_v2/`
- `scripts/`
- `docs/runbooks/migrations.md`

## Requested Focus Paths
- `erp-domain/src/main/resources/db/migration_v2/`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: release-ops
- Contract edges:
  - downstream-external -> release-ops: migration rehearsal and release gating

## Required Checks Before Done
- `bash scripts/flyway_overlap_scan.sh --migration-set v2`
- `bash scripts/schema_drift_scan.sh --migration-set v2`
- `bash scripts/release_migration_matrix_v2.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am data-migration and I own SLICE-02.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `data-migration`.
Start your first line with: `I am data-migration and I own SLICE-02.`
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
