# Task Packet

Ticket: `TKT-ERP-STAGE-103`
Slice: `SLICE-03`
Primary Agent: `inventory-domain`
Reviewers: `qa-reliability, security-governance`
Lane: `w3`
Branch: `tickets/tkt-erp-stage-103/inventory-domain`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-103/inventory-domain`

## Objective
Enforce deterministic idempotency and trace correlation across orchestrator-driven sales inventory production and accounting flows.

## Custom Multi-Agent Role (Codex)
- role: `cross_module_high`
- config_file: `.codex/agents/cross_module_high.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/inventory/`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`

## Cross-Workflow Dependencies
- Upstream slices: SLICE-01, SLICE-02, SLICE-04
- Downstream slices: none
- External upstream agents to watch: auth-rbac-company, purchasing-invoice-p2p
- External downstream agents to watch: none
- Contract edges:
  - upstream -> factory-production (slice SLICE-02): production/packing stock transitions
  - upstream -> orchestrator-runtime (slice SLICE-01): exactly-once side-effect orchestration
  - upstream -> sales-domain (slice SLICE-04): dispatch and stock movement linkage
  - upstream-external -> auth-rbac-company: tenant context and role checks
  - upstream-external -> purchasing-invoice-p2p: grn/stock intake coupling

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
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
