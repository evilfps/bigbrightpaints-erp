# Task Packet

Ticket: `TKT-ERP-STAGE-108`
Slice: `SLICE-02`
Primary Agent: `factory-production`
Reviewers: `qa-reliability, security-governance`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-108/factory-production`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-108/factory-production`

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
- role: `cross_module_high`
- config_file: `.codex/agents/cross_module_high.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/factory/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/production/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/manufacturing/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/dto/BulkVariantResponse.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogService.java`

## Acceptance Criteria
- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: SLICE-01, SLICE-03
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: none
- Contract edges:
  - downstream -> accounting-domain (slice SLICE-01): wip/variance/cogs posting linkage
  - downstream -> inventory-domain (slice SLICE-03): production/packing stock transitions
  - upstream-external -> auth-rbac-company: tenant-scoped manufacturing operations

## Required Checks Before Done
- `bash ci/check-architecture.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am factory-production and I own SLICE-02.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `factory-production`.
Start your first line with: `I am factory-production and I own SLICE-02.`
Use Codex custom multi-agent role `cross_module_high` from `.codex/agents/cross_module_high.toml`.
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
