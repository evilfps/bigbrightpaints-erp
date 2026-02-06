#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-core"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"

echo "[gate-core] validate catalog"
python3 "$ROOT_DIR/scripts/validate_test_catalog.py" \
  --catalog "$ROOT_DIR/docs/CODE-RED/confidence-suite/TEST_CATALOG.json" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --gate gate-core \
  --output "$ARTIFACT_DIR/catalog-validation.json"

echo "[gate-core] flaky guard"
python3 "$ROOT_DIR/scripts/check_flaky_tags.py" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --gate gate-core \
  --output "$ARTIFACT_DIR/flake-guard.json"

echo "[gate-core] run critical+concurrency+reconciliation truth tests"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Pgate-core test
)

echo "[gate-core] module coverage gate"
python3 "$ROOT_DIR/scripts/module_coverage_gate.py" \
  --jacoco "$ROOT_DIR/erp-domain/target/site/jacoco/jacoco.xml" \
  --packages com.bigbrightpaints.erp.modules.accounting,com.bigbrightpaints.erp.modules.inventory,com.bigbrightpaints.erp.modules.invoice,com.bigbrightpaints.erp.orchestrator.policy,com.bigbrightpaints.erp.orchestrator.service,com.bigbrightpaints.erp.orchestrator.workflow \
  --line-threshold 0.92 \
  --branch-threshold 0.85 \
  --output "$ARTIFACT_DIR/module-coverage.json"

echo "[gate-core] OK"
