# Task Packet

Ticket: `TKT-ERP-STAGE-103`
Slice: `SLICE-02`
Primary Agent: `factory-production`
Reviewers: `qa-reliability, security-governance`
Lane: `w2`
Branch: `tickets/tkt-erp-stage-103/factory-production`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-103/factory-production`

## Objective
Enforce deterministic idempotency and trace correlation across orchestrator-driven sales inventory production and accounting flows.

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
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/FactoryService.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: SLICE-03
- External upstream agents to watch: auth-rbac-company
- External downstream agents to watch: accounting-domain
- Contract edges:
  - downstream -> inventory-domain (slice SLICE-03): production/packing stock transitions
  - downstream-external -> accounting-domain: wip/variance/cogs posting linkage
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
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
