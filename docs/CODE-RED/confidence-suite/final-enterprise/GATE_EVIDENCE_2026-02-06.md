# Gate Evidence - 2026-02-06 (Non-Vacuous Branch-as-Trunk Validation)

## Baseline Anchor

- `RELEASE_ANCHOR_SHA=2df86f7b55fd457d768d3218f651cc2c341397ac`
- Anchor rationale: baseline commit before the current hardening train; release validation forbids `HEAD~N`.

## Commands Executed

1. `DIFF_BASE=2df86f7b55fd457d768d3218f651cc2c341397ac GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
2. `bash scripts/gate_core.sh`
3. `bash scripts/gate_reconciliation.sh`
4. `PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp bash scripts/gate_release.sh`
5. `bash scripts/gate_quality.sh`

## Gate Outcomes

- `gate-fast`: `PASS` (`exit=0`)
  - artifact: `artifacts/gate-fast/changed-coverage.json`
  - `files_considered=3`
  - `line_total=7`, `line_covered=7`, `line_ratio=1.0` (threshold `0.95`)
  - `branch_total=0`, `branch_ratio=1.0` (threshold `0.90`)
  - `vacuous=false` (release validation mode now fails on vacuous summaries)
- `gate-core`: `PASS` (`exit=0`)
  - artifact: `artifacts/gate-core/module-coverage.json`
  - thresholds: `line>=0.92`, `branch>=0.85`
  - results: `line_ratio=0.9887323943661972`, `branch_ratio=0.93125`
  - `active_classes=10` (min `7`), `active_packages=5` (min `4`)
- `gate-reconciliation`: `PASS` (`exit=0`)
  - artifact: `artifacts/gate-reconciliation/reconciliation-summary.json`
  - tests: `97`, failures: `0`, errors: `0`, skipped: `0`
- `gate-release`: `PASS` (`exit=0`)
  - artifact: `artifacts/gate-release/migration-matrix.json`
  - migration matrix:
    - expected_count: `132`
    - expected_max_version: `132`
    - fresh: `132/132`
    - upgrade: `132/132`
- `gate-quality`: `PASS` (`exit=0`)
  - artifacts:
    - `artifacts/gate-quality/mutation-summary.json`
    - `artifacts/gate-quality/flake-rate.json`
  - mutation:
    - `mutation_score=84.298` (threshold `80.0`)
    - `scored_total=121` (min `120`)
    - `excluded_ratio=0.04724` (max `0.60`)
  - flake window:
    - `runs_evaluated=20/20`
    - `flake_rate=0.0` (threshold `<0.01`)

## Non-Vacuous Coverage Proof

- `artifacts/gate-fast/changed-coverage.json` shows executable changed lines covered:
  - `AccountingFacade.java`: `1/1`
  - `AccountingPeriodService.java`: `5/5`
  - `InventoryValuationService.java`: `1/1`

## Gate Selection Proof (No Legacy Tests)

- `scripts/gate_fast.sh`, `scripts/gate_core.sh`, `scripts/gate_reconciliation.sh`, `scripts/gate_release.sh`, `scripts/gate_quality.sh` all use:
  - `TRUTH_TEST_ROOT=.../erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite`
- `erp-domain/pom.xml` gate profiles include only:
  - `**/truthsuite/**/*Test.java`
  - `**/truthsuite/**/*IT.java`
  - `**/truthsuite/**/*Suite.java`
