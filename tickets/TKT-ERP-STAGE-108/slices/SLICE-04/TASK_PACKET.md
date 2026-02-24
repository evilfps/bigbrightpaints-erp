# Task Packet

Ticket: `TKT-ERP-STAGE-108`
Slice: `SLICE-04`
Primary Agent: `frontend-documentation`
Reviewers: `repo-cartographer`
Lane: `w4`
Branch: `tickets/tkt-erp-stage-108/frontend-documentation`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-108/frontend-documentation`

## Ticket Context
- title: Bulk Variants Fail-Closed and DryRun Preview
- goal: Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.

## Problem Statement
Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.

## Task To Solve
- Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.
- Expected outcome: Required checks pass and acceptance criteria are satisfied.

## Objective
Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.

## Custom Multi-Agent Role (Codex)
- role: `frontend_documentation`
- config_file: `.codex/agents/frontend_arch.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `docs/accounting-portal-endpoint-map.md`
- `docs/admin-portal-endpoint-map.md`
- `docs/dealer-portal-endpoint-map.md`
- `docs/manufacturing-portal-endpoint-map.md`
- `docs/sales-portal-endpoint-map.md`
- `docs/*portal*handoff*.md`

## Requested Focus Paths
- `docs/accounting-portal-endpoint-map.md`

## Acceptance Criteria
- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.

## Required Checks Before Done
- `bash ci/lint-knowledgebase.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am frontend-documentation and I own SLICE-04.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `frontend-documentation`.
Start your first line with: `I am frontend-documentation and I own SLICE-04.`
Use Codex custom multi-agent role `frontend_documentation` from `.codex/agents/frontend_arch.toml`.
Ticket title: Bulk Variants Fail-Closed and DryRun Preview
Problem statement: Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.
Task to solve: Make accounting catalog bulk-variants fail-closed on duplicate SKUs, add dry-run preview with shared validator path, return structured generated/conflicts/wouldCreate/created payload, and add regression tests.
Expected outcome: Required checks pass and acceptance criteria are satisfied.
Implement this slice with minimal safe patching and proof-backed output.

Execution minimum:
- diagnose current behavior in the requested focus paths
- implement the root-cause fix in allowed scope
- add/adjust tests that prove acceptance criteria
- run required checks and report evidence

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
