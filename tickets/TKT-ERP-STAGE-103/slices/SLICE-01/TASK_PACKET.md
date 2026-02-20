# Task Packet

Ticket: `TKT-ERP-STAGE-103`
Slice: `SLICE-01`
Primary Agent: `orchestrator-runtime`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-103/orchestrator-runtime`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-103/orchestrator-runtime`

## Objective
Enforce deterministic idempotency and trace correlation across orchestrator-driven sales inventory production and accounting flows.

## Custom Multi-Agent Role (Codex)
- role: `cross_module`
- config_file: `.codex/agents/cross_module.toml`
- runtime_profile: `resolved at runtime from role config`

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/orchestrator/`
- `scripts/guard_orchestrator_correlation_contract.sh`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/OrchestratorController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrchestratorCommandRepository.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/EventPublisherService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrchestratorIdempotencyService.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: SLICE-03, SLICE-04
- External upstream agents to watch: none
- External downstream agents to watch: accounting-domain
- Contract edges:
  - downstream -> inventory-domain (slice SLICE-03): exactly-once side-effect orchestration
  - downstream -> sales-domain (slice SLICE-04): async orchestration command contract
  - downstream-external -> accounting-domain: outbox/idempotency posting orchestration

## Required Checks Before Done
- `bash ci/check-architecture.sh`
- `bash scripts/guard_orchestrator_correlation_contract.sh`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Agent Identity Contract
- First output line must be: `I am orchestrator-runtime and I own SLICE-01.`

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `orchestrator-runtime`.
Start your first line with: `I am orchestrator-runtime and I own SLICE-01.`
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
