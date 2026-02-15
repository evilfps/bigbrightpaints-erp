#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC="${AUDIT_TRAIL_OWNERSHIP_DOC:-$ROOT_DIR/docs/AUDIT_TRAIL_OWNERSHIP.md}"
ACCOUNTING_SERVICE="${AUDIT_TRAIL_ACCOUNTING_SERVICE:-$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java}"
APP_CONFIG_MAIN="${AUDIT_TRAIL_APP_CONFIG_MAIN:-$ROOT_DIR/erp-domain/src/main/resources/application.yml}"
APP_CONFIG_TEST="${AUDIT_TRAIL_APP_CONFIG_TEST:-$ROOT_DIR/erp-domain/src/main/resources/application-test.yml}"
APP_CONFIG_IT_TEST="${AUDIT_TRAIL_APP_CONFIG_IT_TEST:-$ROOT_DIR/erp-domain/src/test/resources/application-test.yml}"
REMEDIATION_COMMAND="bash scripts/guard_audit_trail_ownership_contract.sh"

fail() {
  echo "[guard_audit_trail_ownership_contract] ERROR: $1" >&2
  echo "[guard_audit_trail_ownership_contract] REMEDIATION: run '$REMEDIATION_COMMAND'" >&2
  exit 1
}

require_legacy_summary_enabled_value() {
  local config_path="$1"
  local expected="$2"
  awk -v expected="$expected" '
    BEGIN {
      in_block = 0
      block_indent = -1
      found = 0
    }
    /^[[:space:]]*legacy-summary-events:[[:space:]]*$/ {
      in_block = 1
      match($0, /[^ ]/)
      block_indent = RSTART - 1
      next
    }
    {
      if (in_block) {
        if ($0 ~ /^[[:space:]]*$/ || $0 ~ /^[[:space:]]*#/) {
          next
        }
        match($0, /[^ ]/)
        current_indent = RSTART - 1
        if (current_indent <= block_indent) {
          in_block = 0
        }
      }
      if (in_block && $0 ~ "^[[:space:]]*enabled:[[:space:]]*" expected "[[:space:]]*$") {
        found = 1
        exit
      }
    }
    END {
      exit(found ? 0 : 1)
    }
  ' "$config_path"
}

for path in "$DOC" "$ACCOUNTING_SERVICE" "$APP_CONFIG_MAIN" "$APP_CONFIG_TEST" "$APP_CONFIG_IT_TEST"; do
  [[ -f "$path" ]] || fail "missing required file: $path"
done

for required in \
  "Audit Trail Ownership and De-dup Contract" \
  "Canonical Audit Surfaces" \
  "De-dup Policy" \
  "Accounting journal/reversal/settlement summary events are captured by AccountingEventStore" \
  "erp.audit.accounting.legacy-summary-events.enabled=false" \
  "Change-Control Rule"; do
  rg -q --fixed-strings "$required" "$DOC" \
    || fail "ownership doc is missing required contract phrase: $required"
done

rg -q 'erp\.audit\.accounting\.legacy-summary-events\.enabled' "$ACCOUNTING_SERVICE" \
  || fail "accounting service is missing legacy-summary-events property contract"
rg -q 'shouldEmitLegacyAccountingSummaryEvent' "$ACCOUNTING_SERVICE" \
  || fail "accounting service is missing legacy-summary dedup guard method"

if rg -n 'auditService\.logSuccess\(AuditEvent\.(JOURNAL_ENTRY_POSTED|JOURNAL_ENTRY_REVERSED|SETTLEMENT_RECORDED)' "$ACCOUNTING_SERVICE" >/dev/null; then
  fail "direct legacy summary writes for posted/reversed/settlement events are forbidden in accounting service"
fi

rg -q 'legacy-summary-events:' "$APP_CONFIG_MAIN" \
  || fail "missing audit dedup config block in $APP_CONFIG_MAIN"
require_legacy_summary_enabled_value "$APP_CONFIG_MAIN" "false" \
  || fail "production/default config must disable legacy accounting summary audit writes by default in $APP_CONFIG_MAIN"

for cfg in "$APP_CONFIG_TEST" "$APP_CONFIG_IT_TEST"; do
  rg -q 'legacy-summary-events:' "$cfg" \
    || fail "missing audit dedup config block in $cfg"
  require_legacy_summary_enabled_value "$cfg" "true" \
    || fail "test config must keep legacy summary audit writes enabled for compatibility assertions in $cfg"
done

echo "[guard_audit_trail_ownership_contract] OK"
