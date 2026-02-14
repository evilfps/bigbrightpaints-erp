#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="v2"
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

echo "[gate-release] orchestrator correlation contract guard"
CORRELATION_GUARD_LOG="$ARTIFACT_DIR/orchestrator-correlation-guard.txt"
if bash "$ROOT_DIR/scripts/guard_orchestrator_correlation_contract.sh" >"$CORRELATION_GUARD_LOG" 2>&1; then
  cat "$CORRELATION_GUARD_LOG"
else
  cat "$CORRELATION_GUARD_LOG" >&2
  exit 1
fi

VERIFY_LOCAL_SKIP_GUARD=false

if [[ "$MIGRATION_SET" == "v2" ]]; then
  echo "[gate-release] flyway v2 transient checksum guard"
  CHECKSUM_GUARD_LOG="$ARTIFACT_DIR/flyway-v2-transient-checksum-guard.txt"

  if [[ -n "${FLYWAY_GUARD_DB_NAME:-}" && -n "${PGDATABASE:-}" && "${FLYWAY_GUARD_DB_NAME}" != "${PGDATABASE}" ]]; then
    echo "[gate-release] FLYWAY_GUARD_DB_NAME (${FLYWAY_GUARD_DB_NAME}) must match PGDATABASE (${PGDATABASE}) when both are set" | tee "$CHECKSUM_GUARD_LOG" >&2
    exit 3
  fi

  GUARD_DB_NAME="${FLYWAY_GUARD_DB_NAME:-${PGDATABASE:-}}"
  if [[ -z "${FLYWAY_GUARD_DB_NAME:-}" && -n "${PGDATABASE:-}" ]]; then
    echo "[gate-release] deriving FLYWAY_GUARD_DB_NAME from PGDATABASE=$PGDATABASE" | tee "$CHECKSUM_GUARD_LOG"
    export FLYWAY_GUARD_DB_NAME="$PGDATABASE"
  fi

  if [[ -n "$GUARD_DB_NAME" ]]; then
    if bash "$ROOT_DIR/scripts/guard_flyway_v2_transient_checksum.sh" "$GUARD_DB_NAME" >"$CHECKSUM_GUARD_LOG" 2>&1; then
      cat "$CHECKSUM_GUARD_LOG"
      VERIFY_LOCAL_SKIP_GUARD=true
    else
      guard_exit=$?
      cat "$CHECKSUM_GUARD_LOG" >&2
      exit "$guard_exit"
    fi
  elif [[ "${REQUIRE_FLYWAY_V2_GUARD:-false}" == "true" ]]; then
    echo "[gate-release] FLYWAY_GUARD_DB_NAME is required when REQUIRE_FLYWAY_V2_GUARD=true (or set PGDATABASE for fallback resolution)" | tee "$CHECKSUM_GUARD_LOG" >&2
    exit 3
  else
    echo "[gate-release] skip flyway v2 transient checksum guard (set FLYWAY_GUARD_DB_NAME or PGDATABASE to enable)" | tee "$CHECKSUM_GUARD_LOG"
  fi
fi

echo "[gate-release] strict local verify"
MIGRATION_SET="$MIGRATION_SET" VERIFY_LOCAL_SKIP_TESTS=true VERIFY_LOCAL_SKIP_FLYWAY_GUARD="$VERIFY_LOCAL_SKIP_GUARD" VERIFY_LOCAL_GUARD_ALREADY_EXECUTED="$VERIFY_LOCAL_SKIP_GUARD" FAIL_ON_FINDINGS=true bash "$ROOT_DIR/scripts/verify_local.sh"

echo "[gate-release] truth suite strict mode"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Pgate-release test
)

echo "[gate-release] fresh + upgrade migration matrix"
MIGRATION_SET="$MIGRATION_SET" bash "$ROOT_DIR/scripts/release_migration_matrix.sh" --migration-set v2 --artifact-dir "$ARTIFACT_DIR"

echo "[gate-release] OK"
