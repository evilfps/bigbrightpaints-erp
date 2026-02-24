# Task Packet

Ticket: `TKT-ERP-STAGE-108`
Slice: `SLICE-03`
Primary Agent: `inventory-domain`
Reviewers: `qa-reliability, security-governance`
Lane: `w3`
Branch: `tickets/tkt-erp-stage-108/inventory-domain`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-108/inventory-domain`

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
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/inventory/`

## Requested Focus Paths
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/RawMaterialAndProductUpdateIT.java`

## Acceptance Criteria
- No explicit criteria in ticket YAML. Treat required checks and objective as DoD.

## Cross-Workflow Dependencies
- Upstream slices: SLICE-02
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, orchestrator-runtime, purchasing-invoice-p2p, sales-domain
- External downstream agents to watch: none
- Contract edges:
  - upstream -> factory-production (slice SLICE-02): production/packing stock transitions
  - upstream-external -> auth-rbac-company: tenant context and role checks
  - upstream-external -> orchestrator-runtime: exactly-once side-effect orchestration
  - upstream-external -> purchasing-invoice-p2p: grn/stock intake coupling
  - upstream-external -> sales-domain: dispatch and stock movement linkage

## Required Checks Before Done
- `cd erp-domain && mvn -B -ntp -Dtest='*Inventory*' test`
- `bash ci/check-architecture.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am inventory-domain and I own SLICE-03.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `inventory-domain`.
Start your first line with: `I am inventory-domain and I own SLICE-03.`
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
