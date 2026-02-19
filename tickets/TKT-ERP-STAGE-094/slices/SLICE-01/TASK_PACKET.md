# Task Packet

Ticket: `TKT-ERP-STAGE-094`
Slice: `SLICE-01`
Primary Agent: `release-ops`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-094/release-ops`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-094/release-ops`

## Objective
Fix gate closure fail-open and Bash/Python/macOS portability blockers: enforce immutable anchor validation, robust BASH_ENV compatibility, and deterministic knowledgebase lint behavior.

## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `ci/`
- `.github/workflows/`
- `scripts/`
- `docs/runbooks/`
- `docker-compose.yml`
- `erp-domain/Dockerfile`

## Requested Focus Paths
- `ci/lint-knowledgebase.sh`
- `scripts/check_ticket_status_parity.py`
- `scripts/gate_core.sh`
- `scripts/gate_fast.sh`
- `scripts/gate_reconciliation.sh`
- `scripts/gate_release.sh`

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

## Agent Identity Contract
- First output line must be: `I am release-ops and I own SLICE-01.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `release-ops`.
Start your first line with: `I am release-ops and I own SLICE-01.`
Use Codex custom multi-agent role `cross_module` from `.codex/agents/cross_module.toml`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
