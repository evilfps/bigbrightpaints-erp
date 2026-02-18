# Architecture Map (Canonical)

Last reviewed: 2026-02-15
Owner: Repo Cartographer Agent

This document is the canonical architecture spec for agent runs. Keep it short and operational.

## Top-Level Components
- `erp-domain/`: Spring Boot domain service (controllers, module services, repositories, migrations).
- `scripts/`: harness and guard scripts (`scripts/verify_local.sh`, gate scripts, schema/flyway guards).
- `docs/`: repository system of record, runbooks, and async-loop procedures.
- `.github/workflows/`: CI orchestration for gate tiers and policy checks.
- `testing/`: auxiliary compose + test harness replica.
- `artifacts/`: generated gate outputs (non-canonical evidence output).

## Runtime Entrypoints
- App main: `erp-domain/src/main/java/com/bigbrightpaints/erp/ErpDomainApplication.java`
- HTTP API layer: controllers under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/*/controller`
- Orchestrator API/schedulers: `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator`
- CI/local harness entrypoints:
  - `bash scripts/verify_local.sh`
  - `bash scripts/gate_fast.sh`
  - `bash scripts/gate_core.sh`
  - `bash scripts/gate_release.sh`
  - `bash scripts/gate_reconciliation.sh`

## Domain Boundaries
- Core shared concerns: `erp-domain/src/main/java/com/bigbrightpaints/erp/core`, `erp-domain/src/main/java/com/bigbrightpaints/erp/config`, `erp-domain/src/main/java/com/bigbrightpaints/erp/shared`, and module foundations `company`, `auth`, `rbac`.
- Business modules: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/` (accounting, inventory, sales, purchasing, hr, factory, production, invoice, reports, portal).
- Cross-module workflow coordinator: `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator`.

## Inter-Module Dependency Rules (pragmatic)
- `accounting` is the posting sink/source of truth for financial side effects.
- `sales`, `inventory`, `purchasing`, and `hr` may call `accounting` services/facades for posting and reconciliation-linked behavior.
- `auth`/`rbac`/`company` provide identity and tenant context; business modules must not bypass tenant context checks.
- `orchestrator` may coordinate across modules but should centralize retry/idempotency policies.
- Architectural contract is mechanically checked by `ci/check-architecture.sh` against `ci/architecture/module-import-allowlist.txt`.
- Allowlist changes require ADR evidence (why needed, alternatives rejected, boundary preserved) enforced by `ci/architecture/check-allowlist-change-evidence.sh`.

## DB Touchpoints
- JPA repositories: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**/domain/*Repository.java`
- Migration roots:
  - legacy: `erp-domain/src/main/resources/db/migration`
  - active: `erp-domain/src/main/resources/db/migration_v2`
- Migration policy references:
  - `erp-domain/docs/FLYWAY_AUDIT_AND_STRATEGY.md`
  - `docs/runbooks/migrations.md`
- Predeploy data guards:
  - `scripts/flyway_overlap_scan.sh`
  - `scripts/schema_drift_scan.sh`
  - `scripts/run_db_predeploy_scans.sh`

## Async-Loop Compatibility
- Async operating contract: `docs/ASYNC_LOOP_OPERATIONS.md`
- Long-run state ledger: `asyncloop`
- Agent harness principle: enforce important rules with scripts/CI, not only prose docs.

## Domain Maps
- Module flow map: `erp-domain/docs/MODULE_FLOW_MAP.md`
- Order-to-cash state machines: `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`
- Procure-to-pay state machines: `erp-domain/docs/PROCURE_TO_PAY_STATE_MACHINES.md`
- Production-to-pack state machines: `erp-domain/docs/PRODUCTION_TO_PACK_STATE_MACHINES.md`
- Hire-to-pay state machines: `erp-domain/docs/HIRE_TO_PAY_STATE_MACHINES.md`

## Unknowns and TODOs
- Deployment topology details (single-node vs replicated app + DB HA) are unspecified.
  - TODO: attach infra architecture source and add topology-dependent failure domains.
- Formal architecture decision records for module boundary exceptions are partially specified.
  - TODO: add ADR entries for each new cross-module import exception.
