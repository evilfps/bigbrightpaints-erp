# System Map

Repository navigation layer for fast orientation and duplicate overlap triage.

Contents:
- `REPO_OVERVIEW.md`: high-level architecture and duplicate/overlap queue.
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
- `partner`: canonical cross-module term for external commercial entities in shared contracts, idempotency conflict metadata, and platform-level docs.
- `dealer`: sales/O2C role specialization of `partner`.
- `supplier`: purchasing/P2P role specialization of `partner`.
- `idempotencyKey`: canonical key name for replay identity in request headers, exception details, and audit metadata.
