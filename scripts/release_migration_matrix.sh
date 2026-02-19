#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="${MIGRATION_SET:-v2}"
MIGRATIONS_DIR=""
FLYWAY_LOCATIONS=""
FLYWAY_HISTORY_TABLE=""
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-release"
KEEP_RELEASE_DBS="${KEEP_RELEASE_DBS:-false}"

usage() {
  cat <<USAGE
Usage: bash scripts/release_migration_matrix.sh [--migration-set <v1|v2>] [--artifact-dir <dir>]

Environment:
  MIGRATION_SET (default: v2)
  PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
  SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD (used as fallback when PGUSER/PGPASSWORD are unset)
  RELEASE_DB_PREFIX (default: codered_release)
  KEEP_RELEASE_DBS=true to skip cleanup
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --migration-set)
      MIGRATION_SET="${2:-}"
      shift 2
      ;;
    --artifact-dir)
      ARTIFACT_DIR="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[release_migration_matrix] unknown arg: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$MIGRATION_SET" in
  v1)
    MIGRATIONS_DIR="$ROOT_DIR/erp-domain/src/main/resources/db/migration"
    FLYWAY_LOCATIONS="filesystem:src/main/resources/db/migration"
    FLYWAY_HISTORY_TABLE="flyway_schema_history"
    ;;
  v2)
    MIGRATIONS_DIR="$ROOT_DIR/erp-domain/src/main/resources/db/migration_v2"
    FLYWAY_LOCATIONS="filesystem:src/main/resources/db/migration_v2"
    FLYWAY_HISTORY_TABLE="flyway_schema_history_v2"
    ;;
  *)
    echo "[release_migration_matrix] invalid migration set: $MIGRATION_SET (expected v1 or v2)" >&2
    exit 2
    ;;
esac

if [[ ! -d "$MIGRATIONS_DIR" ]]; then
  echo "[release_migration_matrix] migrations dir not found: $MIGRATIONS_DIR" >&2
  exit 2
fi

for cmd in psql dropdb createdb mvn; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[release_migration_matrix] required command missing: $cmd" >&2
    exit 2
  fi
done

PGHOST="${PGHOST:-127.0.0.1}"
PGUSER="${PGUSER:-${SPRING_DATASOURCE_USERNAME:-erp}}"
PGPASSWORD="${PGPASSWORD:-${SPRING_DATASOURCE_PASSWORD:-erp}}"
PGDATABASE="${PGDATABASE:-postgres}"

if [[ -z "${PGPORT:-}" ]]; then
  if command -v docker >/dev/null 2>&1 && docker inspect gate_release_pg >/dev/null 2>&1; then
    mapped_port="$(docker port gate_release_pg 5432/tcp 2>/dev/null | head -n 1 | awk -F: '{print $NF}')"
    if [[ -n "$mapped_port" ]]; then
      PGPORT="$mapped_port"
    fi
  fi
fi
PGPORT="${PGPORT:-5432}"
export PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE

mkdir -p "$ARTIFACT_DIR"

mapfile -t versions < <(ls -1 "$MIGRATIONS_DIR" | sed -n 's/^V\([0-9]\+\)__.*$/\1/p' | sort -n)
if [[ ${#versions[@]} -lt 2 ]]; then
  echo "[release_migration_matrix] need at least 2 versioned migrations" >&2
  exit 2
fi

expected_count="${#versions[@]}"
expected_max="${versions[-1]}"
expected_prev="${versions[-2]}"
expected_prev_count="$((expected_count - 1))"

suffix_raw="${GITHUB_RUN_ID:-local_$(date +%s)}"
suffix="$(printf '%s' "$suffix_raw" | tr -cd '[:alnum:]_')"
prefix="${RELEASE_DB_PREFIX:-codered_release}"

fresh_db="${prefix}_fresh_${suffix}"
upgrade_db="${prefix}_upgrade_${suffix}"

cleanup() {
  if [[ "$KEEP_RELEASE_DBS" == "true" ]]; then
    echo "[release_migration_matrix] KEEP_RELEASE_DBS=true, skipping db cleanup"
    return 0
  fi
  dropdb --if-exists "$fresh_db" >/dev/null 2>&1 || true
  dropdb --if-exists "$upgrade_db" >/dev/null 2>&1 || true
}
trap cleanup EXIT

reset_db() {
  local db_name="$1"
  dropdb --if-exists "$db_name"
  createdb "$db_name"
}

flyway_migrate() {
  local db_name="$1"
  local target="${2:-}"
  local args=(
    -B
    -ntp
    org.flywaydb:flyway-maven-plugin:migrate
    "-Dflyway.url=jdbc:postgresql://${PGHOST}:${PGPORT}/${db_name}"
    "-Dflyway.user=${PGUSER}"
    "-Dflyway.password=${PGPASSWORD}"
    "-Dflyway.locations=${FLYWAY_LOCATIONS}"
    "-Dflyway.table=${FLYWAY_HISTORY_TABLE}"
  )
  if [[ -n "$target" ]]; then
    args+=("-Dflyway.target=${target}")
  fi
  (cd "$ROOT_DIR/erp-domain" && mvn "${args[@]}")
}

history_count() {
  local db_name="$1"
  psql "postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${db_name}" \
    -Atqc "select count(*) from ${FLYWAY_HISTORY_TABLE} where success = true"
}

history_max() {
  local db_name="$1"
  psql "postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${db_name}" \
    -Atqc "select coalesce(max(case when version ~ '^[0-9]+$' then version::int end), 0) from ${FLYWAY_HISTORY_TABLE} where success = true"
}

scan_db() {
  local db_name="$1"
  local out_file="$2"
  local scan_expected_count="${3:-$expected_count}"
  local scan_expected_max="${4:-$expected_max}"
  local scan_file="$ROOT_DIR/scripts/db_predeploy_scans.sql"
  if [[ "$MIGRATION_SET" == "v2" ]]; then
    psql "postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${db_name}" -v ON_ERROR_STOP=1 -qAt <<'SQL'
DO $block$
BEGIN
  IF to_regclass('public.flyway_schema_history') IS NULL
     AND to_regclass('public.flyway_schema_history_v2') IS NOT NULL THEN
    EXECUTE 'create view public.flyway_schema_history as select * from public.flyway_schema_history_v2';
  END IF;
END
$block$;
SQL

    scan_file="$(mktemp)"
    sed -E \
      "s/select [0-9]+::int as expected_count, [0-9]+::int as expected_max_version/select ${scan_expected_count}::int as expected_count, ${scan_expected_max}::int as expected_max_version/" \
      "$ROOT_DIR/scripts/db_predeploy_scans.sql" > "$scan_file"
  fi
  if bash "$ROOT_DIR/scripts/run_db_predeploy_scans.sh" \
    --db-url "postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${db_name}" \
    --output "$out_file" \
    --scan-file "$scan_file"; then
    true
  else
    scan_status=$?
    if [[ "$scan_file" != "$ROOT_DIR/scripts/db_predeploy_scans.sql" ]]; then
      rm -f "$scan_file"
    fi
    return "$scan_status"
  fi
  if [[ "$scan_file" != "$ROOT_DIR/scripts/db_predeploy_scans.sql" ]]; then
    rm -f "$scan_file"
  fi
}

echo "[release_migration_matrix] fresh path"
reset_db "$fresh_db"
flyway_migrate "$fresh_db"
fresh_count="$(history_count "$fresh_db")"
fresh_max="$(history_max "$fresh_db")"
scan_db "$fresh_db" "$ARTIFACT_DIR/predeploy-scans-fresh.txt"

echo "[release_migration_matrix] upgrade path"
reset_db "$upgrade_db"
flyway_migrate "$upgrade_db" "$expected_prev"
upgrade_prev_count="$(history_count "$upgrade_db")"
upgrade_prev_max="$(history_max "$upgrade_db")"
scan_db "$upgrade_db" "$ARTIFACT_DIR/predeploy-scans-upgrade-seed.txt" "$expected_prev_count" "$expected_prev"
flyway_migrate "$upgrade_db"
upgrade_count="$(history_count "$upgrade_db")"
upgrade_max="$(history_max "$upgrade_db")"
scan_db "$upgrade_db" "$ARTIFACT_DIR/predeploy-scans-upgrade.txt"

if [[ "$fresh_count" != "$expected_count" || "$fresh_max" != "$expected_max" ]]; then
  echo "[release_migration_matrix] fresh migration history mismatch" >&2
  exit 1
fi

if [[ "$upgrade_count" != "$expected_count" || "$upgrade_max" != "$expected_max" ]]; then
  echo "[release_migration_matrix] upgrade migration history mismatch" >&2
  exit 1
fi

if [[ "$upgrade_prev_count" != "$expected_prev_count" || "$upgrade_prev_max" != "$expected_prev" ]]; then
  echo "[release_migration_matrix] rollback rehearsal seed mismatch" >&2
  exit 1
fi

cat > "$ARTIFACT_DIR/migration-matrix.json" <<JSON
{
  "migration_set": "${MIGRATION_SET}",
  "flyway_history_table": "${FLYWAY_HISTORY_TABLE}",
  "expected_count": ${expected_count},
  "expected_max_version": ${expected_max},
  "upgrade_seed_version": ${expected_prev},
  "upgrade_seed_expected_count": ${expected_prev_count},
  "fresh": {
    "database": "${fresh_db}",
    "history_count": ${fresh_count},
    "history_max_version": ${fresh_max}
  },
  "rollback_rehearsal_seed": {
    "database": "${upgrade_db}",
    "history_count": ${upgrade_prev_count},
    "history_max_version": ${upgrade_prev_max},
    "scan_artifact": "artifacts/gate-release/predeploy-scans-upgrade-seed.txt"
  },
  "upgrade": {
    "database": "${upgrade_db}",
    "history_count": ${upgrade_count},
    "history_max_version": ${upgrade_max}
  }
}
JSON

cat > "$ARTIFACT_DIR/rollback-rehearsal-evidence.json" <<JSON
{
  "migration_set": "${MIGRATION_SET}",
  "rehearsal": "upgrade-seed-rollback-window",
  "rollback_target_version": ${expected_prev},
  "rollback_target_expected_history_count": ${expected_prev_count},
  "rollback_target_actual_history_count": ${upgrade_prev_count},
  "rollback_target_actual_max_version": ${upgrade_prev_max},
  "rollback_target_scan_artifact": "artifacts/gate-release/predeploy-scans-upgrade-seed.txt",
  "post_rehearsal_upgrade_actual_history_count": ${upgrade_count},
  "post_rehearsal_upgrade_actual_max_version": ${upgrade_max},
  "status": "pass"
}
JSON

echo "[release_migration_matrix] OK"
