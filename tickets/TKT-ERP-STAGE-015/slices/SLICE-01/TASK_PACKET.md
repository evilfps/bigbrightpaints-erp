# Task Packet

Ticket: `TKT-ERP-STAGE-015`
Slice: `SLICE-01`
Primary Agent: `factory-production`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-015/factory-production`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-015/factory-production`

## Objective
M18-S7A smallest shippable hardening for bulk variant duplicate-race handling

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/factory/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/production/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/manufacturing/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogService.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: accounting-domain, inventory-domain
- Contract edges:
  - downstream-external -> accounting-domain: wip/variance/cogs posting linkage
  - downstream-external -> inventory-domain: production/packing stock transitions
  - upstream-external -> auth-rbac-company: tenant-scoped manufacturing operations

## Required Checks Before Done
- `bash ci/check-architecture.sh`
- `cd erp-domain && mvn -B -ntp -Dtest=ProductionCatalogServiceBulkVariantRaceTest test`

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
You are `factory-production`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
