# Task Packet

Ticket: `TKT-ERP-STAGE-008`
Slice: `SLICE-01`
Primary Agent: `release-ops`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-008/release-ops`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-008/release-ops`

## Objective
M18-S9A smallest shippable closure: tighten OpenAPI drift enforcement and portal endpoint-map parity

## Agent Write Boundary (Enforced)
- `.github/workflows/`
- `scripts/`
- `docs/runbooks/`
- `docker-compose.yml`
- `erp-domain/Dockerfile`

## Requested Focus Paths
- `scripts/map_openapi_frontend.py`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: data-migration
- External downstream agents to watch: none
- Contract edges:
  - upstream-external -> data-migration: migration rehearsal and release gating

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`
- `bash ci/check-architecture.sh`

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
