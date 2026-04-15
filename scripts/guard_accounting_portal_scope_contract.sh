#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GUARDRAIL_DOC="${ACCOUNTING_PORTAL_SCOPE_GUARDRAIL_DOC:-$ROOT_DIR/docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md}"
PORTAL_DOC="${ACCOUNTING_PORTAL_PORTAL_DOC:-$ROOT_DIR/docs/frontend-portals/accounting/README.md}"
FRONTEND_API_DOC="${ACCOUNTING_PORTAL_FRONTEND_API_DOC:-$ROOT_DIR/docs/frontend-api/README.md}"
ENDPOINT_INVENTORY_DOC="${ACCOUNTING_PORTAL_ENDPOINT_INVENTORY_DOC:-$ROOT_DIR/docs/endpoint-inventory.md}"
REMEDIATION_COMMAND="bash scripts/guard_accounting_portal_scope_contract.sh"
SCOPE_SENTENCE="HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal"

fail() {
  echo "[guard_accounting_portal_scope_contract] ERROR: $1" >&2
  echo "[guard_accounting_portal_scope_contract] REMEDIATION: run '$REMEDIATION_COMMAND'" >&2
  exit 1
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

require_regex_match() {
  local pattern="$1"
  local file="$2"
  local message="$3"
  if command -v rg >/dev/null 2>&1; then
    rg -q -- "$pattern" "$file" || fail "$message"
    return
  fi
  if command -v perl >/dev/null 2>&1; then
    perl -ne 'BEGIN { $pattern = shift @ARGV; $found = 0 } if (m{$pattern}) { $found = 1; exit 0 } END { exit($found ? 0 : 1) }' "$pattern" "$file" || fail "$message"
    return
  fi
  grep -Eq -- "$pattern" "$file" || fail "$message"
}

for path in "$GUARDRAIL_DOC" "$PORTAL_DOC" "$FRONTEND_API_DOC" "$ENDPOINT_INVENTORY_DOC"; do
  [[ -f "$path" ]] || fail "missing required scope contract file: $path"
done

require_literal "$SCOPE_SENTENCE" "$GUARDRAIL_DOC" \
  "missing accounting portal scope invariant in $GUARDRAIL_DOC"
require_literal "$SCOPE_SENTENCE" "$ENDPOINT_INVENTORY_DOC" \
  "missing accounting portal scope invariant in $ENDPOINT_INVENTORY_DOC"

require_literal "Updated canonical portal and frontend API docs for every affected portal." "$GUARDRAIL_DOC" \
  "scope guardrail doc must require canonical portal/API doc updates for scope changes"
require_literal 'Updated `docs/endpoint-inventory.md` module mapping and examples.' "$GUARDRAIL_DOC" \
  "scope guardrail doc must require endpoint inventory updates for scope changes"

require_literal "docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md" "$ENDPOINT_INVENTORY_DOC" \
  "endpoint inventory must reference the scope guardrail doc"
for module in hr purchasing inventory portal reports; do
  require_regex_match "\\| \`$module\` \\| [1-9][0-9]* \\|" "$ENDPOINT_INVENTORY_DOC" \
    "endpoint inventory summary missing required module row with non-zero path count: $module"
done
require_regex_match "\\| \`finished-goods\` \\| [1-9][0-9]* \\|" "$ENDPOINT_INVENTORY_DOC" \
  "endpoint inventory summary missing required module row with non-zero path count: finished-goods"

for endpoint in \
  "/api/v1/hr/employees" \
  "/api/v1/purchasing/purchase-orders" \
  "/api/v1/finished-goods/stock-summary" \
  "/api/v1/reports/inventory-valuation" \
  "/api/v1/portal/finance/ledger" \
  "/api/v1/portal/finance/invoices" \
  "/api/v1/portal/finance/aging"; do
  require_regex_match "^- \\x60[A-Z]+(, [A-Z]+)*\\x60 \\x60$endpoint\\x60\\r?$" "$ENDPOINT_INVENTORY_DOC" \
    "required endpoint evidence missing in endpoint inventory bullets: $endpoint"
done

require_literal "drive request-close, approve-close, and reject-close workflow" "$PORTAL_DOC" \
  "accounting portal README must document maker-checker period-close ownership"
require_literal 'Direct `POST /api/v1/accounting/periods/{periodId}/close` is not a frontend' "$PORTAL_DOC" \
  "accounting portal README must reject direct close wiring"
require_literal "review and import opening stock only after accounting readiness is complete" "$PORTAL_DOC" \
  "accounting portal README must keep opening stock inside accounting portal ownership"

require_literal "**Period close:** frontend must follow maker-checker flow: request close" "$FRONTEND_API_DOC" \
  "frontend API README must document period-close maker-checker flow"
require_literal "**accounting:** COA, journals, reconciliation, period close, reports" "$FRONTEND_API_DOC" \
  "frontend API README must keep accounting portal placement explicit"

echo "[guard_accounting_portal_scope_contract] OK"
