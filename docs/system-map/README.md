# System Map

Repository navigation layer for fast orientation and duplicate overlap triage.

Contents:
- `Goal/ERP_STAGING_MASTER_PLAN.md`: final stability-first staging plan and async-loop closure protocol.
- `REVIEW_QUEUE_POLICY.md`: async-loop code-review queue behavior (normal vs saturation mode).
- `LIVE_EXECUTION_PLAN.md`: always-on live task lanes and closure criteria.
- `COMPLETION_GATES_STATUS.md`: live status board for safe-to-deploy completion gates with evidence anchors.
- `tickets/TKT-ERP-STAGE-105/reports/release-evidence-freeze-20260223.md`: frozen release-candidate sign-off evidence pack.
- `REPO_OVERVIEW.md`: high-level architecture and duplicate/overlap queue.
- `CANONICAL_VOCABULARY.md`: cross-module naming contract for entities, idempotency, and ledger-gate terms.
- `MODULE_BOUNDARIES.md`: module package boundaries and allowed inter-module direction.
- `CROSS_MODULE_WORKFLOWS.md`: canonical O2C/P2P/Production-to-Pack/Payroll/Period Close chains.
- `CI_DEVOPS_MAP.md`: gate and script mapping with validation and artifacts.
- `TESTING_MAP.md`: test surfaces and where to run them.
- `MIGRATION_POLICY_V2.md`: Flyway v2 contract, safety checks, allowed/forbidden patterns.
- `modules/<module>/FILES.md`: one-line file map + entrypoints and high-risk files for each module.

Operational rules used while building this map:
- Source-of-truth docs are authoritative for flow/correctness claims.
- No code changes are made in this task slice; this is docs-only.
- Focus is navigation and evidence-backed cleanup queue generation.

## Canonical vocabulary guardrails
- Canonical term definitions live in `CANONICAL_VOCABULARY.md`.
- Role-specific terms (`dealer`, `supplier`) are allowed only in workflow-local context where role specificity is required.
