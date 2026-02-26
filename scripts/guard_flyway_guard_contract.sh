#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE_RELEASE_SRC="$ROOT_DIR/scripts/gate_release.sh"
VERIFY_LOCAL_SRC="$ROOT_DIR/scripts/verify_local.sh"

fail() {
  echo "[guard_flyway_guard_contract] FAIL: $1" >&2
  exit 1
}

require_callsite() {
  local file="$1"
  local needle="$2"
  local label="$3"
  if ! grep -Fq "$needle" "$file"; then
    fail "missing callsite for $label ($needle)"
  fi
}

for file in "$GATE_RELEASE_SRC" "$VERIFY_LOCAL_SRC"; do
  [[ -f "$file" ]] || fail "missing script: $file"
done

# Ensure release/verify entrypoints continue invoking this guard.
require_callsite "$GATE_RELEASE_SRC" 'guard_flyway_guard_contract.sh' 'gate_release preflight contract guard'
require_callsite "$VERIFY_LOCAL_SRC" 'guard_flyway_guard_contract.sh' 'verify_local preflight contract guard'
require_callsite "$GATE_RELEASE_SRC" 'guard_flyway_v2_migration_ownership_fixture_matrix.sh' 'gate_release ownership fixture matrix'
require_callsite "$VERIFY_LOCAL_SRC" 'guard_flyway_v2_migration_ownership_fixture_matrix.sh' 'verify_local ownership fixture matrix'
require_callsite "$GATE_RELEASE_SRC" 'guard_flyway_v2_referential_contract.sh' 'gate_release referential contract canary'
require_callsite "$VERIFY_LOCAL_SRC" 'guard_flyway_v2_referential_contract.sh' 'verify_local referential contract canary'
require_callsite "$GATE_RELEASE_SRC" 'guard_flyway_v2_referential_contract_fixture_matrix.sh' 'gate_release referential fixture matrix'
require_callsite "$VERIFY_LOCAL_SRC" 'guard_flyway_v2_referential_contract_fixture_matrix.sh' 'verify_local referential fixture matrix'
require_callsite "$GATE_RELEASE_SRC" 'guard_payroll_account_bootstrap_contract.sh' 'gate_release payroll bootstrap contract guard'
require_callsite "$VERIFY_LOCAL_SRC" 'guard_payroll_account_bootstrap_contract.sh' 'verify_local payroll bootstrap contract guard'
require_callsite "$VERIFY_LOCAL_SRC" 'guard_audit_trail_ownership_contract.sh' 'verify_local audit trail ownership contract guard'
require_callsite "$VERIFY_LOCAL_SRC" 'guard_openapi_contract_drift.sh' 'verify_local openapi contract drift guard'

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

mkdir -p \
  "$TMP_ROOT/scripts" \
  "$TMP_ROOT/bin" \
  "$TMP_ROOT/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite" \
  "$TMP_ROOT/docs/CODE-RED/confidence-suite"

cp "$GATE_RELEASE_SRC" "$TMP_ROOT/scripts/gate_release.sh"
cp "$VERIFY_LOCAL_SRC" "$TMP_ROOT/scripts/verify_local.sh"
chmod +x "$TMP_ROOT/scripts/gate_release.sh" "$TMP_ROOT/scripts/verify_local.sh"

# Prevent recursive invocation when sandboxed verify_local/gate_release call this guard.
cat > "$TMP_ROOT/scripts/guard_flyway_guard_contract.sh" <<'STUB_GUARD'
#!/usr/bin/env bash
set -euo pipefail
exit 0
STUB_GUARD
chmod +x "$TMP_ROOT/scripts/guard_flyway_guard_contract.sh"

# Fast-pass stubs for dependency scripts invoked by gate_release/verify_local.
for stub in \
  guard_legacy_migration_freeze.sh \
  schema_drift_scan.sh \
  flyway_overlap_scan.sh \
  guard_orchestrator_correlation_contract.sh \
  guard_integration_failure_metadata_schema.sh \
  guard_integration_failure_metadata_schema_fixture_matrix.sh \
  guard_openapi_contract_drift.sh \
  guard_accounting_portal_scope_contract.sh \
  guard_audit_trail_ownership_contract.sh \
  guard_payroll_account_bootstrap_contract.sh \
  guard_flyway_v2_migration_ownership.sh \
  guard_flyway_v2_migration_ownership_fixture_matrix.sh \
  guard_flyway_v2_referential_contract.sh \
  guard_flyway_v2_referential_contract_fixture_matrix.sh \
  time_api_scan.sh \
  release_migration_matrix.sh; do
  cat > "$TMP_ROOT/scripts/$stub" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
exit 0
STUB
  chmod +x "$TMP_ROOT/scripts/$stub"
done

# Guard checksum stub: mark invocations so required/delegation behavior can be asserted.
cat > "$TMP_ROOT/scripts/guard_flyway_v2_transient_checksum.sh" <<'STUB_CHECKSUM'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${CONTRACT_MARK_GUARD_FILE:-}" ]]; then
  printf '%s\n' "${1:-}" >> "$CONTRACT_MARK_GUARD_FILE"
fi
exit 0
STUB_CHECKSUM
chmod +x "$TMP_ROOT/scripts/guard_flyway_v2_transient_checksum.sh"

# Python preflight stubs used by gate_release.
for py_stub in validate_test_catalog.py check_flaky_tags.py; do
  cat > "$TMP_ROOT/scripts/$py_stub" <<'STUB_PY'
#!/usr/bin/env python3
import sys
sys.exit(0)
STUB_PY
  chmod +x "$TMP_ROOT/scripts/$py_stub"
done

# Minimal files/dirs referenced by gate_release arguments.
: > "$TMP_ROOT/scripts/test_quarantine.txt"
cat > "$TMP_ROOT/docs/CODE-RED/confidence-suite/TEST_CATALOG.json" <<'JSON'
{}
JSON

# Maven stub so verify_local/gate_release complete instantly in sandbox tests.
cat > "$TMP_ROOT/bin/mvn" <<'STUB_MVN'
#!/usr/bin/env bash
set -euo pipefail
exit 0
STUB_MVN
chmod +x "$TMP_ROOT/bin/mvn"

# External runtime stubs: contract cases must stay hermetic and never touch host Docker/Postgres.
cat > "$TMP_ROOT/bin/docker" <<'STUB_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "${CONTRACT_EXTERNAL_CALL_LOG:-/dev/null}"
exit 99
STUB_DOCKER
chmod +x "$TMP_ROOT/bin/docker"

cat > "$TMP_ROOT/bin/psql" <<'STUB_PSQL'
#!/usr/bin/env bash
set -euo pipefail
printf 'psql %s\n' "$*" >> "${CONTRACT_EXTERNAL_CALL_LOG:-/dev/null}"
exit 99
STUB_PSQL
chmod +x "$TMP_ROOT/bin/psql"

run_case() {
  local name="$1"
  local expected_exit="$2"
  local cmd="$3"
  local out_file="$TMP_ROOT/${name}.out"
  local status=0

  set +e
  (
    cd "$TMP_ROOT"
    env -u PGHOST -u PGPORT -u PGUSER -u PGPASSWORD -u PGDATABASE -u BASH_ENV \
      PATH="$TMP_ROOT/bin:$PATH" \
      bash -c "$cmd"
  ) >"$out_file" 2>&1
  status=$?
  set -e

  if [[ "$status" -ne "$expected_exit" ]]; then
    echo "[guard_flyway_guard_contract] FAIL: case '$name' expected exit $expected_exit, got $status" >&2
    echo "--- $name output ---" >&2
    cat "$out_file" >&2
    exit 1
  fi
}

require_output() {
  local name="$1"
  local needle="$2"
  local out_file="$TMP_ROOT/${name}.out"
  if ! grep -Fq "$needle" "$out_file"; then
    echo "[guard_flyway_guard_contract] FAIL: case '$name' missing output marker: $needle" >&2
    echo "--- $name output ---" >&2
    cat "$out_file" >&2
    exit 1
  fi
}

# Case 1: verify_local fails closed on DB mismatch by default.
run_case "verify_mismatch_fail_closed" 4 \
  'MIGRATION_SET=v2 FLYWAY_GUARD_DB_NAME=guard_db PGDATABASE=other_db VERIFY_LOCAL_SKIP_TESTS=true ./scripts/verify_local.sh'
require_output "verify_mismatch_fail_closed" "FLYWAY_GUARD_DB_NAME and PGDATABASE differ."

# Case 2: verify_local allows mismatch only with explicit override.
run_case "verify_mismatch_allow" 0 \
  'MIGRATION_SET=v2 ALLOW_FLYWAY_GUARD_DB_MISMATCH=true FLYWAY_GUARD_DB_NAME=guard_db PGDATABASE=other_db VERIFY_LOCAL_SKIP_TESTS=true ./scripts/verify_local.sh'

# Case 3: required mode ignores delegated skip and still executes checksum guard.
run_case "verify_required_ignores_skip" 0 \
  'rm -f .guard_calls && MIGRATION_SET=v2 REQUIRE_FLYWAY_V2_GUARD=true VERIFY_LOCAL_SKIP_FLYWAY_GUARD=true VERIFY_LOCAL_GUARD_ALREADY_EXECUTED=true FLYWAY_GUARD_DB_NAME=guard_db VERIFY_LOCAL_SKIP_TESTS=true CONTRACT_MARK_GUARD_FILE=.guard_calls ./scripts/verify_local.sh && test -s .guard_calls'

# Case 4: skip delegation without execution marker is ignored and guard still runs.
run_case "verify_skip_without_marker" 0 \
  'rm -f .guard_calls && MIGRATION_SET=v2 VERIFY_LOCAL_SKIP_FLYWAY_GUARD=true VERIFY_LOCAL_GUARD_ALREADY_EXECUTED=false FLYWAY_GUARD_DB_NAME=guard_db VERIFY_LOCAL_SKIP_TESTS=true CONTRACT_MARK_GUARD_FILE=.guard_calls ./scripts/verify_local.sh && test -s .guard_calls'

# Case 5: delegated skip with execution marker suppresses re-running checksum guard.
run_case "verify_skip_with_marker" 0 \
  'rm -f .guard_calls && MIGRATION_SET=v2 VERIFY_LOCAL_SKIP_FLYWAY_GUARD=true VERIFY_LOCAL_GUARD_ALREADY_EXECUTED=true FLYWAY_GUARD_DB_NAME=guard_db VERIFY_LOCAL_SKIP_TESTS=true CONTRACT_MARK_GUARD_FILE=.guard_calls ./scripts/verify_local.sh && test ! -s .guard_calls'

# Case 6: gate_release fails closed on mismatch by default.
run_case "release_mismatch_fail_closed" 4 \
  'GATE_REQUIRE_CANONICAL_BASE=false FLYWAY_GUARD_DB_NAME=guard_db PGDATABASE=other_db ./scripts/gate_release.sh'
require_output "release_mismatch_fail_closed" "FLYWAY_GUARD_DB_NAME and PGDATABASE differ."

# Case 7: gate_release propagates allow/delegation flags to verify_local call.
mv "$TMP_ROOT/scripts/verify_local.sh" "$TMP_ROOT/scripts/verify_local.real.sh"
cat > "$TMP_ROOT/scripts/verify_local.sh" <<'STUB_VERIFY_WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
printf '[contract] wrapper_env allow=%s guard=%s skip=%s executed=%s\n' \
  "${ALLOW_FLYWAY_GUARD_DB_MISMATCH:-}" \
  "${FLYWAY_GUARD_DB_NAME:-}" \
  "${VERIFY_LOCAL_SKIP_FLYWAY_GUARD:-}" \
  "${VERIFY_LOCAL_GUARD_ALREADY_EXECUTED:-}"
[[ "${ALLOW_FLYWAY_GUARD_DB_MISMATCH:-}" == "true" ]] || exit 91
[[ "${FLYWAY_GUARD_DB_NAME:-}" == "guard_db" ]] || exit 92
[[ "${VERIFY_LOCAL_SKIP_FLYWAY_GUARD:-}" == "true" ]] || exit 93
[[ "${VERIFY_LOCAL_GUARD_ALREADY_EXECUTED:-}" == "true" ]] || exit 94
exit 0
STUB_VERIFY_WRAPPER
chmod +x "$TMP_ROOT/scripts/verify_local.sh"

run_case "release_allow_propagation" 0 \
  'rm -f .external_calls && GATE_REQUIRE_CANONICAL_BASE=false AUTO_START_GATE_RELEASE_PG=false ALLOW_FLYWAY_GUARD_DB_MISMATCH=true FLYWAY_GUARD_DB_NAME=guard_db PGDATABASE=other_db CONTRACT_EXTERNAL_CALL_LOG=.external_calls ./scripts/gate_release.sh && test ! -s .external_calls'
require_output "release_allow_propagation" "[contract] wrapper_env allow=true guard=guard_db skip=true executed=true"

echo "[guard_flyway_guard_contract] OK"
