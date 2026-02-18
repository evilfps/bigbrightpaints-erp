#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS_DIR="$ROOT_DIR/erp-domain/src/main/resources/db/migration"
ARTIFACT_DIR="$ROOT_DIR/artifacts/gate-release"
KEEP_RELEASE_DBS="${KEEP_RELEASE_DBS:-false}"

usage() {
  cat <<USAGE
Usage: bash scripts/release_migration_matrix.sh [--artifact-dir <dir>]

Environment:
  PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
  SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD (used as fallback when PGUSER/PGPASSWORD are unset)
  RELEASE_DB_PREFIX (default: codered_release)
  KEEP_RELEASE_DBS=true to skip cleanup
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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
    "-Dflyway.locations=filesystem:src/main/resources/db/migration"
  )
  if [[ -n "$target" ]]; then
    args+=("-Dflyway.target=${target}")
  fi
  (cd "$ROOT_DIR/erp-domain" && mvn "${args[@]}")
}

history_count() {
  local db_name="$1"
  psql "postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${db_name}" \
    -Atqc "select count(*) from flyway_schema_history where success = true"
}

history_max() {
  local db_name="$1"
  psql "postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${db_name}" \
    -Atqc "select coalesce(max(case when version ~ '^[0-9]+$' then version::int end), 0) from flyway_schema_history where success = true"
}

scan_db() {
  local db_name="$1"
  local out_file="$2"
  bash "$ROOT_DIR/scripts/run_db_predeploy_scans.sh" \
    --db-url "postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${db_name}" \
    --output "$out_file"
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

cat > "$ARTIFACT_DIR/migration-matrix.json" <<JSON
{
  "expected_count": ${expected_count},
  "expected_max_version": ${expected_max},
  "upgrade_seed_version": ${expected_prev},
  "fresh": {
    "database": "${fresh_db}",
    "history_count": ${fresh_count},
    "history_max_version": ${fresh_max}
  },
  "upgrade": {
    "database": "${upgrade_db}",
    "history_count": ${upgrade_count},
    "history_max_version": ${upgrade_max}
  }
}
JSON

echo "[release_migration_matrix] OK"
