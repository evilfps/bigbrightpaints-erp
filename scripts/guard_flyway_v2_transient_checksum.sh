#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DB_NAME="${FLYWAY_GUARD_DB_NAME:-${1:-}}"
DB_HOST="${PGHOST:-${FLYWAY_GUARD_DB_HOST:-localhost}}"
DB_PORT="${PGPORT:-${FLYWAY_GUARD_DB_PORT:-5432}}"
DB_USER="${PGUSER:-${FLYWAY_GUARD_DB_USER:-postgres}}"
DB_PASSWORD="${PGPASSWORD:-${FLYWAY_GUARD_DB_PASSWORD:-}}"
SCHEMA_NAME="${FLYWAY_GUARD_SCHEMA:-public}"
HISTORY_TABLE="${FLYWAY_GUARD_HISTORY_TABLE:-flyway_schema_history_v2}"

V12_VERSION="${FLYWAY_GUARD_V12_VERSION:-12}"
V13_VERSION="${FLYWAY_GUARD_V13_VERSION:-13}"

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/guard_flyway_v2_transient_checksum.sh <db_name>

Environment overrides:
  PGHOST / FLYWAY_GUARD_DB_HOST
  PGPORT / FLYWAY_GUARD_DB_PORT
  PGUSER / FLYWAY_GUARD_DB_USER
  PGPASSWORD / FLYWAY_GUARD_DB_PASSWORD
  FLYWAY_GUARD_HISTORY_TABLE (default: flyway_schema_history_v2)
  FLYWAY_GUARD_SCHEMA (default: public)
USAGE
}

fail() {
  echo "[guard_flyway_v2_transient_checksum] ERROR: $1" >&2
  exit 1
}

assert_identifier() {
  local label="$1"
  local value="$2"
  [[ "$value" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail "$label must match ^[A-Za-z_][A-Za-z0-9_]*$: '$value'"
}

assert_numeric_token() {
  local label="$1"
  local value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || fail "$label must be numeric: '$value'"
}

[[ -n "$DB_NAME" ]] || {
  usage >&2
  fail "database name is required"
}

command -v psql >/dev/null 2>&1 || fail "psql is required"

assert_identifier "schema" "$SCHEMA_NAME"
assert_identifier "history table" "$HISTORY_TABLE"
assert_numeric_token "V12 version" "$V12_VERSION"
assert_numeric_token "V13 version" "$V13_VERSION"

export PGPASSWORD="$DB_PASSWORD"

psql_query() {
  local sql="$1"
  psql \
    -h "$DB_HOST" \
    -p "$DB_PORT" \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -X -qAt \
    -c "$sql"
}

table_exists="$(psql_query "SELECT to_regclass('${SCHEMA_NAME}.${HISTORY_TABLE}') IS NOT NULL;")"
if [[ "$table_exists" != "t" ]]; then
  echo "[guard_flyway_v2_transient_checksum] OK: ${SCHEMA_NAME}.${HISTORY_TABLE} not found; no v2 checksum state to guard."
  exit 0
fi

history_table_ref="\"${SCHEMA_NAME}\".\"${HISTORY_TABLE}\""
v12_success_count="$(psql_query "SELECT count(*) FROM ${history_table_ref} WHERE version='${V12_VERSION}' AND success;")"
v13_success_count="$(psql_query "SELECT count(*) FROM ${history_table_ref} WHERE version='${V13_VERSION}' AND success;")"
v12_checksum="$(psql_query "SELECT checksum FROM ${history_table_ref} WHERE version='${V12_VERSION}' AND success ORDER BY installed_rank DESC LIMIT 1;")"

if [[ "$v12_success_count" == "0" ]]; then
  echo "[guard_flyway_v2_transient_checksum] OK: v${V12_VERSION} not yet applied."
  exit 0
fi

null_index_exists="$(psql_query "SELECT to_regclass('public.idx_invoices_company_order_status_null') IS NOT NULL;")"
norm_index_predicate="$(psql_query "SELECT COALESCE(pg_get_expr(i.indpred, i.indrelid), '') FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname='public' AND c.relname='idx_invoices_company_order_status_norm' LIMIT 1;")"

has_status_not_null_predicate="f"
if [[ "$norm_index_predicate" == *"status IS NOT NULL"* ]]; then
  has_status_not_null_predicate="t"
fi

if [[ "$v13_success_count" != "0" ]]; then
  if [[ "$null_index_exists" == "f" && "$has_status_not_null_predicate" == "t" ]]; then
    echo "[guard_flyway_v2_transient_checksum] OK: v${V13_VERSION} applied and normalized index predicate is canonical."
    exit 0
  fi

  echo "[guard_flyway_v2_transient_checksum] WARN: v${V13_VERSION} is applied but normalized index shape is non-canonical."
  echo "[guard_flyway_v2_transient_checksum] INFO: null-index-present=${null_index_exists}, status-not-null-predicate=${has_status_not_null_predicate}"
  echo "[guard_flyway_v2_transient_checksum] REMEDIATION:"
  echo "  1) Rebuild idx_invoices_company_order_status_norm with canonical predicate."
  echo "  2) Ensure idx_invoices_company_order_status_null is absent."
  echo "  Example (run during maintenance window):"
  echo "    DROP INDEX CONCURRENTLY IF EXISTS public.idx_invoices_company_order_status_norm;"
  echo "    CREATE INDEX CONCURRENTLY idx_invoices_company_order_status_norm ON public.invoices USING btree (company_id, sales_order_id, upper(trim(status))) WHERE (sales_order_id IS NOT NULL AND status IS NOT NULL);"
  echo "    DROP INDEX CONCURRENTLY IF EXISTS public.idx_invoices_company_order_status_null;"
  exit 3
fi

if [[ "$null_index_exists" == "f" && "$has_status_not_null_predicate" == "f" ]]; then
  echo "[guard_flyway_v2_transient_checksum] WARN: detected transient v${V12_VERSION} schema signature (null index absent, norm predicate lacks status guard) while v${V13_VERSION} is not installed."
  echo "[guard_flyway_v2_transient_checksum] WARN: this state can fail Flyway validate after migration-text convergence."
  echo "[guard_flyway_v2_transient_checksum] INFO: v${V12_VERSION} checksum in history table: ${v12_checksum:-<null>}"
  echo "[guard_flyway_v2_transient_checksum] REMEDIATION:"
  echo "  1) Run Flyway repair for v2 history table."
  echo "  2) Re-run Flyway migrate so v${V13_VERSION}+ forward fixes install."
  echo "  Example:"
  echo "    mvn -B -ntp -f ${ROOT_DIR}/erp-domain/pom.xml org.flywaydb:flyway-maven-plugin:repair \\"
  echo "      -Dflyway.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME} \\"
  echo "      -Dflyway.user=${DB_USER} -Dflyway.password=<redacted> \\"
  echo "      -Dflyway.defaultSchema=${SCHEMA_NAME} \\"
  echo "      -Dflyway.locations=filesystem:${ROOT_DIR}/erp-domain/src/main/resources/db/migration_v2 \\"
  echo "      -Dflyway.table=${HISTORY_TABLE}"
  echo "    mvn -B -ntp -f ${ROOT_DIR}/erp-domain/pom.xml org.flywaydb:flyway-maven-plugin:migrate \\"
  echo "      -Dflyway.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME} \\"
  echo "      -Dflyway.user=${DB_USER} -Dflyway.password=<redacted> \\"
  echo "      -Dflyway.defaultSchema=${SCHEMA_NAME} \\"
  echo "      -Dflyway.locations=filesystem:${ROOT_DIR}/erp-domain/src/main/resources/db/migration_v2 \\"
  echo "      -Dflyway.table=${HISTORY_TABLE}"
  exit 2
fi

if [[ "$null_index_exists" == "t" && "$has_status_not_null_predicate" == "t" ]]; then
  echo "[guard_flyway_v2_transient_checksum] OK: v${V12_VERSION} pre-convergence state is canonical (v${V13_VERSION} pending migrate)."
  echo "[guard_flyway_v2_transient_checksum] INFO: v${V12_VERSION} checksum in history table: ${v12_checksum:-<null>}"
  exit 0
fi

echo "[guard_flyway_v2_transient_checksum] WARN: v${V12_VERSION} state is non-canonical and does not match known transient signature."
echo "[guard_flyway_v2_transient_checksum] INFO: null-index-present=${null_index_exists}, status-not-null-predicate=${has_status_not_null_predicate}"
echo "[guard_flyway_v2_transient_checksum] INFO: v${V12_VERSION} checksum in history table: ${v12_checksum:-<null>}"
exit 2
