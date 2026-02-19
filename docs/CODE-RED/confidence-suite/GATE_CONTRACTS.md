# Gate Contracts

This document defines mandatory lane contracts for one immutable SHA.

Authoritative truth-suite scope:
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/**`
- Gate evidence from legacy tests is forbidden.

## Gate-Fast

Purpose:
- PR hard-fail lane for critical safety regressions in <=15 minutes.

Command:
- `bash scripts/gate_fast.sh`
- Local long-lived branch usage:
  - `DIFF_BASE=$(git rev-parse HEAD~1) bash scripts/gate_fast.sh`
  - CI/PR resolves `DIFF_BASE` from the pull-request base SHA unless overridden.
- Branch-as-trunk final certification usage:
  - `GATE_FAST_RELEASE_VALIDATION_MODE=true DIFF_BASE=<RELEASE_ANCHOR_SHA> bash scripts/gate_fast.sh`
  - In this mode, `DIFF_BASE` is mandatory and `HEAD~N` values are rejected.
  - `RELEASE_ANCHOR_SHA` must be a fixed baseline commit before the current hardening train on this branch.
- Long-lived branch convergence PR usage:
  - `GATE_FAST_SYNC_PR_MODE=true bash scripts/gate_fast.sh`
  - This mode runs all gate-fast guards + critical truth tests and intentionally skips changed-file coverage enforcement.
  - Allowed only for orchestrated `sync/*` convergence PRs where the source hardening line is already release-certified.

Enforced:
- Only tests in `.../truthsuite/**`.
- `@Tag("critical")` tests only, excluding `@Tag("flaky")`.
- Changed-line coverage from JaCoCo XML + git diff:
  - line >= `0.95`
  - branch >= `0.90`
  - release validation mode is invalid if changed coverage is vacuous:
    - `files_considered == 0` or
    - `line_total == 0`
- Flaky guard:
  - any lane-included test with `@Tag("flaky")` fails.
- Catalog/ownership validation for tagged truth tests.

Artifacts:
- `artifacts/gate-fast/changed-coverage.json`
- `artifacts/gate-fast/flake-guard.json`
- `artifacts/gate-fast/catalog-validation.json`

Failure semantics:
- any threshold miss, flaky-tag violation, or test failure is `NO-GO`.

## Gate-Core

Purpose:
- mainline integration lane for critical + concurrency + reconciliation truth tests.

Command:
- `bash scripts/gate_core.sh`

Enforced:
- Only tests in `.../truthsuite/**`.
- `@Tag("critical")`, `@Tag("concurrency")`, and `@Tag("reconciliation")` selection via Maven groups.
- Excludes `@Tag("flaky")`.
- Module coverage floor on critical packages:
  - line >= `0.92`
  - branch >= `0.85`
  - active runtime classes >= `7`
  - active runtime packages >= `4`
- Catalog/ownership validation.

Critical package scope:
- `com.bigbrightpaints.erp.modules.accounting`
- `com.bigbrightpaints.erp.modules.inventory`
- `com.bigbrightpaints.erp.modules.invoice`
- `com.bigbrightpaints.erp.orchestrator.policy`
- `com.bigbrightpaints.erp.orchestrator.service`
- `com.bigbrightpaints.erp.orchestrator.workflow`

Runtime class scope enforced inside those packages:
- `com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService`
- `com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService`
- `com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService`
- `com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy`
- `com.bigbrightpaints.erp.orchestrator.policy.PolicyEnforcer`
- `com.bigbrightpaints.erp.orchestrator.service.TraceService`
- `com.bigbrightpaints.erp.orchestrator.service.OrchestratorIdempotencyService`

Artifacts:
- `artifacts/gate-core/module-coverage.json`
- `artifacts/gate-core/catalog-validation.json`
- `artifacts/gate-core/flake-guard.json`

Failure semantics:
- any selected test failure, coverage floor miss, or flake/catalog violation is `NO-GO`.

## Gate-Release

Purpose:
- strict release SHA lane.

Command:
- `bash scripts/gate_release.sh`
- Local DB override example:
  - `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp bash scripts/gate_release.sh`
  - `scripts/release_migration_matrix.sh` falls back to `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` when `PGUSER`/`PGPASSWORD` are unset.

Enforced:
- Only tests in `.../truthsuite/**`.
- `FAIL_ON_FINDINGS=true bash scripts/verify_local.sh`
- Fresh migration path: empty DB -> latest migration.
- Upgrade migration path: N-1 -> latest migration.
- `scripts/db_predeploy_scans.sql` must return zero rows on both paths.
- Catalog/ownership validation.

Artifacts:
- `artifacts/gate-release/migration-matrix.json`
- `artifacts/gate-release/predeploy-scans-fresh.txt`
- `artifacts/gate-release/predeploy-scans-upgrade.txt`
- `artifacts/gate-release/catalog-validation.json`

Failure semantics:
- any verify failure, migration failure, or predeploy scan row is `NO-GO`.

## Gate-Reconciliation

Purpose:
- prove operational truth == financial truth using deterministic cross-module assertions.

Command:
- `bash scripts/gate_reconciliation.sh`

Enforced:
- Only tests in `.../truthsuite/**`.
- `@Tag("reconciliation")` truth tests only, excluding `@Tag("flaky")`.
- Reconciliation evidence summarization from Surefire XML.
- Empty mismatch report required.

Artifacts:
- `artifacts/gate-reconciliation/reconciliation-summary.json`
- `artifacts/gate-reconciliation/mismatch-report.txt`
- `artifacts/gate-reconciliation/catalog-validation.json`

Failure semantics:
- any reconciliation test failure or non-empty mismatch report is `NO-GO`.

## Gate-Quality

Purpose:
- nightly confidence durability checks (mutation + flake-rate + portfolio governance).

Command:
- `bash scripts/gate_quality.sh`

Enforced:
- PIT mutation score for critical modules >= `80.0`.
- PIT actionable-signal floor:
  - `scored_total >= 120`
  - `excluded_ratio <= 0.60`
- PIT mutation scope (actionable classes):
  - `com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService`
  - `com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService`
  - `com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy`
  - `com.bigbrightpaints.erp.orchestrator.policy.PolicyEnforcer`
  - `com.bigbrightpaints.erp.orchestrator.service.TraceService`
  - `com.bigbrightpaints.erp.orchestrator.service.OrchestratorIdempotencyService`
  - `ReferenceNumberService` remains enforced in `gate-core` runtime branch coverage and deterministic contract tests; it is excluded from PIT because it is formatting/hash heavy with low actionable mutation signal.
- Rolling flake-rate check over `20` runs on monitored tests:
  - flake rate < `0.01`.
- Catalog completeness and quarantine enforcement.
- Flake window executes `-Pgate-core`, which is restricted to `.../truthsuite/**`.

Artifacts:
- `artifacts/gate-quality/mutation-summary.json`
- `artifacts/gate-quality/flake-rate.json`
- `artifacts/gate-quality/catalog-validation.json`

Failure semantics:
- mutation score below threshold or flake rate >= 1% is `NO-GO`.

## Promotion Rule

Release promotion is allowed only when one immutable SHA has green:
- `gate-fast`
- `gate-core`
- `gate-release`
- `gate-reconciliation`
- `gate-quality` (nightly durability gate must be healthy before promotion window)
