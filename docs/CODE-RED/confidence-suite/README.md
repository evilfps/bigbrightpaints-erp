# Authoritative Confidence Suite

This folder is the single source of deploy-readiness testing contracts for CODE-RED.

Truth policy:
- Primary truth: production code + DB schema in this repository.
- Policy truth: docs under `docs/CODE-RED/**`.
- Existing tests are treated as hints until mapped to code flow evidence.

This suite drives five mandatory gates:
- `gate-fast`
- `gate-core`
- `gate-release`
- `gate-reconciliation`
- `gate-quality`

Authoritative truth-suite test package:
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/**`
- Gate evidence from legacy test folders is excluded.

Contents:
- `FLOW_EVIDENCE_MATRIX.md`: endpoint -> service -> repository/table -> side effects.
- `GATE_CONTRACTS.md`: exact gate commands, thresholds, and failure semantics.
- `RECONCILIATION_CONTRACT.md`: accounting/cross-module invariants and tolerances.
- `TEST_CATALOG.json`: ownership + classification + gate membership for truth tests.
- `final-enterprise/TESTING_STRATEGY.md`: final real-world enterprise strategy snapshot.

Local entrypoints:
- `bash scripts/gate_fast.sh`
- `bash scripts/gate_core.sh`
- `bash scripts/gate_release.sh`
- `bash scripts/gate_reconciliation.sh`
- `bash scripts/gate_quality.sh`
