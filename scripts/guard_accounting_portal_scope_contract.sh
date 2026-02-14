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

assert_endpoint_contract() {
  local module="$1"
  local endpoint="$2"

  rg -q -- "^- \\x60[A-Z, ]+\\x60 \\x60$endpoint\\x60$" "$ENDPOINT_INVENTORY_DOC" \
    || fail "required $module endpoint evidence missing in endpoint inventory bullets ($endpoint) in $ENDPOINT_INVENTORY_DOC"

  rg -q -- "^\\| \\x60[A-Z, ]+ $endpoint\\x60 \\|" "$ENDPOINT_MAP_DOC" \
    || fail "required $module endpoint evidence missing in endpoint map rows ($endpoint) in $ENDPOINT_MAP_DOC"

  rg -q -- "^\\| \\x60[^|]+\\x60 \\| [A-Z]+ \\| \\x60$endpoint\\x60 \\|" "$HANDOFF_DOC" \
    || fail "required $module endpoint evidence missing in handoff rows ($endpoint) in $HANDOFF_DOC"
}

for path in "$GUARDRAIL_DOC" "$ENDPOINT_MAP_DOC" "$HANDOFF_DOC" "$ENDPOINT_INVENTORY_DOC"; do
  [[ -f "$path" ]] || fail "missing required file: $path"
done

for path in "$GUARDRAIL_DOC" "$ENDPOINT_MAP_DOC" "$HANDOFF_DOC" "$ENDPOINT_INVENTORY_DOC"; do
  rg -q "$SCOPE_SENTENCE" "$path" || fail "missing accounting portal scope invariant in $path"
done

for heading in \
  "## Purchasing & Payables" \
  "## Inventory & Costing" \
  "## HR & Payroll" \
  "## Reports & Reconciliation"; do
  rg -q "$heading" "$ENDPOINT_MAP_DOC" \
    || fail "accounting endpoint map missing required domain heading: $heading"
  rg -q "$heading" "$HANDOFF_DOC" \
    || fail "accounting frontend handoff missing required domain heading: $heading"
done

for module in hr purchasing inventory reports; do
  rg -q "\\| \`$module\` \\| [1-9][0-9]* \\|" "$ENDPOINT_INVENTORY_DOC" \
    || fail "endpoint inventory summary missing required module row with non-zero path count: $module"
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
  rg -q "$controller" "$ENDPOINT_MAP_DOC" \
    || fail "accounting endpoint map missing required controller section: $controller"
done

rg -q "docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md" "$ENDPOINT_MAP_DOC" \
  || fail "accounting endpoint map must reference the scope guardrail doc"
rg -q "docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md" "$ENDPOINT_INVENTORY_DOC" \
  || fail "endpoint inventory must reference the scope guardrail doc"

rg -q "Change-Control Rule" "$GUARDRAIL_DOC" \
  || fail "scope guardrail doc must keep change-control section"
rg -q "Updated portal endpoint map and frontend handoff docs" "$GUARDRAIL_DOC" \
  || fail "scope guardrail doc must require portal-map + handoff updates for scope changes"
rg -q 'Updated `?docs/endpoint-inventory\.md`? module mapping and examples' "$GUARDRAIL_DOC" \
  || fail "scope guardrail doc must require endpoint inventory updates for scope changes"

echo "[guard_accounting_portal_scope_contract] OK"
