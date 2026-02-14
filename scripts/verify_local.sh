#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATION_SET="${MIGRATION_SET:-v2}"
MVN_ARGS=(-B -ntp)

case "$MIGRATION_SET" in
  v2)
    MVN_ARGS+=("-Dspring.profiles.active=test,flyway-v2")
    MVN_ARGS+=("-Dspring.flyway.locations=classpath:db/migration_v2")
    MVN_ARGS+=("-Dspring.flyway.table=flyway_schema_history_v2")
    ;;
  v1)
    MVN_ARGS+=("-Dspring.profiles.active=test")
    MVN_ARGS+=("-Dspring.flyway.locations=classpath:db/migration")
    MVN_ARGS+=("-Dspring.flyway.table=flyway_schema_history")
    ;;
  *)
    echo "[verify_local] invalid MIGRATION_SET: $MIGRATION_SET (expected v1 or v2)" >&2
    exit 2
    ;;
esac

echo "[verify_local] root=$ROOT_DIR"
echo "[verify_local] migration_set=$MIGRATION_SET"

echo "[verify_local] legacy migration freeze guard"
bash "$ROOT_DIR/scripts/guard_legacy_migration_freeze.sh" --no-range

echo "[verify_local] schema drift scan"
FAIL_ON_FINDINGS=true bash "$ROOT_DIR/scripts/schema_drift_scan.sh" --migration-set "$MIGRATION_SET"

echo "[verify_local] flyway overlap scan (heuristic)"
FAIL_ON_FINDINGS=true bash "$ROOT_DIR/scripts/flyway_overlap_scan.sh" --migration-set "$MIGRATION_SET"

echo "[verify_local] orchestrator correlation contract guard"
bash "$ROOT_DIR/scripts/guard_orchestrator_correlation_contract.sh"

echo "[verify_local] accounting portal scope contract guard"
bash "$ROOT_DIR/scripts/guard_accounting_portal_scope_contract.sh"

if [[ "$MIGRATION_SET" == "v2" ]]; then
  GUARD_DB_NAME="${FLYWAY_GUARD_DB_NAME:-${PGDATABASE:-}}"
  if [[ "${VERIFY_LOCAL_SKIP_FLYWAY_GUARD:-false}" == "true" ]]; then
    echo "[verify_local] skip flyway v2 transient checksum guard (delegated by caller)"
  elif [[ -n "$GUARD_DB_NAME" ]]; then
    echo "[verify_local] flyway v2 transient checksum guard"
    bash "$ROOT_DIR/scripts/guard_flyway_v2_transient_checksum.sh" "$GUARD_DB_NAME"
  elif [[ "${REQUIRE_FLYWAY_V2_GUARD:-false}" == "true" ]]; then
    echo "[verify_local] FLYWAY_GUARD_DB_NAME/PGDATABASE is required when REQUIRE_FLYWAY_V2_GUARD=true" >&2
    exit 3
  else
    echo "[verify_local] skip flyway v2 transient checksum guard (set FLYWAY_GUARD_DB_NAME or PGDATABASE to enable)"
  fi
fi

echo "[verify_local] time api scan"
bash "$ROOT_DIR/scripts/time_api_scan.sh"

if [[ "${VERIFY_LOCAL_SKIP_TESTS:-false}" == "true" ]]; then
  echo "[verify_local] mvn verify (skip tests)"
  (cd "$ROOT_DIR/erp-domain" && mvn "${MVN_ARGS[@]}" -DskipTests -Djacoco.skip=true verify)
else
  echo "[verify_local] mvn verify"
  (cd "$ROOT_DIR/erp-domain" && mvn "${MVN_ARGS[@]}" verify)
fi

echo "[verify_local] OK"
