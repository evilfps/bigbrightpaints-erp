#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GUARDRAIL_DOC="${ACCOUNTING_PORTAL_SCOPE_GUARDRAIL_DOC:-$ROOT_DIR/docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md}"
ENDPOINT_MAP_DOC="${ACCOUNTING_PORTAL_ENDPOINT_MAP_DOC:-$ROOT_DIR/docs/accounting-portal-endpoint-map.md}"
HANDOFF_DOC="${ACCOUNTING_PORTAL_HANDOFF_DOC:-$ROOT_DIR/docs/accounting-portal-frontend-engineer-handoff.md}"
ENDPOINT_INVENTORY_DOC="${ACCOUNTING_PORTAL_ENDPOINT_INVENTORY_DOC:-$ROOT_DIR/docs/endpoint-inventory.md}"
REMEDIATION_COMMAND="bash scripts/guard_accounting_portal_scope_contract.sh"
SCOPE_SENTENCE="HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal"

fail() {
  echo "[guard_accounting_portal_scope_contract] ERROR: $1" >&2
  echo "[guard_accounting_portal_scope_contract] REMEDIATION: run '$REMEDIATION_COMMAND'" >&2
  exit 1
}

escape_regex() {
  printf '%s' "$1" | sed -E 's/[][(){}.^$*+?|\\]/\\&/g'
}

has_regex_match() {
  local pattern="$1"
  local file="$2"
  if command -v rg >/dev/null 2>&1; then
    rg -q -- "$pattern" "$file"
    return
  fi
  if command -v perl >/dev/null 2>&1; then
    SCOPE_PATTERN="$pattern" perl -ne 'BEGIN { $p = $ENV{"SCOPE_PATTERN"}; $matched = 0 } $matched = 1 if /$p/; END { exit($matched ? 0 : 1) }' "$file"
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

assert_endpoint_contract() {
  local module="$1"
  local endpoint="$2"
  local escaped_endpoint
  escaped_endpoint="$(escape_regex "$endpoint")"

  require_regex_match "^- \\x60[A-Z]+(, [A-Z]+)*\\x60 \\x60$escaped_endpoint\\x60\\r?$" "$ENDPOINT_INVENTORY_DOC" \
    "required $module endpoint evidence missing in endpoint inventory bullets ($endpoint) in $ENDPOINT_INVENTORY_DOC"

  require_regex_match "^\\| \\x60[A-Z]+(, [A-Z]+)* $escaped_endpoint\\x60 \\|([^|]*\\|)*\\r?$" "$ENDPOINT_MAP_DOC" \
    "required $module endpoint evidence missing in endpoint map rows ($endpoint) in $ENDPOINT_MAP_DOC"

  require_regex_match "^\\| \\x60[^|]+\\x60 \\| [A-Z]+ \\| \\x60$escaped_endpoint\\x60 \\|([^|]*\\|)*\\r?$" "$HANDOFF_DOC" \
    "required $module endpoint evidence missing in handoff rows ($endpoint) in $HANDOFF_DOC"
}

for path in "$GUARDRAIL_DOC" "$ENDPOINT_MAP_DOC" "$HANDOFF_DOC" "$ENDPOINT_INVENTORY_DOC"; do
  [[ -f "$path" ]] || fail "missing required file: $path"
done

for path in "$GUARDRAIL_DOC" "$ENDPOINT_MAP_DOC" "$HANDOFF_DOC" "$ENDPOINT_INVENTORY_DOC"; do
  require_literal "$SCOPE_SENTENCE" "$path" "missing accounting portal scope invariant in $path"
done

for heading in \
  "## Purchasing & Payables" \
  "## Inventory & Costing" \
  "## HR & Payroll" \
  "## Reports & Reconciliation"; do
  require_literal "$heading" "$ENDPOINT_MAP_DOC" \
    "accounting endpoint map missing required domain heading: $heading"
  require_literal "$heading" "$HANDOFF_DOC" \
    "accounting frontend handoff missing required domain heading: $heading"
done

for module in hr purchasing inventory reports; do
  require_regex_match "\\| \`$module\` \\| [1-9][0-9]* \\|" "$ENDPOINT_INVENTORY_DOC" \
    "endpoint inventory summary missing required module row with non-zero path count: $module"
done

for required in \
  "hr:/api/v1/hr/employees" \
  "purchasing:/api/v1/purchasing/purchase-orders" \
  "inventory:/api/v1/finished-goods/stock-summary" \
  "reports:/api/v1/reports/inventory-valuation"; do
  module="${required%%:*}"
  endpoint="${required#*:}"
  assert_endpoint_contract "$module" "$endpoint"
done

for controller in \
  "### purchasing-workflow-controller" \
  "### raw-material-controller" \
  "### inventory-adjustment-controller" \
  "### hr-controller" \
  "### hr-payroll-controller" \
  "### report-controller"; do
  require_literal "$controller" "$ENDPOINT_MAP_DOC" \
    "accounting endpoint map missing required controller section: $controller"
done

require_literal "docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md" "$ENDPOINT_MAP_DOC" \
  "accounting endpoint map must reference the scope guardrail doc"
require_literal "docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md" "$ENDPOINT_INVENTORY_DOC" \
  "endpoint inventory must reference the scope guardrail doc"

require_literal "Change-Control Rule" "$GUARDRAIL_DOC" \
  "scope guardrail doc must keep change-control section"
require_literal "Updated portal endpoint map and frontend handoff docs" "$GUARDRAIL_DOC" \
  "scope guardrail doc must require portal-map + handoff updates for scope changes"
require_regex_match 'Updated `?docs/endpoint-inventory\.md`? module mapping and examples' "$GUARDRAIL_DOC" \
  "scope guardrail doc must require endpoint inventory updates for scope changes"

echo "[guard_accounting_portal_scope_contract] OK"
