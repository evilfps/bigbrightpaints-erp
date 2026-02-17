#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="v2"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-release"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
GATE_START_UTC="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
RELEASE_SHA="${RELEASE_SHA:-}"
if [[ -z "$RELEASE_SHA" ]] && resolved_sha="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null)"; then
  RELEASE_SHA="$resolved_sha"
fi
if [[ -z "$RELEASE_SHA" || "$RELEASE_SHA" == "unknown" ]]; then
  echo "[gate-release] ERROR: unable to resolve release SHA; set RELEASE_SHA explicitly or run within a git checkout with HEAD available" >&2
  exit 1
fi
if resolved_head_sha="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null)"; then
  if [[ "$RELEASE_SHA" != "$resolved_head_sha" ]]; then
    echo "[gate-release] ERROR: RELEASE_SHA mismatch; expected checkout HEAD $resolved_head_sha but got $RELEASE_SHA" >&2
    exit 1
  fi
fi
TRACEABILITY_FILE="$ARTIFACT_DIR/release-gate-traceability.json"

# Keep local release matrix runs deterministic on integration worktrees while
# still honoring explicit PG* overrides used by CI/jobs.
RELEASE_MATRIX_PGHOST="${RELEASE_MATRIX_PGHOST:-${PGHOST:-127.0.0.1}}"
RELEASE_MATRIX_PGPORT="${RELEASE_MATRIX_PGPORT:-${PGPORT:-55432}}"
RELEASE_MATRIX_PGUSER="${RELEASE_MATRIX_PGUSER:-${PGUSER:-${SPRING_DATASOURCE_USERNAME:-erp}}}"
RELEASE_MATRIX_PGPASSWORD="${RELEASE_MATRIX_PGPASSWORD:-${PGPASSWORD:-${SPRING_DATASOURCE_PASSWORD:-erp}}}"
RELEASE_MATRIX_PGDATABASE="${RELEASE_MATRIX_PGDATABASE:-${PGDATABASE:-postgres}}"

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

echo "[gate-release] integration-failure metadata schema guard"
INTEGRATION_FAILURE_SCHEMA_GUARD_LOG="$ARTIFACT_DIR/integration-failure-metadata-schema-guard.txt"
if bash "$ROOT_DIR/scripts/guard_integration_failure_metadata_schema.sh" >"$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG" 2>&1; then
  cat "$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG"
else
  cat "$INTEGRATION_FAILURE_SCHEMA_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-release] integration-failure schema fixture matrix"
INTEGRATION_FAILURE_SCHEMA_FIXTURE_LOG="$ARTIFACT_DIR/integration-failure-metadata-schema-fixture-matrix.txt"
if bash "$ROOT_DIR/scripts/guard_integration_failure_metadata_schema_fixture_matrix.sh" >"$INTEGRATION_FAILURE_SCHEMA_FIXTURE_LOG" 2>&1; then
  cat "$INTEGRATION_FAILURE_SCHEMA_FIXTURE_LOG"
else
  cat "$INTEGRATION_FAILURE_SCHEMA_FIXTURE_LOG" >&2
  exit 1
fi

echo "[gate-release] flyway guard contract guard"
GUARD_CONTRACT_LOG="$ARTIFACT_DIR/flyway-guard-contract-guard.txt"
if bash "$ROOT_DIR/scripts/guard_flyway_guard_contract.sh" >"$GUARD_CONTRACT_LOG" 2>&1; then
  cat "$GUARD_CONTRACT_LOG"
else
  cat "$GUARD_CONTRACT_LOG" >&2
  exit 1
fi

echo "[gate-release] payroll account bootstrap contract guard"
PAYROLL_BOOTSTRAP_GUARD_LOG="$ARTIFACT_DIR/payroll-account-bootstrap-contract-guard.txt"
if bash "$ROOT_DIR/scripts/guard_payroll_account_bootstrap_contract.sh" >"$PAYROLL_BOOTSTRAP_GUARD_LOG" 2>&1; then
  cat "$PAYROLL_BOOTSTRAP_GUARD_LOG"
else
  cat "$PAYROLL_BOOTSTRAP_GUARD_LOG" >&2
  exit 1
fi

echo "[gate-release] flyway v2 migration ownership guard"
MIGRATION_OWNERSHIP_LOG="$ARTIFACT_DIR/flyway-v2-migration-ownership-guard.txt"
if bash "$ROOT_DIR/scripts/guard_flyway_v2_migration_ownership.sh" >"$MIGRATION_OWNERSHIP_LOG" 2>&1; then
  cat "$MIGRATION_OWNERSHIP_LOG"
else
  cat "$MIGRATION_OWNERSHIP_LOG" >&2
  exit 1
fi

echo "[gate-release] flyway v2 migration ownership fixture matrix"
MIGRATION_OWNERSHIP_FIXTURE_LOG="$ARTIFACT_DIR/flyway-v2-migration-ownership-fixture-matrix.txt"
if bash "$ROOT_DIR/scripts/guard_flyway_v2_migration_ownership_fixture_matrix.sh" >"$MIGRATION_OWNERSHIP_FIXTURE_LOG" 2>&1; then
  cat "$MIGRATION_OWNERSHIP_FIXTURE_LOG"
else
  cat "$MIGRATION_OWNERSHIP_FIXTURE_LOG" >&2
  exit 1
fi

echo "[gate-release] flyway v2 referential contract canary"
REFERENTIAL_CONTRACT_LOG="$ARTIFACT_DIR/flyway-v2-referential-contract-guard.txt"
if bash "$ROOT_DIR/scripts/guard_flyway_v2_referential_contract.sh" >"$REFERENTIAL_CONTRACT_LOG" 2>&1; then
  cat "$REFERENTIAL_CONTRACT_LOG"
else
  cat "$REFERENTIAL_CONTRACT_LOG" >&2
  exit 1
fi

echo "[gate-release] flyway v2 referential contract fixture matrix"
REFERENTIAL_FIXTURE_LOG="$ARTIFACT_DIR/flyway-v2-referential-contract-fixture-matrix.txt"
if bash "$ROOT_DIR/scripts/guard_flyway_v2_referential_contract_fixture_matrix.sh" >"$REFERENTIAL_FIXTURE_LOG" 2>&1; then
  cat "$REFERENTIAL_FIXTURE_LOG"
else
  cat "$REFERENTIAL_FIXTURE_LOG" >&2
  exit 1
fi

VERIFY_LOCAL_SKIP_GUARD=false
ALLOW_GUARD_DB_MISMATCH="${ALLOW_FLYWAY_GUARD_DB_MISMATCH:-false}"

if [[ -n "${FLYWAY_GUARD_DB_NAME:-}" ]]; then
  VERIFY_LOCAL_GUARD_DB_NAME="$FLYWAY_GUARD_DB_NAME"
elif [[ -n "${PGDATABASE:-}" ]]; then
  VERIFY_LOCAL_GUARD_DB_NAME="$PGDATABASE"
else
  VERIFY_LOCAL_GUARD_DB_NAME=""
fi

if [[ "$MIGRATION_SET" == "v2" ]]; then
  echo "[gate-release] flyway v2 transient checksum guard"
  CHECKSUM_GUARD_LOG="$ARTIFACT_DIR/flyway-v2-transient-checksum-guard.txt"

  if [[ -n "${FLYWAY_GUARD_DB_NAME:-}" && -n "${PGDATABASE:-}" && "${FLYWAY_GUARD_DB_NAME}" != "${PGDATABASE}" ]]; then
    if [[ "$ALLOW_GUARD_DB_MISMATCH" != "true" ]]; then
      {
        echo "[gate-release] FLYWAY_GUARD_DB_NAME and PGDATABASE differ."
        echo "[gate-release] Set ALLOW_FLYWAY_GUARD_DB_MISMATCH=true only when intentionally targeting different databases."
      } | tee "$CHECKSUM_GUARD_LOG" >&2
      exit 4
    fi
    echo "[gate-release] WARNING: FLYWAY_GUARD_DB_NAME and PGDATABASE differ; using FLYWAY_GUARD_DB_NAME as guard target due to ALLOW_FLYWAY_GUARD_DB_MISMATCH=true" | tee "$CHECKSUM_GUARD_LOG" >&2
  fi

  GUARD_DB_NAME="$VERIFY_LOCAL_GUARD_DB_NAME"
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
MIGRATION_SET="$MIGRATION_SET" \
FLYWAY_GUARD_DB_NAME="$VERIFY_LOCAL_GUARD_DB_NAME" \
ALLOW_FLYWAY_GUARD_DB_MISMATCH="$ALLOW_GUARD_DB_MISMATCH" \
VERIFY_LOCAL_TEST_STRATEGY=high-signal \
VERIFY_LOCAL_SKIP_TESTS=true \
VERIFY_LOCAL_SKIP_MVN_VERIFY=true \
VERIFY_LOCAL_SKIP_RELEASE_DUPLICATE_GUARDS=true \
VERIFY_LOCAL_RELEASE_PREFLIGHT_DONE=true \
VERIFY_LOCAL_SKIP_FLYWAY_GUARD="$VERIFY_LOCAL_SKIP_GUARD" \
VERIFY_LOCAL_GUARD_ALREADY_EXECUTED="$VERIFY_LOCAL_SKIP_GUARD" \
FAIL_ON_FINDINGS=true \
bash "$ROOT_DIR/scripts/verify_local.sh"

echo "[gate-release] truth suite strict mode"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Dsurefire.runOrder=alphabetical -Pgate-release test
)

echo "[gate-release] fresh + upgrade migration matrix"
echo "[gate-release] release matrix db target: ${RELEASE_MATRIX_PGHOST}:${RELEASE_MATRIX_PGPORT}/${RELEASE_MATRIX_PGDATABASE} (user=${RELEASE_MATRIX_PGUSER})"
MIGRATION_SET="$MIGRATION_SET" \
RELEASE_SHA="$RELEASE_SHA" \
PGHOST="$RELEASE_MATRIX_PGHOST" \
PGPORT="$RELEASE_MATRIX_PGPORT" \
PGUSER="$RELEASE_MATRIX_PGUSER" \
PGPASSWORD="$RELEASE_MATRIX_PGPASSWORD" \
PGDATABASE="$RELEASE_MATRIX_PGDATABASE" \
bash "$ROOT_DIR/scripts/release_migration_matrix.sh" --migration-set v2 --artifact-dir "$ARTIFACT_DIR"

echo "[gate-release] verify release evidence artifacts"
required_release_artifacts=(
  "$ARTIFACT_DIR/migration-matrix.json"
  "$ARTIFACT_DIR/predeploy-scans-fresh.txt"
  "$ARTIFACT_DIR/predeploy-scans-upgrade-seed.txt"
  "$ARTIFACT_DIR/predeploy-scans-upgrade.txt"
  "$ARTIFACT_DIR/rollback-rehearsal-evidence.json"
)
for required_artifact in "${required_release_artifacts[@]}"; do
  if [[ ! -f "$required_artifact" ]]; then
    echo "[gate-release] missing required artifact: $required_artifact" >&2
    exit 1
  fi
done

echo "[gate-release] verify rollback rehearsal SHA parity"
python3 - "$ARTIFACT_DIR/migration-matrix.json" "$ARTIFACT_DIR/rollback-rehearsal-evidence.json" "$RELEASE_SHA" <<'PY'
import json
import sys

matrix_path, rollback_path, release_sha = sys.argv[1:4]

with open(matrix_path, "r", encoding="utf-8") as handle:
    matrix_payload = json.load(handle)
with open(rollback_path, "r", encoding="utf-8") as handle:
    rollback_payload = json.load(handle)

matrix_sha = matrix_payload.get("release_sha")
rollback_sha = rollback_payload.get("release_sha")
if matrix_sha != release_sha:
    raise SystemExit(
        f"[gate-release] migration-matrix release_sha mismatch: expected {release_sha}, got {matrix_sha}"
    )
if rollback_sha != release_sha:
    raise SystemExit(
        f"[gate-release] rollback-rehearsal-evidence release_sha mismatch: expected {release_sha}, got {rollback_sha}"
    )
PY

echo "[gate-release] build traceability manifest"
python3 - "$ARTIFACT_DIR" "$MIGRATION_SET" "$GATE_START_UTC" "$RELEASE_SHA" <<'PY'
import hashlib
import json
import os
import sys
import time

artifact_dir, migration_set, started_at_utc, release_sha = sys.argv[1:5]
manifest_path = os.path.join(artifact_dir, "release-gate-traceability.json")
tmp_path = manifest_path + ".tmp"
artifacts = []

for name in sorted(os.listdir(artifact_dir)):
    path = os.path.join(artifact_dir, name)
    if not os.path.isfile(path):
        continue
    if path == manifest_path:
        continue
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    stat_result = os.stat(path)
    artifacts.append(
        {
            "path": f"artifacts/gate-release/{name}",
            "sha256": digest.hexdigest(),
            "bytes": stat_result.st_size,
        }
    )

payload = {
    "gate": "gate-release",
    "release_sha": release_sha,
    "migration_set": migration_set,
    "started_at_utc": started_at_utc,
    "finished_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "artifact_count": len(artifacts),
    "artifacts": artifacts,
}

with open(tmp_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2, sort_keys=True)
    handle.write("\n")
os.replace(tmp_path, manifest_path)
PY
cat "$TRACEABILITY_FILE"

echo "[gate-release] OK"
