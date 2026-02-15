#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_GUARD="$ROOT_DIR/scripts/guard_integration_failure_metadata_schema.sh"

fail() {
  echo "[guard_integration_failure_metadata_schema_fixture_matrix] FAIL: $1" >&2
  exit 1
}

[[ -f "$SOURCE_GUARD" ]] || fail "missing source guard script: $SOURCE_GUARD"

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

run_case() {
  local name="$1"
  local expected_exit="$2"
  local java_source="$3"
  local case_root="$TMP_ROOT/$name"
  local java_file="$case_root/erp-domain/src/main/java/com/example/Fixture.java"
  local out_file="$case_root/output.log"
  local status=0

  mkdir -p \
    "$case_root/scripts" \
    "$case_root/erp-domain/src/main/java/com/example"

  cp "$SOURCE_GUARD" "$case_root/scripts/guard_integration_failure_metadata_schema.sh"
  chmod +x "$case_root/scripts/guard_integration_failure_metadata_schema.sh"

  cat > "$java_file" <<JAVA
$java_source
JAVA

  set +e
  (cd "$case_root" && bash scripts/guard_integration_failure_metadata_schema.sh) >"$out_file" 2>&1
  status=$?
  set -e

  if [[ "$status" -ne "$expected_exit" ]]; then
    echo "[guard_integration_failure_metadata_schema_fixture_matrix] FAIL: case '$name' expected exit $expected_exit, got $status" >&2
    echo "--- $name output ---" >&2
    cat "$out_file" >&2
    exit 1
  fi
}

run_case "single_line_pass" 0 "$(cat <<'JAVA'
package com.example;

class Fixture {
    private void goodSingleLine() {
        IntegrationFailureMetadataSchema.applyRequiredFields(metadata, "X", "Y", "Z", "SEV3_TICKET");
        auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
    }
}
JAVA
)"

run_case "multiline_call_pass" 0 "$(cat <<'JAVA'
package com.example;

class Fixture {
    private void goodMultiline() {
        IntegrationFailureMetadataSchema.applyRequiredFields(metadata, "X", "Y", "Z", "SEV3_TICKET");
        auditService.logFailure(
                AuditEvent.INTEGRATION_FAILURE,
                metadata
        )
        ;
    }
}
JAVA
)"

run_case "helper_in_other_method_fail" 1 "$(cat <<'JAVA'
package com.example;

class Fixture {
    private void helperOnly() {
        IntegrationFailureMetadataSchema.applyRequiredFields(metadata, "X", "Y", "Z", "SEV3_TICKET");
    }

    private void logWithoutHelper() {
        auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
    }
}
JAVA
)"

run_case "manual_required_key_fail" 1 "$(cat <<'JAVA'
package com.example;

class Fixture {
    private void manualWrite() {
        metadata.put("failureCode", "SETTLEMENT_OPERATION_FAILED");
        IntegrationFailureMetadataSchema.applyRequiredFields(metadata, "X", "Y", "Z", "SEV3_TICKET");
        auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
    }
}
JAVA
)"

run_case "multi_log_single_helper_pass" 0 "$(cat <<'JAVA'
package com.example;

class Fixture {
    private void twoLogsOneHelper() {
        IntegrationFailureMetadataSchema.applyRequiredFields(metadata, "X", "Y", "Z", "SEV3_TICKET");
        auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
        auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
    }
}
JAVA
)"

run_case "static_import_token_pass" 0 "$(cat <<'JAVA'
package com.example;

import static com.bigbrightpaints.erp.core.audit.AuditEvent.INTEGRATION_FAILURE;

class Fixture {
    private void staticImportPath() {
        IntegrationFailureMetadataSchema.applyRequiredFields(metadata, "X", "Y", "Z", "SEV3_TICKET");
        auditService.logFailure(INTEGRATION_FAILURE, metadata);
    }
}
JAVA
)"

run_case "static_import_token_fail" 1 "$(cat <<'JAVA'
package com.example;

import static com.bigbrightpaints.erp.core.audit.AuditEvent.INTEGRATION_FAILURE;

class Fixture {
    private void staticImportWithoutHelper() {
        auditService.logFailure(INTEGRATION_FAILURE, metadata);
    }
}
JAVA
)"

echo "[guard_integration_failure_metadata_schema_fixture_matrix] OK"
