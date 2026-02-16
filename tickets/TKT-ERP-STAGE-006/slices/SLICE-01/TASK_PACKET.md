# Task Packet

Ticket: `TKT-ERP-STAGE-006`
Slice: `SLICE-01`
Primary Agent: `release-ops`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-006/release-ops`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-006/release-ops`

## Identity Contract
- Start your response exactly with: `I am release-ops and I own SLICE-01.`

## Objective
M18-S3A guard lane - enforce canonical workflow decision contract in CI for O2C/P2P/production/payroll.

## YAML Contract
- `agents/release-ops.agent.yaml`

## Agent Write Boundary (Enforced)
- `ci/`
- `.github/workflows/`
- `scripts/`
- `docs/runbooks/`
- `docker-compose.yml`
- `erp-domain/Dockerfile`

## Requested Focus Paths
- `ci/check-enterprise-policy.sh`
- `scripts/guard_workflow_canonical_paths.sh`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: data-migration
- External downstream agents to watch: none
- Contract edges:
  - upstream-external -> data-migration: migration rehearsal and release gating

## Required Checks Before Done
- `bash scripts/guard_workflow_canonical_paths.sh`
- `bash ci/check-enterprise-policy.sh`
- `bash ci/check-architecture.sh`

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
You are `release-ops`.
Implement this slice with minimal safe patching and proof-backed output.
Start your response exactly with: I am release-ops and I own SLICE-01.

Required output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
