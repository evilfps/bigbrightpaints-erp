#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-quality"
RUNS_DIR="$ARTIFACT_DIR/flake-runs"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR" "$RUNS_DIR"

FLAKE_RUNS="${FLAKE_RUNS:-20}"
FLAKE_THRESHOLD="${FLAKE_THRESHOLD:-0.01}"
MUTATION_THRESHOLD="${MUTATION_THRESHOLD:-80.0}"
MUTATION_MIN_SCORED_TOTAL="${MUTATION_MIN_SCORED_TOTAL:-120}"
MUTATION_MAX_EXCLUDED_RATIO="${MUTATION_MAX_EXCLUDED_RATIO:-0.60}"
ALLOW_SHORT_WINDOW="${ALLOW_SHORT_WINDOW:-false}"

echo "[gate-quality] validate catalog"
python3 "$ROOT_DIR/scripts/validate_test_catalog.py" \
  --catalog "$ROOT_DIR/docs/CODE-RED/confidence-suite/TEST_CATALOG.json" \
  --quarantine "$ROOT_DIR/scripts/test_quarantine.txt" \
  --tests-root "$TRUTH_TEST_ROOT" \
  --gate gate-quality \
  --output "$ARTIFACT_DIR/catalog-validation.json"

echo "[gate-quality] run mutation testing"
set +e
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/pit-reports target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Pgate-quality -Dpitest.skip=false test-compile org.pitest:pitest-maven:mutationCoverage
)
pit_cmd_status=$?
set -e

set +e
python3 "$ROOT_DIR/scripts/pit_mutation_summary.py" \
  --pit-reports "$ROOT_DIR/erp-domain/target/pit-reports" \
  --threshold "$MUTATION_THRESHOLD" \
  --min-scored-total "$MUTATION_MIN_SCORED_TOTAL" \
  --max-excluded-ratio "$MUTATION_MAX_EXCLUDED_RATIO" \
  --output "$ARTIFACT_DIR/mutation-summary.json"
pit_gate_status=$?
set -e

rm -rf "$RUNS_DIR"
mkdir -p "$RUNS_DIR"

echo "[gate-quality] run flake window (runs=$FLAKE_RUNS)"
for i in $(seq 1 "$FLAKE_RUNS"); do
  run_dir="$RUNS_DIR/run${i}"
  mkdir -p "$run_dir"
  echo "[gate-quality] flake run $i/$FLAKE_RUNS"

  set +e
  (
    cd "$ROOT_DIR/erp-domain"
    rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
    mvn -B -ntp -Pgate-core test
  )
  status=$?
  set -e

  if [[ -d "$ROOT_DIR/erp-domain/target/surefire-reports" ]]; then
    cp -R "$ROOT_DIR/erp-domain/target/surefire-reports/." "$run_dir/"
  fi

  printf '{"run": %s, "status": %s}\n' "$i" "$status" > "$run_dir/run-status.json"
done

flake_args=(
  --runs-dir "$RUNS_DIR"
  --threshold "$FLAKE_THRESHOLD"
  --window 20
  --output "$ARTIFACT_DIR/flake-rate.json"
)

if [[ "$ALLOW_SHORT_WINDOW" == "true" ]]; then
  flake_args+=(--allow-short-window)
fi

set +e
python3 "$ROOT_DIR/scripts/flake_rate_gate.py" "${flake_args[@]}"
flake_gate_status=$?
set -e

final_status=0
if [[ $pit_cmd_status -ne 0 ]]; then
  final_status=$pit_cmd_status
fi
if [[ $pit_gate_status -ne 0 ]]; then
  final_status=$pit_gate_status
fi
if [[ $flake_gate_status -ne 0 ]]; then
  final_status=$flake_gate_status
fi

if [[ $final_status -ne 0 ]]; then
  echo "[gate-quality] FAIL: mutation or flake policy violation"
  exit "$final_status"
fi

echo "[gate-quality] OK"
