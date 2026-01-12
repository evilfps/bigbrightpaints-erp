# Final Predeploy Stabilization Phase (ERP Backend)

Goal: run a **correctness-only** stabilization pass before deployment so the ERP cannot report “success” while leaving accounting, inventory, or workflow state wrong.

Non‑negotiables:
- NO new product features, no UI work. Only: consistency fixes, missing linkage fixes, invariants, reconciliation, docs/runbooks, test coverage, safety guards, endpoint cleanup (remove deprecated endpoints **only if truly unused**).
- Prefer small, localized changes. Avoid wide refactors unless required for correctness.
- Never skip verification: run the required gates after **every milestone**.

## Inputs already in repo (anchors)
- Scope/invariants: `SCOPE.md`
- Execution rules: `.codex/AGENTS.md`
- Stabilization history: `erp-domain/docs/STABILIZATION_LOG.md`
- Onboarding contract: `erp-domain/docs/ONBOARDING_GUIDE.md`
- Posting contract: `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`
- Workflow/state docs: `erp-domain/docs/*STATE_MACHINES.md`
- Traceability: `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`
- Reconciliation: `erp-domain/docs/RECONCILIATION_CONTRACTS.md`
- Ops readiness: `erp-domain/docs/DEPLOY_CHECKLIST.md`

## When to run this phase
Run this phase **after** completing the Deep Debugging Program in `tasks/debugging/README.md`.

## Execution order (run sequentially)
1) `tasks/predeploy/task-08-predeploy-consistency.md`
2) `tasks/predeploy/task-09-ledger-subledger-gaps.md`
3) `tasks/predeploy/task-10-masterdata-and-onboarding-audit.md`
4) `tasks/predeploy/task-11-endpoint-hygiene-and-deprecations.md`
5) `tasks/predeploy/task-12-ops-and-data-integrity.md`

## Global verification gates (required after every milestone)
Run these after **every milestone** in every task file:
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

Then run the task’s focused tests/invariant checks (each task specifies which ones).

## Evidence standard (treat like a real pre-deploy audit)
Capture evidence so it can be reviewed later:
- Append run summaries to `docs/ops_and_debug/EVIDENCE.md`
- Store raw logs/output under `docs/ops_and_debug/LOGS/` (redact secrets)

## Definition of done (entire predeploy program)
All are true:
- All tasks 08–12 milestones completed with evidence captured.
- `mvn -f erp-domain/pom.xml test` passes with no new skips added.
- Golden paths + invariants are stable under retry (idempotency) and cannot create duplicate postings.
- Reconciliation checks (inventory↔GL, AR/AP↔control accounts, period close checklist) produce **zero variance within tolerance** for golden scenarios and on a seeded “prod-like” dataset.
- No financially-impacting endpoint can create unlinked/unreconcilable state (missing journal links, orphaned movements, cross-company link leakage).
- Deprecated endpoints are either:
  - removed (only if verified unused), or
  - consistently marked deprecated in OpenAPI + docs with canonical replacements.
- Deploy runbook + smoke checks are executable and reflect required env vars and rollback safety.
