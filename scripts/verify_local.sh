#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPAT_BASH_ENV_BOOTSTRAP="$ROOT_DIR/scripts/bash_env_bootstrap.sh"
if [[ "${BASH_ENV:-}" != "$COMPAT_BASH_ENV_BOOTSTRAP" && -n "${BASH_ENV:-}" ]]; then
  export BBP_CHAINED_BASH_ENV="${BASH_ENV:-}"
  export BBP_CHAINED_BASH_ENV_PARENT_PID="$$"
else
  unset BBP_CHAINED_BASH_ENV
  unset BBP_CHAINED_BASH_ENV_PARENT_PID
fi
export BASH_ENV="$COMPAT_BASH_ENV_BOOTSTRAP"
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

VERIFY_LOCAL_TEST_STRATEGY="${VERIFY_LOCAL_TEST_STRATEGY:-high-signal}"
case "$VERIFY_LOCAL_TEST_STRATEGY" in
  high-signal|full)
    ;;
  *)
    echo "[verify_local] invalid VERIFY_LOCAL_TEST_STRATEGY: $VERIFY_LOCAL_TEST_STRATEGY (expected high-signal or full)" >&2
    exit 2
    ;;
esac

HIGH_SIGNAL_PROFILE="${VERIFY_LOCAL_HIGH_SIGNAL_PROFILE:-gate-core}"
if [[ "$VERIFY_LOCAL_TEST_STRATEGY" == "high-signal" && "$HIGH_SIGNAL_PROFILE" != "gate-core" ]]; then
  echo "[verify_local] invalid VERIFY_LOCAL_HIGH_SIGNAL_PROFILE: $HIGH_SIGNAL_PROFILE (expected gate-core)" >&2
  exit 2
fi

SKIP_RELEASE_DUPLICATE_GUARDS="${VERIFY_LOCAL_SKIP_RELEASE_DUPLICATE_GUARDS:-false}"
if [[ "$SKIP_RELEASE_DUPLICATE_GUARDS" == "true" && "${VERIFY_LOCAL_RELEASE_PREFLIGHT_DONE:-false}" != "true" ]]; then
  echo "[verify_local] ignore release duplicate-guard delegation (VERIFY_LOCAL_RELEASE_PREFLIGHT_DONE=true not set)"
  SKIP_RELEASE_DUPLICATE_GUARDS=false
fi

SKIP_MVN_VERIFY="${VERIFY_LOCAL_SKIP_MVN_VERIFY:-false}"
if [[ "$SKIP_MVN_VERIFY" == "true" && "${VERIFY_LOCAL_SKIP_TESTS:-false}" != "true" ]]; then
  echo "[verify_local] ignore mvn verify delegation (VERIFY_LOCAL_SKIP_TESTS=true not set)"
  SKIP_MVN_VERIFY=false
fi

echo "[verify_local] legacy migration freeze guard"
bash "$ROOT_DIR/scripts/guard_legacy_migration_freeze.sh" --no-range

echo "[verify_local] schema drift scan"
FAIL_ON_FINDINGS=true bash "$ROOT_DIR/scripts/schema_drift_scan.sh" --migration-set "$MIGRATION_SET"

echo "[verify_local] flyway overlap scan (heuristic)"
FAIL_ON_FINDINGS=true bash "$ROOT_DIR/scripts/flyway_overlap_scan.sh" --migration-set "$MIGRATION_SET"

if [[ "$SKIP_RELEASE_DUPLICATE_GUARDS" == "true" ]]; then
  echo "[verify_local] skip orchestrator correlation contract guard (delegated by caller)"
else
  echo "[verify_local] orchestrator correlation contract guard"
  bash "$ROOT_DIR/scripts/guard_orchestrator_correlation_contract.sh"
fi

echo "[verify_local] openapi contract drift guard"
bash "$ROOT_DIR/scripts/guard_openapi_contract_drift.sh"

echo "[verify_local] accounting portal scope contract guard"
bash "$ROOT_DIR/scripts/guard_accounting_portal_scope_contract.sh"

echo "[verify_local] audit trail ownership contract guard"
bash "$ROOT_DIR/scripts/guard_audit_trail_ownership_contract.sh"

if [[ "$MIGRATION_SET" == "v2" ]]; then
  if [[ "$SKIP_RELEASE_DUPLICATE_GUARDS" == "true" ]]; then
    echo "[verify_local] skip payroll account bootstrap contract guard (delegated by caller)"
    echo "[verify_local] skip flyway v2 migration ownership guard (delegated by caller)"
    echo "[verify_local] skip flyway v2 migration ownership fixture matrix (delegated by caller)"
    echo "[verify_local] skip flyway v2 referential contract canary (delegated by caller)"
    echo "[verify_local] skip flyway v2 referential contract fixture matrix (delegated by caller)"
  else
    echo "[verify_local] payroll account bootstrap contract guard"
    bash "$ROOT_DIR/scripts/guard_payroll_account_bootstrap_contract.sh"

    echo "[verify_local] flyway v2 migration ownership guard"
    bash "$ROOT_DIR/scripts/guard_flyway_v2_migration_ownership.sh"

    echo "[verify_local] flyway v2 migration ownership fixture matrix"
    bash "$ROOT_DIR/scripts/guard_flyway_v2_migration_ownership_fixture_matrix.sh"

    echo "[verify_local] flyway v2 referential contract canary"
    bash "$ROOT_DIR/scripts/guard_flyway_v2_referential_contract.sh"

    echo "[verify_local] flyway v2 referential contract fixture matrix"
    bash "$ROOT_DIR/scripts/guard_flyway_v2_referential_contract_fixture_matrix.sh"
  fi
fi

if [[ "$SKIP_RELEASE_DUPLICATE_GUARDS" == "true" ]]; then
  echo "[verify_local] skip flyway guard contract guard (delegated by caller)"
else
  echo "[verify_local] flyway guard contract guard"
  bash "$ROOT_DIR/scripts/guard_flyway_guard_contract.sh"
fi

if [[ "$MIGRATION_SET" == "v2" ]]; then
  ALLOW_GUARD_DB_MISMATCH="${ALLOW_FLYWAY_GUARD_DB_MISMATCH:-false}"
  if [[ -n "${FLYWAY_GUARD_DB_NAME:-}" && -n "${PGDATABASE:-}" && "${FLYWAY_GUARD_DB_NAME}" != "${PGDATABASE}" ]]; then
    if [[ "$ALLOW_GUARD_DB_MISMATCH" != "true" ]]; then
      echo "[verify_local] FLYWAY_GUARD_DB_NAME and PGDATABASE differ. Set ALLOW_FLYWAY_GUARD_DB_MISMATCH=true only for intentional split-target runs." >&2
      exit 4
    fi
    echo "[verify_local] WARNING: FLYWAY_GUARD_DB_NAME and PGDATABASE differ; using FLYWAY_GUARD_DB_NAME due to ALLOW_FLYWAY_GUARD_DB_MISMATCH=true" >&2
  fi

  GUARD_DB_NAME="${FLYWAY_GUARD_DB_NAME:-${PGDATABASE:-}}"
  SKIP_FLYWAY_GUARD="${VERIFY_LOCAL_SKIP_FLYWAY_GUARD:-false}"
  DELEGATED_GUARD_EXECUTED="${VERIFY_LOCAL_GUARD_ALREADY_EXECUTED:-false}"

  if [[ "${REQUIRE_FLYWAY_V2_GUARD:-false}" == "true" && -z "$GUARD_DB_NAME" ]]; then
    echo "[verify_local] FLYWAY_GUARD_DB_NAME/PGDATABASE is required when REQUIRE_FLYWAY_V2_GUARD=true" >&2
    exit 3
  fi

  if [[ "$SKIP_FLYWAY_GUARD" == "true" && "${REQUIRE_FLYWAY_V2_GUARD:-false}" == "true" ]]; then
    echo "[verify_local] ignore flyway v2 transient checksum delegation while REQUIRE_FLYWAY_V2_GUARD=true"
    SKIP_FLYWAY_GUARD=false
  elif [[ "$SKIP_FLYWAY_GUARD" == "true" && "$DELEGATED_GUARD_EXECUTED" != "true" ]]; then
    echo "[verify_local] ignore flyway v2 transient checksum delegation (VERIFY_LOCAL_GUARD_ALREADY_EXECUTED=true not set)"
    SKIP_FLYWAY_GUARD=false
  fi

  if [[ "$SKIP_FLYWAY_GUARD" == "true" && -n "$GUARD_DB_NAME" ]]; then
    echo "[verify_local] skip flyway v2 transient checksum guard (delegated by caller with FLYWAY_GUARD_DB_NAME=$GUARD_DB_NAME)"
  elif [[ -n "$GUARD_DB_NAME" ]]; then
    if [[ -z "${FLYWAY_GUARD_DB_NAME:-}" && -n "${PGDATABASE:-}" ]]; then
      echo "[verify_local] deriving FLYWAY_GUARD_DB_NAME from PGDATABASE"
    fi
    echo "[verify_local] flyway v2 transient checksum guard"
    bash "$ROOT_DIR/scripts/guard_flyway_v2_transient_checksum.sh" "$GUARD_DB_NAME"
  else
    echo "[verify_local] skip flyway v2 transient checksum guard (set FLYWAY_GUARD_DB_NAME or PGDATABASE to enable)"
  fi
fi

echo "[verify_local] time api scan"
bash "$ROOT_DIR/scripts/time_api_scan.sh"

if [[ "$SKIP_MVN_VERIFY" == "true" ]]; then
  echo "[verify_local] skip mvn verify (delegated by caller)"
elif [[ "${VERIFY_LOCAL_SKIP_TESTS:-false}" == "true" ]]; then
  echo "[verify_local] mvn verify (skip tests)"
  (cd "$ROOT_DIR/erp-domain" && mvn "${MVN_ARGS[@]}" -DskipTests -Djacoco.skip=true verify)
elif [[ "$VERIFY_LOCAL_TEST_STRATEGY" == "high-signal" ]]; then
  echo "[verify_local] mvn test (deterministic high-signal lane: $HIGH_SIGNAL_PROFILE)"
  (
    cd "$ROOT_DIR/erp-domain"
    rm -rf target/surefire-reports target/site/jacoco target/jacoco.exec
    mvn "${MVN_ARGS[@]}" -Dsurefire.runOrder=alphabetical -P"$HIGH_SIGNAL_PROFILE" test
  )
else
  echo "[verify_local] mvn verify (full suite)"
  (cd "$ROOT_DIR/erp-domain" && mvn "${MVN_ARGS[@]}" -Dsurefire.runOrder=alphabetical verify)
fi

echo "[verify_local] OK"
