#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-release"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"

echo "[gate-release] validate catalog"
python3 "$ROOT_DIR/scripts/validate_test_catalog.py" \
  --catalog "$ROOT_DIR/docs/CODE-RED/confidence-suite/TEST_CATALOG.json" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --gate gate-release \
  --output "$ARTIFACT_DIR/catalog-validation.json"

echo "[gate-release] flaky guard"
python3 "$ROOT_DIR/scripts/check_flaky_tags.py" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --gate gate-release \
  --output "$ARTIFACT_DIR/flake-guard.json"

echo "[gate-release] strict local verify"
FAIL_ON_FINDINGS=true bash "$ROOT_DIR/scripts/verify_local.sh"

echo "[gate-release] truth suite strict mode"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Pgate-release test
)

echo "[gate-release] fresh + upgrade migration matrix"
bash "$ROOT_DIR/scripts/release_migration_matrix.sh" --artifact-dir "$ARTIFACT_DIR"

echo "[gate-release] OK"
