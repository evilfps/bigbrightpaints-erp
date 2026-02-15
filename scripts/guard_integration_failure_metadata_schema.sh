#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_ROOT="$ROOT_DIR/erp-domain/src/main/java"
REMEDIATION_COMMAND="bash scripts/guard_integration_failure_metadata_schema.sh"
PRODUCER_PATTERN='logFailure\(AuditEvent\.INTEGRATION_FAILURE'
SCHEMA_PATTERN='IntegrationFailureMetadataSchema\.applyRequiredFields\('
MANUAL_REQUIRED_KEY_PATTERN='put\("failureCode"|put\("errorCategory"|put\("alertRoutingVersion"|put\("alertRoute"'

fail() {
  echo "[guard_integration_failure_metadata_schema] ERROR: $1" >&2
  echo "[guard_integration_failure_metadata_schema] REMEDIATION: run '$REMEDIATION_COMMAND'" >&2
  exit 1
}

[[ -d "$SOURCE_ROOT" ]] || fail "missing source root: $SOURCE_ROOT"

mapfile -t producer_files < <(rg -l "$PRODUCER_PATTERN" "$SOURCE_ROOT")

[[ "${#producer_files[@]}" -gt 0 ]] || fail "no INTEGRATION_FAILURE producers found under $SOURCE_ROOT"

for file in "${producer_files[@]}"; do
  if ! rg -q "$SCHEMA_PATTERN" "$file"; then
    fail "producer $file does not call IntegrationFailureMetadataSchema.applyRequiredFields"
  fi

  if rg -q "$MANUAL_REQUIRED_KEY_PATTERN" "$file"; then
    fail "producer $file still writes required metadata keys manually; use IntegrationFailureMetadataSchema only"
  fi

  if ! rg -U -q "IntegrationFailureMetadataSchema\\.applyRequiredFields\\([\\s\\S]*logFailure\\(AuditEvent\\.INTEGRATION_FAILURE" "$file"; then
    fail "producer $file does not apply schema helper before INTEGRATION_FAILURE log emission"
  fi
done

echo "[guard_integration_failure_metadata_schema] OK"
