# CI/DevOps Map

## Gate list
- `gate_fast`
- `gate_core`
- `gate_release`
- `gate_reconciliation`
- `gate_quality`
- `verify_local`

## Trigger map (when each runs)
- `gate_fast`: on PRs and workflow_dispatch with `diff_base`; validates changed-file coverage + selected truth tests.
- `gate_core`: on push to `main`; runs core checks plus module coverage gate.
- `gate_release`: on tags and workflow_dispatch release path; runs strict v2 predeploy checks and release matrix.
- `gate_reconciliation`: on tags and release workflow_dispatch; focuses on reconciliation test suites and mismatch summary.
- `gate_quality`: nightly and optional manual workflow_dispatch (with `run_quality_gate=true`); includes mutation + flake windows.
- `verify_local`: local/CI preflight invoked by gate jobs and by developers.

## What each checks

### gate_fast
- Test catalog validation (`gate-fast` profile)
- Flaky tag guard
- Orchestrator correlation guard
- Accounting portal scope guard
- Audit trail ownership guard
- Critical truth tests
- Coverage delta validation vs `DIFF_BASE`
- Artifacts: `artifacts/gate-fast/*` and Maven surefire/site reports.

### gate_fast `DIFF_BASE` policy (v2 schema)
- Use a fixed commit SHA for `DIFF_BASE` in long-running async-loop hardening trains.
- For final ledger closure runs, execute:
  - `DIFF_BASE=<RELEASE_ANCHOR_SHA> GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
- In release validation mode, `HEAD~N` references are intentionally rejected; always pass a concrete SHA.
- Do not weaken line/branch thresholds to bypass broad historical diffs; rotate `RELEASE_ANCHOR_SHA` only after a fully green ledger-gate bundle.

## Async-loop final ledger gate order (staging)
1. `DIFF_BASE=<RELEASE_ANCHOR_SHA> GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
2. `bash scripts/gate_core.sh`
3. `bash scripts/gate_reconciliation.sh`
4. `bash scripts/gate_release.sh`
5. Optional hardening lane: `bash scripts/gate_quality.sh`
6. Record the exact commands and artifact paths in `asyncloop`.

### gate_core
- Test catalog + flaky + orchestrator + portal scope + audit ownership guards
- Critical + concurrency + reconciliation truth tests
- Module coverage thresholds for accounting/inventory/invoice/orchestrator classes
- Artifacts: `artifacts/gate-core/*` plus coverage/surefire.

### gate_release
- Same safety guards as `gate_fast/core` + v2 ownership/referential contract guards
- Flyway v2 migration ownership + fixture matrix + transient checksum guard
- Strict `verify_local.sh`
- `mvn -Pgate-release` tests
- Release migration matrix (`scripts/release_migration_matrix.sh`)
- Artifacts: `artifacts/gate-release/*`, `erp-domain/target/site/jacoco`, `erp-domain/target/surefire-reports`.

### gate_reconciliation
- Test catalog + flaky guard
- Reconciliation truth tests (`-Pgate-reconciliation`)
- Evidence summary and mismatch report from surefire outputs
- Artifacts: `artifacts/gate-reconciliation/*`.

### gate_quality
- Mutation tests (`pitest`) + mutation threshold gates
- Flake window run (`FLAKE_RUNS`) with pass-rate gating
- Artifacts: `artifacts/gate-quality/*`, `erp-domain/target/pit-reports`, `artifacts/gate-quality/flake-runs/*`.

### verify_local
- Guard checks: legacy freeze, drift scan, overlap scan, orchestrator correlation, portal scope, audit trail ownership.
- v2 contract checks: payroll bootstrap, ownership, referential fixture matrices.
- Flyway guard contract and optional transient checksum delegation.
- `mvn verify` with v2 migration profile by default.
- Artifacts: Maven test/reports + local guard command outputs.

## Flaky/noisy areas and stabilization priority
1. **High**: `gate_quality` flake variance from repeated `-Pgate-core` runs; mitigate with deterministic test selection and idempotency cleanup.
2. **High**: overlap scans can be noisy if legacy/legacy-v2 naming conflicts exist; keep `migration_v2` clean and use convergence scripts.
3. **Medium**: large modules with broad coverage gates can fail for missing targeted tests; maintain evidence-backed test slices for touched files.
4. **Medium**: duplicate contract checks in `gate_fast` and `gate_core`; keep outputs deterministic by avoiding non-deterministic assertions.

## CI artifact locations
- `artifacts/gate-fast`, `artifacts/gate-core`, `artifacts/gate-release`, `artifacts/gate-reconciliation`, `artifacts/gate-quality`
- `artifacts/certificates` and general run artifacts are separate and not required by these gates.
