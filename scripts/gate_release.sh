#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="v2"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-release"
TRUTH_TEST_ROOT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite"
COMPAT_BASH_ENV_BOOTSTRAP="$ROOT_DIR/scripts/bash_env_bootstrap.sh"
MAVEN_MEMORY_DEFAULTS="$ROOT_DIR/scripts/maven_memory_defaults.sh"
if [[ -f "$MAVEN_MEMORY_DEFAULTS" ]]; then
  source "$MAVEN_MEMORY_DEFAULTS"
  bbp_ensure_maven_memory_defaults
elif [[ -z "${MAVEN_OPTS:-}" ]]; then
  export MAVEN_OPTS="-Xmx${BBP_MAVEN_XMX:-1536m} -XX:MaxMetaspaceSize=${BBP_MAVEN_MAX_METASPACE:-512m} -XX:+UseG1GC"
fi
if [[ "${BASH_ENV:-}" != "$COMPAT_BASH_ENV_BOOTSTRAP" && -n "${BASH_ENV:-}" ]]; then
  export BBP_CHAINED_BASH_ENV="${BASH_ENV:-}"
  export BBP_CHAINED_BASH_ENV_PARENT_PID="$$"
else
  unset BBP_CHAINED_BASH_ENV
  unset BBP_CHAINED_BASH_ENV_PARENT_PID
fi

probe_pg() {
  local host="$1"
  local port="$2"
  local user="$3"
  local password="$4"
  local database="$5"
  if ! command -v psql >/dev/null 2>&1; then
    return 1
  fi
  PGPASSWORD="$password" psql \
    -h "$host" \
    -p "$port" \
    -U "$user" \
    -d "$database" \
    -Atqc "select 1" >/dev/null 2>&1
}

ensure_release_matrix_postgres() {
  local auto_start="${AUTO_START_GATE_RELEASE_PG:-true}"
  local host="${PGHOST:-127.0.0.1}"
  local user="${PGUSER:-${SPRING_DATASOURCE_USERNAME:-erp}}"
  local password="${PGPASSWORD:-${SPRING_DATASOURCE_PASSWORD:-erp}}"
  local database="${PGDATABASE:-postgres}"
  local configured_port="${PGPORT:-}"
  local probe_port="${configured_port:-5432}"
  local container="gate_release_pg"

  if [[ "$auto_start" != "true" ]]; then
    return 0
  fi

  case "$host" in
    127.0.0.1|localhost) ;;
    *)
      return 0
      ;;
  esac

  if probe_pg "$host" "$probe_port" "$user" "$password" "$database"; then
    export PGHOST="$host"
    export PGPORT="$probe_port"
    export PGUSER="$user"
    export PGPASSWORD="$password"
    export PGDATABASE="$database"
    return 0
  fi

  if ! command -v docker >/dev/null 2>&1; then
    echo "[gate-release] postgres unreachable at $host:$probe_port and docker is unavailable; migration matrix may fail." >&2
    return 0
  fi

  if docker inspect "$container" >/dev/null 2>&1; then
    if [[ -z "$configured_port" ]]; then
      local mapped_port
      # A stale stopped container can make `docker port` exit non-zero under pipefail.
      mapped_port="$(docker port "$container" 5432/tcp 2>/dev/null | head -n 1 | awk -F: '{print $NF}' || true)"
      if [[ -n "$mapped_port" ]]; then
        probe_port="$mapped_port"
        export PGPORT="$mapped_port"
      fi
    fi
    if probe_pg "$host" "$probe_port" "$user" "$password" "$database"; then
      echo "[gate-release] using existing $container on $host:$probe_port"
      return 0
    fi
  fi

  local start_port="${configured_port:-55432}"
  echo "[gate-release] starting local postgres container $container on $host:$start_port for migration matrix"
  docker rm -f "$container" >/dev/null 2>&1 || true
  docker run -d --name "$container" \
    -e POSTGRES_USER="$user" \
    -e POSTGRES_PASSWORD="$password" \
    -e POSTGRES_DB="$database" \
    -p "${start_port}:5432" \
    postgres:16 >/dev/null

  local ready=false
  for _ in $(seq 1 60); do
    if docker exec "$container" pg_isready -U "$user" -d "$database" >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 1
  done

  if [[ "$ready" != "true" ]]; then
    echo "[gate-release] FAIL: postgres container $container did not become ready in time." >&2
    exit 1
  fi

  export PGHOST="$host"
  export PGPORT="$start_port"
  export PGUSER="$user"
  export PGPASSWORD="$password"
  export PGDATABASE="$database"

  if ! probe_pg "$host" "$start_port" "$user" "$password" "$database"; then
    echo "[gate-release] FAIL: unable to connect to auto-started postgres at $host:$start_port." >&2
    exit 1
  fi
}

export BASH_ENV="$COMPAT_BASH_ENV_BOOTSTRAP"
rm -rf "$ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"
GATE_START_UTC="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
EXPECTED_RELEASE_HEAD_SHA="${RELEASE_HEAD_SHA:-}"
RESOLVED_RELEASE_HEAD_SHA="unknown"
GIT_CONTEXT_AVAILABLE="false"
if resolved_sha="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null)"; then
  RESOLVED_RELEASE_HEAD_SHA="$resolved_sha"
  GIT_CONTEXT_AVAILABLE="true"
elif [[ -n "$EXPECTED_RELEASE_HEAD_SHA" ]]; then
  RESOLVED_RELEASE_HEAD_SHA="$EXPECTED_RELEASE_HEAD_SHA"
fi
if [[ -n "$EXPECTED_RELEASE_HEAD_SHA" && "$GIT_CONTEXT_AVAILABLE" == "true" && "$EXPECTED_RELEASE_HEAD_SHA" != "$RESOLVED_RELEASE_HEAD_SHA" ]]; then
  echo "[gate-release] FAIL: RELEASE_HEAD_SHA=$EXPECTED_RELEASE_HEAD_SHA does not match current HEAD=$RESOLVED_RELEASE_HEAD_SHA"
  exit 2
fi
CANONICAL_BASE_REF="${GATE_CANONICAL_BASE_REF:-harness-engineering-orchestrator}"
CANONICAL_BASE_REQUIRED="${GATE_REQUIRE_CANONICAL_BASE:-true}"
CANONICAL_BASE_SHA=""
CANONICAL_BASE_VERIFIED="false"

resolve_canonical_base() {
  local requested_ref="$CANONICAL_BASE_REF"
  local -a candidate_refs=()
  local -a resolved_refs=()
  local -a resolved_shas=()
  local candidate_ref
  local candidate_sha

  if [[ "$GIT_CONTEXT_AVAILABLE" != "true" ]]; then
    if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
      echo "[gate-release] FAIL: canonical base verification requires git context"
      exit 2
    fi
    return 0
  fi

  candidate_refs=("$requested_ref")
  if [[ "$requested_ref" != origin/* ]]; then
    candidate_refs+=("origin/$requested_ref")
  fi
  for fallback_ref in main origin/main; do
    if [[ "$fallback_ref" != "$requested_ref" ]]; then
      candidate_refs+=("$fallback_ref")
    fi
  done

  for candidate_ref in "${candidate_refs[@]}"; do
    if candidate_sha="$(git -C "$ROOT_DIR" rev-parse --verify --quiet "$candidate_ref" 2>/dev/null)"; then
      resolved_refs+=("$candidate_ref")
      resolved_shas+=("$candidate_sha")
    fi
  done

  if [[ "${#resolved_refs[@]}" -eq 0 ]]; then
    if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
      echo "[gate-release] FAIL: canonical base ref '$requested_ref' was not found and no usable mainline fallback was available"
      exit 2
    fi
    return 0
  fi

  if [[ "$CANONICAL_BASE_REQUIRED" == "true" ]]; then
    local idx
    for idx in "${!resolved_refs[@]}"; do
      if git -C "$ROOT_DIR" merge-base --is-ancestor "${resolved_shas[$idx]}" "$RESOLVED_RELEASE_HEAD_SHA"; then
        CANONICAL_BASE_REF="${resolved_refs[$idx]}"
        CANONICAL_BASE_SHA="${resolved_shas[$idx]}"
        CANONICAL_BASE_VERIFIED="true"
        if [[ "$CANONICAL_BASE_REF" != "$requested_ref" ]]; then
          echo "[gate-release] WARN: canonical base '$requested_ref' is unavailable/stale; using '$CANONICAL_BASE_REF' ($CANONICAL_BASE_SHA)"
        fi
        return 0
      fi
    done

    local resolved_desc=""
    for idx in "${!resolved_refs[@]}"; do
      if [[ -n "$resolved_desc" ]]; then
        resolved_desc+=", "
      fi
      resolved_desc+="${resolved_refs[$idx]} (${resolved_shas[$idx]})"
    done
    echo "[gate-release] FAIL: HEAD=$RESOLVED_RELEASE_HEAD_SHA is not based on canonical base candidates: $resolved_desc"
    exit 2
  fi

  CANONICAL_BASE_REF="${resolved_refs[0]}"
  CANONICAL_BASE_SHA="${resolved_shas[0]}"
}

resolve_canonical_base
TRACEABILITY_STRICT_MODE="$GIT_CONTEXT_AVAILABLE"
TRACEABILITY_FILE="$ARTIFACT_DIR/release-gate-traceability.json"

echo "[gate-release] confidence-lane contract"
python3 "$ROOT_DIR/scripts/validate_confidence_lanes.py" \
  --contract "$ROOT_DIR/testing/local/confidence-lanes.json" \
  --lane staging \
  --output "$ARTIFACT_DIR/confidence-lane-contract.json"

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
MIGRATION_SET="$MIGRATION_SET" FLYWAY_GUARD_DB_NAME="$VERIFY_LOCAL_GUARD_DB_NAME" ALLOW_FLYWAY_GUARD_DB_MISMATCH="$ALLOW_GUARD_DB_MISMATCH" VERIFY_LOCAL_SKIP_TESTS=true VERIFY_LOCAL_SKIP_MVN_VERIFY=true VERIFY_LOCAL_SKIP_FLYWAY_GUARD="$VERIFY_LOCAL_SKIP_GUARD" VERIFY_LOCAL_GUARD_ALREADY_EXECUTED="$VERIFY_LOCAL_SKIP_GUARD" FAIL_ON_FINDINGS=true bash "$ROOT_DIR/scripts/verify_local.sh"

echo "[gate-release] truth suite strict mode"
(
  cd "$ROOT_DIR/erp-domain"
  rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
  mvn -B -ntp -Pgate-release test
)

echo "[gate-release] ensure migration matrix database connectivity"
ensure_release_matrix_postgres

echo "[gate-release] fresh + upgrade migration matrix"
MIGRATION_SET="$MIGRATION_SET" bash "$ROOT_DIR/scripts/release_migration_matrix.sh" --artifact-dir "$ARTIFACT_DIR"

if [[ "$TRACEABILITY_STRICT_MODE" == "true" ]]; then
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
else
  echo "[gate-release] traceability strict mode disabled (no git SHA context); skipping release artifact completeness enforcement"
fi

echo "[gate-release] build traceability manifest"
python3 - "$ARTIFACT_DIR" "$TRACEABILITY_FILE" "$MIGRATION_SET" "$GATE_START_UTC" "$RESOLVED_RELEASE_HEAD_SHA" "$GIT_CONTEXT_AVAILABLE" "$CANONICAL_BASE_REF" "$CANONICAL_BASE_SHA" "$CANONICAL_BASE_REQUIRED" "$CANONICAL_BASE_VERIFIED" <<'PY'
import hashlib
import json
import os
import sys
import time

(
    artifact_dir,
    manifest_path,
    migration_set,
    started_at_utc,
    release_head_sha,
    git_context_available,
    canonical_base_ref,
    canonical_base_sha,
    canonical_base_required,
    canonical_base_verified,
) = sys.argv[1:11]

tmp_path = manifest_path + ".tmp"
artifacts = []

for name in sorted(os.listdir(artifact_dir)):
    path = os.path.join(artifact_dir, name)
    if not os.path.isfile(path):
        continue
    if path == manifest_path:
        continue
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
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
    "release_sha": release_head_sha,
    "release_head_sha": release_head_sha,
    "migration_set": migration_set,
    "git_context_available": git_context_available.lower() == "true",
    "canonical_base_ref": canonical_base_ref,
    "canonical_base_sha": canonical_base_sha or None,
    "canonical_base_required": canonical_base_required.lower() == "true",
    "canonical_base_verified": canonical_base_verified.lower() == "true",
    "started_at_utc": started_at_utc,
    "finished_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "artifact_count": len(artifacts),
    "artifacts": artifacts,
}

with open(tmp_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)
    fh.write("\n")
os.replace(tmp_path, manifest_path)
PY
cat "$TRACEABILITY_FILE"

echo "[gate-release] OK"
