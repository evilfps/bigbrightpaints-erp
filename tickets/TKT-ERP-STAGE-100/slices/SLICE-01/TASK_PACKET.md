# Task Packet

Ticket: `TKT-ERP-STAGE-100`
Slice: `SLICE-01`
Primary Agent: `purchasing-invoice-p2p`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-100/purchasing-invoice-p2p`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-100/purchasing-invoice-p2p`

## Objective
Add truth-suite coverage for changed sales and purchasing approval paths so gate_fast reflects deterministic coverage.

## Custom Multi-Agent Role (Codex)
- role: `cross_module_high`
- config_file: `.codex/agents/cross_module_high.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/purchasing/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/invoice/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/p2p/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/controller/PurchasingWorkflowController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/SupplierApprovalDecision.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/SupplierApprovalPolicy.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: accounting-domain, inventory-domain
- Contract edges:
  - downstream-external -> accounting-domain: ap/posting and settlement linkage
  - downstream-external -> inventory-domain: grn/stock intake coupling
  - upstream-external -> auth-rbac-company: tenant-scoped supplier/AP access rules

## Required Checks Before Done
- `bash ci/check-architecture.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am purchasing-invoice-p2p and I own SLICE-01.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `purchasing-invoice-p2p`.
Start your first line with: `I am purchasing-invoice-p2p and I own SLICE-01.`
Use Codex custom multi-agent role `cross_module_high` from `.codex/agents/cross_module_high.toml`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
