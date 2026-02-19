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

has_regex_match() {
  local pattern="$1"
  local file="$2"
  if command -v rg >/dev/null 2>&1; then
    rg -q -- "$pattern" "$file"
    return
  fi
  if command -v perl >/dev/null 2>&1; then
    AUDIT_PATTERN="$pattern" perl -ne 'BEGIN { $p = $ENV{"AUDIT_PATTERN"}; $matched = 0 } $matched = 1 if /$p/; END { exit($matched ? 0 : 1) }' "$file"
    return
  fi
  grep -Eq -- "$pattern" "$file"
}

require_regex_match() {
  local pattern="$1"
  local file="$2"
  local message="$3"
  has_regex_match "$pattern" "$file" || fail "$message"
}

require_literal() {
  local text="$1"
  local file="$2"
  local message="$3"
  if command -v rg >/dev/null 2>&1; then
    rg -q --fixed-strings -- "$text" "$file" || fail "$message"
    return
  fi
  grep -Fq -- "$text" "$file" || fail "$message"
}

for path in "$DOC" "$ACCOUNTING_SERVICE" "$APP_CONFIG_MAIN" "$APP_CONFIG_TEST" "$APP_CONFIG_IT_TEST"; do
  [[ -f "$path" ]] || fail "missing required file: $path"
done

for required in \
  "Audit Trail Ownership and De-dup Contract" \
  "Canonical Audit Surfaces" \
  "De-dup Policy" \
  "Accounting journal/reversal/settlement summary events are captured by AccountingEventStore" \
  "Legacy summary success writes for these events in \`AuditService\` are fully decommissioned (not toggle-controlled)." \
  "No profile may re-enable legacy summary success writes" \
  "Change-Control Rule"; do
  require_literal "$required" "$DOC" \
    "ownership doc is missing required contract phrase: $required"
done

if has_regex_match 'erp\.audit\.accounting\.legacy-summary-events\.enabled' "$ACCOUNTING_SERVICE"; then
  fail "accounting service still references removed legacy-summary-events property toggle"
fi
if has_regex_match 'legacyAccountingSummaryEventsEnabled' "$ACCOUNTING_SERVICE"; then
  fail "accounting service still contains removed legacy summary compatibility field"
fi
require_regex_match 'shouldEmitAuditServiceSuccessEvent' "$ACCOUNTING_SERVICE" \
  "accounting service is missing audit success suppression guard"
for event in JOURNAL_ENTRY_POSTED JOURNAL_ENTRY_REVERSED SETTLEMENT_RECORDED; do
  require_regex_match "event != AuditEvent\\.${event}" "$ACCOUNTING_SERVICE" \
    "accounting service suppression guard is missing event filter for ${event}"
done

require_no_direct_legacy_summary_writes() {
  local source_root="$1"
  if command -v rg >/dev/null 2>&1; then
    if rg -n --glob '*.java' 'auditService\.logSuccess\(AuditEvent\.(JOURNAL_ENTRY_POSTED|JOURNAL_ENTRY_REVERSED|SETTLEMENT_RECORDED)' "$source_root" >/dev/null; then
      fail "direct legacy summary writes for posted/reversed/settlement events are forbidden in tracked source tree: $source_root"
    fi
    return
  fi
  if grep -R -n -E --include='*.java' 'auditService\.logSuccess\(AuditEvent\.(JOURNAL_ENTRY_POSTED|JOURNAL_ENTRY_REVERSED|SETTLEMENT_RECORDED)' "$source_root" >/dev/null; then
    fail "direct legacy summary writes for posted/reversed/settlement events are forbidden in tracked source tree: $source_root"
  fi
}

require_no_direct_legacy_summary_writes "$ROOT_DIR/erp-domain/src/main/java"

for cfg in "$APP_CONFIG_TEST" "$APP_CONFIG_IT_TEST"; do
  if has_regex_match 'legacy-summary-events:' "$cfg"; then
    fail "legacy summary toggle block must be absent in $cfg"
  fi
done
if has_regex_match 'legacy-summary-events:' "$APP_CONFIG_MAIN"; then
  fail "legacy summary toggle block must be absent in $APP_CONFIG_MAIN"
fi

echo "[guard_audit_trail_ownership_contract] OK"
