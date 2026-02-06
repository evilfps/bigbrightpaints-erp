#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-reconciliation"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"

echo "[gate-reconciliation] validate catalog"
python3 "$ROOT_DIR/scripts/validate_test_catalog.py" \
  --catalog "$ROOT_DIR/docs/CODE-RED/confidence-suite/TEST_CATALOG.json" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --gate gate-reconciliation \
  --output "$ARTIFACT_DIR/catalog-validation.json"

echo "[gate-reconciliation] flaky guard"
python3 "$ROOT_DIR/scripts/check_flaky_tags.py" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --gate gate-reconciliation \
  --output "$ARTIFACT_DIR/flake-guard.json"

echo "[gate-reconciliation] run reconciliation truth tests"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Pgate-reconciliation test
)

echo "[gate-reconciliation] build evidence artifacts"
python3 "$ROOT_DIR/scripts/surefire_report_summary.py" \
  --reports-dir "$ROOT_DIR/erp-domain/target/surefire-reports" \
  --summary-out "$ARTIFACT_DIR/reconciliation-summary.json" \
  --mismatch-out "$ARTIFACT_DIR/mismatch-report.txt" \
  --gate gate-reconciliation

echo "[gate-reconciliation] OK"
