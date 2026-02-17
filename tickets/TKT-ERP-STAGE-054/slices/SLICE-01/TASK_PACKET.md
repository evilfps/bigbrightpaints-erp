# Task Packet

Ticket: `TKT-ERP-STAGE-054`
Slice: `SLICE-01`
Primary Agent: `orchestrator-runtime`
Reviewers: `qa-reliability, security-governance`
Lane: `w1`
Branch: `tickets/tkt-erp-stage-054/orchestrator-runtime`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_exec_worktrees/TKT-ERP-STAGE-054/orchestrator-runtime`

## Objective
M18-S5B orchestrator idempotency hardening: reserve command keys without duplicate-key exception churn while preserving strict payload conflict semantics

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/orchestrator/`
- `scripts/guard_orchestrator_correlation_contract.sh`

## Requested Focus Paths
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/OrchestratorCommandRepository.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrchestratorIdempotencyService.java`

## Cross-Workflow Dependencies
- Upstream slices: none
- Downstream slices: none
- External upstream agents to watch: none
- External downstream agents to watch: accounting-domain, inventory-domain, sales-domain
- Contract edges:
  - downstream-external -> accounting-domain: outbox/idempotency posting orchestration
  - downstream-external -> inventory-domain: exactly-once side-effect orchestration
  - downstream-external -> sales-domain: async orchestration command contract

## Required Checks Before Done
- `bash ci/check-architecture.sh`
- `bash scripts/guard_orchestrator_correlation_contract.sh`

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
You are `orchestrator-runtime`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
