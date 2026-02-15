# Testing Map

## Baseline commands
- Fast local: `bash scripts/verify_local.sh`
- Unit + integration: `cd erp-domain && mvn -B -ntp test`
- Docs-only lane: `bash ci/lint-knowledgebase.sh`
- Anchored fast lane (final ledger runs): `DIFF_BASE=<RELEASE_ANCHOR_SHA> GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`

## Test profile matrix
- `-Pgate-fast`: critical truth coverage and changed-file coverage (strict mode requires fixed `DIFF_BASE` anchor).
- `-Pgate-core`: core + concurrency + reconciliation-critical truth tests.
- `-Pgate-reconciliation`: reconciliation-focused tests + mismatch export.
- `-Pgate-release`: stricter regression and release assertions.
- `-Pgate-quality`: mutation tests + flake run window.

## Module-focused evidence points
- `sales`: `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/*` for O2C, dispatch, and portal overlaps.
- `accounting`: settlement/ledger posting and period services tests under `modules/accounting`.
- `inventory`: movement/dispatch/adjustments tests under `modules/inventory`.
- `purchasing`: P2P lifecycle under `modules/purchasing`.
- `hr`: payroll lifecycle and run-state tests under `modules/hr`.
- `orchestrator`: orchestration + correlation tests under `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator`.

## Contract/guarded tests and maps
- Truth suite catalog: `docs/CODE-RED/confidence-suite/TEST_CATALOG.json`.
- Canonical flow assertions: `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`, `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`, `erp-domain/docs/PROCURE_TO_PAY_STATE_MACHINES.md`.

## Coverage expectations
- Any touched module must update evidence-backed tests before non-doc changes.
- Cross-module high-risk paths should include at least one golden-flow E2E and one invariant check per affected boundary.
- For long-running hardening trains, coverage deltas must be evaluated against a fixed anchor SHA, not implicit merge-base history.

## Async-loop evidence contract
- Run ledger gates in this order for staging closure:
  1. `DIFF_BASE=<RELEASE_ANCHOR_SHA> GATE_FAST_RELEASE_VALIDATION_MODE=true bash scripts/gate_fast.sh`
  2. `bash scripts/gate_core.sh`
  3. `bash scripts/gate_reconciliation.sh`
  4. `bash scripts/gate_release.sh`
- Record exact commands and artifact paths in `asyncloop`.
