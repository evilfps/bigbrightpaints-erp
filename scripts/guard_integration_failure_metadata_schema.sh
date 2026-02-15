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
  if rg -q "$MANUAL_REQUIRED_KEY_PATTERN" "$file"; then
    fail "producer $file still writes required metadata keys manually; use IntegrationFailureMetadataSchema only"
  fi

  mapfile -t helper_lines < <(rg -n "$SCHEMA_PATTERN" "$file" | cut -d: -f1)
  [[ "${#helper_lines[@]}" -gt 0 ]] || fail "producer $file does not call IntegrationFailureMetadataSchema.applyRequiredFields"

  mapfile -t log_lines < <(rg -n "$PRODUCER_PATTERN" "$file" | cut -d: -f1)
  [[ "${#log_lines[@]}" -gt 0 ]] || fail "producer pattern matched file scan but no log lines resolved for $file"

  for log_line in "${log_lines[@]}"; do
    has_preceding_schema=false
    for helper_line in "${helper_lines[@]}"; do
      if (( helper_line <= log_line )); then
        has_preceding_schema=true
      else
        break
      fi
    done

    if [[ "$has_preceding_schema" != true ]]; then
      fail "producer $file has INTEGRATION_FAILURE log at line $log_line without preceding schema helper"
    fi
  done
done

echo "[guard_integration_failure_metadata_schema] OK"
