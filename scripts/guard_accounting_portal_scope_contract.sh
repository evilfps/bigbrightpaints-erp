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

section_contains_literal() {
  local heading="$1"
  local needle="$2"
  local file="$3"
  awk -v heading="$heading" -v needle="$needle" '
    $0 == heading { in_section = 1; next }
    in_section && /^### / { exit(found ? 0 : 1) }
    in_section && index($0, needle) { found = 1 }
    END { exit(found ? 0 : 1) }
  ' "$file"
}

section_line_contains_literals() {
  local heading="$1"
  local first="$2"
  local second="$3"
  local file="$4"
  awk -v heading="$heading" -v first="$first" -v second="$second" '
    $0 == heading { in_section = 1; next }
    in_section && /^### / { exit(found ? 0 : 1) }
    in_section && index($0, first) && index($0, second) { found = 1 }
    END { exit(found ? 0 : 1) }
  ' "$file"
}

require_section_literal() {
  local heading="$1"
  local needle="$2"
  local file="$3"
  local message="$4"
  section_contains_literal "$heading" "$needle" "$file" || fail "$message"
}

forbid_section_literal() {
  local heading="$1"
  local needle="$2"
  local file="$3"
  local message="$4"
  if section_contains_literal "$heading" "$needle" "$file"; then
    fail "$message"
  fi
}

forbid_section_line_literals() {
  local heading="$1"
  local first="$2"
  local second="$3"
  local file="$4"
  local message="$5"
  if section_line_contains_literals "$heading" "$first" "$second" "$file"; then
    fail "$message"
  fi
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
  if [[ ! -f "$path" ]]; then
    fail "missing required scope contract file: $path"
  fi
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

for module in hr purchasing inventory reports portal; do
  require_regex_match "\\| \`$module\` \\| [1-9][0-9]* \\|" "$ENDPOINT_INVENTORY_DOC" \
    "endpoint inventory summary missing required module row with non-zero path count: $module"
done

for required in \
  "hr:/api/v1/hr/employees" \
  "purchasing:/api/v1/purchasing/purchase-orders" \
  "inventory:/api/v1/finished-goods/stock-summary" \
  "reports:/api/v1/reports/inventory-valuation" \
  "portal:/api/v1/portal/finance/ledger" \
  "portal:/api/v1/portal/finance/invoices" \
  "portal:/api/v1/portal/finance/aging"; do
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
  "### report-controller" \
  "### portal-finance-controller"; do
  require_literal "$controller" "$ENDPOINT_MAP_DOC" \
    "accounting endpoint map missing required controller section: $controller"
done

require_literal "Portal finance drill-ins stay on \`/api/v1/portal/finance/*\` for admin/accounting users; dealer self-service remains on \`/api/v1/dealer-portal/{ledger,invoices,aging}\`, and retired shared/legacy aliases stay out of the portal." "$ENDPOINT_MAP_DOC" \
  "accounting endpoint map must document the canonical internal-vs-dealer finance split"

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

require_literal "admin-only deprecated exports and must not be treated as required APIs for new accountant-owned UI flows" "$HANDOFF_DOC" \
  "handoff must explicitly classify legacy audit digest endpoints as admin-only deprecated exports"

forbid_section_line_literals "### \`/accounting/ar/invoices\`" "- Required API calls" "invoiceDownloadInvoicePdf" "$HANDOFF_DOC" \
  "invoice route must not list admin-only PDF export as a shared required API"
require_section_literal "### \`/accounting/ar/invoices\`" "Admin-only APIs (do not expose to accounting/sales roles): \`invoiceDownloadInvoicePdf\`" "$HANDOFF_DOC" \
  "invoice route must document invoice PDF as admin-only"
require_section_literal "### \`/accounting/ar/invoices\`" "Accounting portal dealer invoice drill-ins use \`portalFinanceInvoices\` on \`/api/v1/portal/finance/invoices\`; dealer self-service invoice reads remain on \`/api/v1/dealer-portal/invoices\`." "$HANDOFF_DOC" \
  "invoice route must document the canonical internal-vs-dealer invoice split"
require_section_literal "### \`/accounting/ar/invoices\`" "Role/permission gate: Mixed by endpoint:" "$HANDOFF_DOC" \
  "invoice route must document mixed RBAC truth"

forbid_section_line_literals "### \`/accounting/ar/collections-settlements\`" "- Required API calls" "acctDealerStatementPdf" "$HANDOFF_DOC" \
  "collections route must not list retired dealer statement PDF as a shared accountant-required API"
require_section_literal "### \`/accounting/ar/collections-settlements\`" "Canonical dealer finance reads: \`portalFinanceLedger\`, \`portalFinanceInvoices\`, and \`portalFinanceAging\` all route through \`/api/v1/portal/finance/*\`; do not wire retired dealer/accounting/report aliases back into the portal." "$HANDOFF_DOC" \
  "collections route must document the canonical portal finance host split"
require_section_literal "### \`/accounting/ar/collections-settlements\`" "Internal dealer receivables drill-ins stay on \`/api/v1/portal/finance/{ledger,invoices,aging}\` while dealer self-service finance remains on \`/api/v1/dealer-portal/{ledger,invoices,aging}\`." "$HANDOFF_DOC" \
  "collections route must keep the internal-vs-dealer finance host split explicit"
require_section_literal "### \`/accounting/ar/collections-settlements\`" "Role/permission gate: Mixed by endpoint: receipts/settlements/portal-finance reads use \`ROLE_ADMIN|ROLE_ACCOUNTING\`; \`GET /api/v1/accounting/sales/returns\` also permits \`ROLE_SALES\`." "$HANDOFF_DOC" \
  "collections route must document mixed RBAC truth for portal finance reads"

forbid_section_line_literals "### \`/accounting/reports/financial\`" "- Required API calls" "acctAuditDigest" "$HANDOFF_DOC" \
  "financial reports route must not list deprecated digest endpoints as required APIs"
forbid_section_line_literals "### \`/accounting/reports/financial\`" "- Required API calls" "acctAuditDigestCsv" "$HANDOFF_DOC" \
  "financial reports route must not list deprecated digest CSV as a required API"
require_section_literal "### \`/accounting/reports/financial\`" "Admin-only legacy exports (do not treat as required for this route): \`acctAuditDigest\`, \`acctAuditDigestCsv\`" "$HANDOFF_DOC" \
  "financial reports route must classify digest exports as admin-only legacy"
require_section_literal "### \`/accounting/reports/financial\`" "Audit-trail route dependency: use \`/accounting/audit-trail\` with \`acctAuditTransactions\` and \`acctAuditTransactionDetail\` for new transaction-audit UX." "$HANDOFF_DOC" \
  "financial reports route must point new audit UX to transaction audit route"
require_section_literal "### \`/accounting/reports/financial\`" "Role/permission gate: Mixed by endpoint:" "$HANDOFF_DOC" \
  "financial reports route must document mixed RBAC truth"

require_section_literal "### \`/accounting/period-close\`" "requestPeriodClose" "$HANDOFF_DOC" \
  "period-close route must document maker request-close workflow"
require_section_literal "### \`/accounting/period-close\`" "approvePeriodClose" "$HANDOFF_DOC" \
  "period-close route must document approve-close workflow"
require_section_literal "### \`/accounting/period-close\`" "rejectPeriodClose" "$HANDOFF_DOC" \
  "period-close route must document reject-close workflow"
require_section_literal "### \`/accounting/period-close\`" "do not wire \`acctClosePeriod\` as a frontend action" "$HANDOFF_DOC" \
  "period-close route must warn that direct close is disabled for frontend flows"
require_section_literal "### \`/accounting/period-close\`" "Period grid from \`AccountingPeriodDto\`" "$HANDOFF_DOC" \
  "period-close route must keep grid fields tied to AccountingPeriodDto truth"
require_section_literal "### \`/accounting/period-close\`" "derive it by joining \`PeriodCloseRequestDto\` / \`approvals\` data" "$HANDOFF_DOC" \
  "period-close route must treat pending review state as derived from request or queue payloads"
require_section_literal "### \`/accounting/period-close\`" "Role/permission gate: Mixed by endpoint." "$HANDOFF_DOC" \
  "period-close route must document mixed RBAC truth for maker-checker close"
require_section_literal "### \`/accounting/period-close\`" "\`acctReopenPeriod\` is \`ROLE_SUPER_ADMIN\` only" "$HANDOFF_DOC" \
  "period-close route must keep reopen restricted to superadmin-only UX"
require_literal "### Accounting Core Workflow Supplements (Code-Verified, Outside Parity Lock)" "$HANDOFF_DOC" \
  "handoff must keep explicit supplement rows for maker-checker period-close workflow"
require_literal '| `approvals` | GET | `/api/v1/admin/approvals` |' "$HANDOFF_DOC" \
  "handoff must include approvals queue inventory row"
require_literal '| `requestPeriodClose` | POST | `/api/v1/accounting/periods/{periodId}/request-close` |' "$HANDOFF_DOC" \
  "handoff must include request-close inventory row"
require_literal '| `approvePeriodClose` | POST | `/api/v1/accounting/periods/{periodId}/approve-close` |' "$HANDOFF_DOC" \
  "handoff must include approve-close inventory row"
require_literal '| `rejectPeriodClose` | POST | `/api/v1/accounting/periods/{periodId}/reject-close` |' "$HANDOFF_DOC" \
  "handoff must include reject-close inventory row"
require_literal "Maker-checker period-close note:" "$ENDPOINT_MAP_DOC" \
  "endpoint map must include maker-checker period-close note"
require_literal 'GET /api/v1/admin/approvals' "$ENDPOINT_MAP_DOC" \
  "endpoint map must reference approvals queue visibility for period close workflow"
require_literal 'POST /api/v1/accounting/periods/{periodId}/request-close' "$ENDPOINT_MAP_DOC" \
  "endpoint map must reference request-close for period close workflow"
require_literal 'POST /api/v1/accounting/periods/{periodId}/approve-close' "$ENDPOINT_MAP_DOC" \
  "endpoint map must reference approve-close for period close workflow"
require_literal 'POST /api/v1/accounting/periods/{periodId}/reject-close' "$ENDPOINT_MAP_DOC" \
  "endpoint map must reference reject-close for period close workflow"

echo "[guard_accounting_portal_scope_contract] OK"
