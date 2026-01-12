# Deep Debugging Program (Pre‑Deploy Maximum Confidence)

Goal: achieve **maximum confidence** before deployment by verifying the ERP **module-by-module** and **workflow-by-workflow**, with explicit chain‑of‑evidence guarantees for every financially significant action:

`document → journal → ledger/subledger → reconciliation`

This program is **planning + documentation + tests/evidence harness**, not feature work.

Non‑negotiables
- NO new product features, no net‑new business workflows, no UI work.
- Only: invariants/tests/evidence harness, endpoint hygiene + deprecation mapping, RBAC alignment checks, reconciliation/assertions, docs/runbooks, and safety/consistency hardening where behavior is already intended.
- Use existing endpoints and existing domain patterns. Add endpoints only if required to close an integrity gap with no equivalent endpoint.
- Never skip verification gates after each milestone.
- Capture evidence like a real audit: append to `docs/ops_and_debug/EVIDENCE.md` and store logs under `docs/ops_and_debug/LOGS/`.

## Inputs already in repo (anchors)
- Scope/invariants: `SCOPE.md`
- Async execution rules: `.codex/AGENTS.md`
- Stabilization history: `erp-domain/docs/STABILIZATION_LOG.md`
- Cross‑module traceability: `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`
- Posting contract: `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`
- Reconciliation contract: `erp-domain/docs/RECONCILIATION_CONTRACTS.md`
- Ops readiness: `erp-domain/docs/DEPLOY_CHECKLIST.md`
- Existing confidence ladder (reference): `docs/ops_and_debug/DEBUGGING_PLAN.md`
- Endpoint inventory snapshot: `erp-domain/docs/endpoint_inventory.tsv`
- OpenAPI snapshot: `openapi.json` (and `OpenApiSnapshotIT`)

## Execution order (run sequentially)
1) `tasks/debugging/task-01-architecture-and-module-map.md`
2) `tasks/debugging/task-02-endpoint-and-portal-matrix.md`
3) `tasks/debugging/task-03-auditability-and-linkage-contracts.md`
4) `tasks/debugging/task-04-module-by-module-deep-debug.md`
5) `tasks/debugging/task-05-reconciliation-and-period-controls.md`
6) `tasks/debugging/task-06-security-rbac-and-company-boundaries.md`
7) `tasks/debugging/task-07-performance-and-ops-evidence.md`

Then run the **final phase** stabilization pack: `tasks/predeploy/README.md` (renumbered tasks `08–12`).

## Global verification gates (required after every milestone)
Run these after **every milestone** in every task file:
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

Then run the task’s focused tests/invariant checks and any docker compose health checks (each task specifies which ones).

## Definition of done (entire Deep Debugging Program)
All are true:
- All tasks 01–07 milestones completed, with evidence appended to `docs/ops_and_debug/EVIDENCE.md`.
- For each core flow (O2C, P2P/AP, Production, Payroll): the chain of evidence is documented and enforced by tests (or explicitly listed as a gap with a fix/test plan).
- `mvn -f erp-domain/pom.xml test` passes with no new skips added.
- OpenAPI drift is detected and explained (`OpenApiSnapshotIT` used where relevant); deprecated endpoints have a canonical mapping in `docs/API_PORTAL_MATRIX.md`.
- RBAC and company boundary checks show no cross‑company leakage and no “any authenticated user” access to admin-grade endpoints (or such gaps are fail‑closed with minimal fixes + tests).
- Reconciliation and period controls produce **zero variance within tolerance** for golden scenarios and on a seeded “prod‑like” dataset.

