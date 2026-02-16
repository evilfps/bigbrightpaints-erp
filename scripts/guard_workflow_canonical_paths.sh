#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW_DOC="${WORKFLOW_CANONICAL_PATHS_DOC:-$ROOT_DIR/docs/system-map/CROSS_MODULE_WORKFLOWS.md}"
FLOW_MATRIX_DOC="${WORKFLOW_CANONICAL_FLOW_MATRIX_DOC:-$ROOT_DIR/docs/CODE-RED/confidence-suite/FLOW_EVIDENCE_MATRIX.md}"
REMEDIATION_COMMAND="bash scripts/guard_workflow_canonical_paths.sh"

fail() {
  echo "[guard_workflow_canonical_paths] FAIL: $1" >&2
  echo "[guard_workflow_canonical_paths] remediation: run '$REMEDIATION_COMMAND'" >&2
  exit 1
}

require_literal() {
  local file="$1"
  local needle="$2"
  local label="$3"
  rg -q --fixed-strings -- "$needle" "$file" || fail "missing $label in $file"
}

require_duplicate_risk_contract() {
  local section_heading="$1"
  awk -v heading="$section_heading" '
    $0 == heading {in_section=1; next}
    /^## / && in_section {exit}
    in_section && $0 == "- Duplicate/overlap risks:" {in_duplicate=1; next}
    in_section && in_duplicate && /^  - / {duplicate_items++}
    END {exit(duplicate_items > 0 ? 0 : 1)}
  ' "$WORKFLOW_DOC" || fail "section '$section_heading' must include duplicate-path decisions with bullet entries"
}

for path in "$WORKFLOW_DOC" "$FLOW_MATRIX_DOC"; do
  [[ -f "$path" ]] || fail "missing required contract document: $path"
done

for section in \
  "## Order-to-Cash (O2C)" \
  "## Procure-to-Pay (P2P)" \
  "## Production-to-Pack" \
  "## Payroll"; do
  require_literal "$WORKFLOW_DOC" "$section" "workflow section heading '$section'"
  require_duplicate_risk_contract "$section"
done

require_literal "$FLOW_MATRIX_DOC" '| O2C | `POST /api/v1/sales/dispatch/confirm`' "O2C canonical endpoint row"
require_literal "$FLOW_MATRIX_DOC" '-> `SalesService.confirmDispatch`' "O2C canonical service path"
require_literal "$FLOW_MATRIX_DOC" 'Canonical posting via `AccountingFacade.postCogsJournal` + `AccountingFacade.postSalesJournal`' "O2C posting contract"

require_literal "$FLOW_MATRIX_DOC" '| P2P (GRN) | `POST /api/v1/purchasing/goods-receipts`' "P2P GRN canonical endpoint row"
require_literal "$FLOW_MATRIX_DOC" '-> `PurchasingService.createGoodsReceipt`' "P2P GRN canonical service path"
require_literal "$FLOW_MATRIX_DOC" '| P2P (Invoice/AP) | `POST /api/v1/purchasing/raw-material-purchases`' "P2P invoice canonical endpoint row"
require_literal "$FLOW_MATRIX_DOC" '-> `PurchasingService.createPurchase`' "P2P invoice canonical service path"

require_literal "$FLOW_MATRIX_DOC" '| Manufacturing (Packing) | `POST /api/v1/factory/packing-records`' "production packing canonical endpoint row"
require_literal "$FLOW_MATRIX_DOC" '-> `PackingService.recordPacking`' "production packing canonical service path"
require_literal "$FLOW_MATRIX_DOC" '| Manufacturing (Bulk Pack) | `POST /api/v1/factory/pack`' "production bulk-pack canonical endpoint row"
require_literal "$FLOW_MATRIX_DOC" '-> `BulkPackingService.pack`' "production bulk-pack canonical service path"

require_literal "$FLOW_MATRIX_DOC" '| Payroll | `POST /api/v1/payroll/runs/{id}/post`' "payroll canonical endpoint row"
require_literal "$FLOW_MATRIX_DOC" '-> `PayrollService.postPayrollToAccounting`' "payroll canonical service path"
require_literal "$FLOW_MATRIX_DOC" 'posting via `AccountingFacade.postPayrollRun`' "payroll posting contract"

echo "[guard_workflow_canonical_paths] OK"
