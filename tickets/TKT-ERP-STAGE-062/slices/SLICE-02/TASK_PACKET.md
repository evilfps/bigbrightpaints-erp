# Task Packet

Ticket: `TKT-ERP-STAGE-062`
Slice: `SLICE-02`
Primary Agent: `purchasing-invoice-p2p`
Reviewers: `qa-reliability, security-governance`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-062/purchasing-invoice-p2p`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec_worktrees/TKT-ERP-STAGE-062/purchasing-invoice-p2p`

## Objective
Simplify messy backend workflows with deterministic reason-coded fail-closed behavior

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/purchasing/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/invoice/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/p2p/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: SLICE-01
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: inventory-domain
- Contract edges:
  - downstream -> accounting-domain (slice SLICE-01): ap/posting and settlement linkage
  - downstream-external -> inventory-domain: grn/stock intake coupling
  - upstream-external -> auth-rbac-company: tenant-scoped supplier/AP access rules

## Required Checks Before Done
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
You are `purchasing-invoice-p2p`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity line: `I am purchasing-invoice-p2p and I own SLICE-02.`
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
