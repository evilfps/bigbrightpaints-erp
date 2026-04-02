#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
O2C_WORKFLOW_DOC="${WORKFLOW_O2C_DOC:-$ROOT_DIR/docs/workflows/sales-order-to-cash.md}"
P2P_WORKFLOW_DOC="${WORKFLOW_P2P_DOC:-$ROOT_DIR/docs/workflows/purchase-to-pay.md}"
MANUFACTURING_WORKFLOW_DOC="${WORKFLOW_MANUFACTURING_DOC:-$ROOT_DIR/docs/workflows/manufacturing-and-packaging.md}"
PAYROLL_WORKFLOW_DOC="${WORKFLOW_PAYROLL_DOC:-$ROOT_DIR/docs/workflows/payroll.md}"
FRONTEND_V2_DOC="$ROOT_DIR/.factory/library/frontend-v2.md"
FRONTEND_HANDOFF_DOC="$ROOT_DIR/.factory/library/frontend-handoff.md"
FACTORY_FLOW_LIBRARY_DOC="$ROOT_DIR/.factory/library/factory-canonical-flow.md"
FACTORY_FLOW_SKILL_DOC="$ROOT_DIR/.factory/skills/factory-flow-worker/SKILL.md"
ORCHESTRATOR_MODULE_DOC="$ROOT_DIR/docs/modules/orchestrator.md"
O2C_REVIEW_DOC="$ROOT_DIR/docs/code-review/flows/order-to-cash.md"
ONBOARDING_ROUTE_MAP_DOC="$ROOT_DIR/docs/developer/onboarding-stock-readiness/02-route-service-map.md"
REMEDIATION_COMMAND="bash scripts/guard_workflow_canonical_paths.sh"
ERP_MAIN_DIR="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp"
SALES_CORE_ENGINE="$ERP_MAIN_DIR/modules/sales/service/SalesCoreEngine.java"
COMMAND_DISPATCHER="$ERP_MAIN_DIR/orchestrator/service/CommandDispatcher.java"
INTEGRATION_COORDINATOR="$ERP_MAIN_DIR/orchestrator/service/IntegrationCoordinator.java"
ORCHESTRATOR_DIR="$ERP_MAIN_DIR/orchestrator"
DISPATCH_REQUEST="$ERP_MAIN_DIR/orchestrator/dto/DispatchRequest.java"

fail() {
  echo "[guard_workflow_canonical_paths] FAIL: $1" >&2
  echo "[guard_workflow_canonical_paths] remediation: run '$REMEDIATION_COMMAND'" >&2
  exit 1
}

require_literal() {
  local file="$1"
  local needle="$2"
  local label="$3"
  grep -Fq -- "$needle" "$file" || fail "missing $label in $file"
}

forbid_literal() {
  local file="$1"
  local needle="$2"
  local label="$3"
  if grep -Fq -- "$needle" "$file"; then
    fail "found retired $label in $file"
  fi
}

for path in \
  "$O2C_WORKFLOW_DOC" \
  "$P2P_WORKFLOW_DOC" \
  "$MANUFACTURING_WORKFLOW_DOC" \
  "$PAYROLL_WORKFLOW_DOC" \
  "$FRONTEND_V2_DOC" \
  "$FRONTEND_HANDOFF_DOC" \
  "$FACTORY_FLOW_LIBRARY_DOC" \
  "$FACTORY_FLOW_SKILL_DOC" \
  "$ORCHESTRATOR_MODULE_DOC" \
  "$O2C_REVIEW_DOC" \
  "$ONBOARDING_ROUTE_MAP_DOC"; do
  [[ -f "$path" ]] || fail "missing required contract document: $path"
done

require_literal "$O2C_WORKFLOW_DOC" '`POST /api/v1/dispatch/confirm`' "O2C dispatch workflow doc guidance"
require_literal "$FRONTEND_V2_DOC" '`POST /api/v1/dispatch/confirm`' "frontend-v2 canonical dispatch guidance"
require_literal "$FRONTEND_HANDOFF_DOC" '`POST /api/v1/dispatch/confirm`' "frontend handoff canonical dispatch guidance"
require_literal "$FACTORY_FLOW_LIBRARY_DOC" '`POST /api/v1/dispatch/confirm`' "factory canonical flow library dispatch guidance"
require_literal "$FACTORY_FLOW_SKILL_DOC" '`POST /api/v1/dispatch/confirm`' "factory flow worker dispatch guidance"
require_literal "$ORCHESTRATOR_MODULE_DOC" '`POST /api/v1/dispatch/confirm`' "orchestrator module dispatch guidance"
require_literal "$O2C_REVIEW_DOC" '`POST /api/v1/dispatch/confirm`' "order-to-cash review dispatch guidance"
require_literal "$ONBOARDING_ROUTE_MAP_DOC" '`POST /api/v1/dispatch/confirm`' "onboarding route map dispatch guidance"

require_literal "$ORCHESTRATOR_MODULE_DOC" "inventory owns transport/controller" "orchestrator dispatch seam split"
require_literal "$O2C_REVIEW_DOC" "Factory/admin dispatch-confirm owner plus finance-repair reconciliation" "order-to-cash dispatch owner split"
require_literal "$ONBOARDING_ROUTE_MAP_DOC" "preserve downstream inventory and accounting posting consequences on the canonical dispatch path" "onboarding route map canonical dispatch note"

for path in \
  "$FRONTEND_V2_DOC" \
  "$FRONTEND_HANDOFF_DOC" \
  "$FACTORY_FLOW_LIBRARY_DOC" \
  "$FACTORY_FLOW_SKILL_DOC" \
  "$ORCHESTRATOR_MODULE_DOC" \
  "$O2C_REVIEW_DOC" \
  "$ONBOARDING_ROUTE_MAP_DOC"; do
  forbid_literal "$path" "/api/v1/sales/dispatch/confirm" "dispatch sales confirm alias"
done

forbid_literal "$ORCHESTRATOR_MODULE_DOC" "owned by the sales module" "dispatch full-sales-ownership wording"
forbid_literal "$O2C_REVIEW_DOC" "Sales-owned dispatch posting plus factory/operator read-only prepared-slip surfaces." "dispatch sales-owned review wording"
forbid_literal "$ONBOARDING_ROUTE_MAP_DOC" "sales-owned path" "dispatch sales-owned route map wording"

require_literal "$P2P_WORKFLOW_DOC" '`POST /api/v1/purchasing/goods-receipts`' "P2P GRN canonical endpoint doc"
require_literal "$P2P_WORKFLOW_DOC" '`POST /api/v1/purchasing/raw-material-purchases`' "P2P invoice canonical endpoint doc"

require_literal "$MANUFACTURING_WORKFLOW_DOC" '`POST /api/v1/factory/packing-records`' "manufacturing packing canonical endpoint doc"
if grep -Fq -- '`POST /api/v1/factory/pack`' "$MANUFACTURING_WORKFLOW_DOC"; then
  fail "manufacturing workflow doc still references retired bulk-pack endpoint in $MANUFACTURING_WORKFLOW_DOC"
fi
if grep -Fq -- '`POST /api/v1/factory/packing-records/{productionLogId}/complete`' "$MANUFACTURING_WORKFLOW_DOC"; then
  fail "manufacturing workflow doc still references retired packing complete endpoint in $MANUFACTURING_WORKFLOW_DOC"
fi

require_literal "$PAYROLL_WORKFLOW_DOC" '`POST /api/v1/payroll/runs/{id}/post`' "payroll canonical endpoint doc"

for path in \
  "$SALES_CORE_ENGINE" \
  "$COMMAND_DISPATCHER" \
  "$INTEGRATION_COORDINATOR"; do
  [[ -f "$path" ]] || fail "missing required source file: $path"
done

python3 - "$SALES_CORE_ENGINE" "$COMMAND_DISPATCHER" "$INTEGRATION_COORDINATOR" "$ORCHESTRATOR_DIR" "$DISPATCH_REQUEST" <<'PY' || fail "canonical dispatch source checks failed"
from pathlib import Path
import re
import sys

sales_core_path = Path(sys.argv[1])
command_dispatcher_path = Path(sys.argv[2])
integration_coordinator_path = Path(sys.argv[3])
orchestrator_dir = Path(sys.argv[4])
dispatch_request_path = Path(sys.argv[5])

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")

def fail(message: str) -> None:
    print(f"[guard_workflow_canonical_paths] FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)

def extract_method_body(text: str, signature_pattern: str, label: str) -> str:
    match = re.search(signature_pattern, text, re.MULTILINE)
    if not match:
        fail(f"missing {label}")
    brace_start = text.find("{", match.end() - 1)
    if brace_start == -1:
        fail(f"missing opening brace for {label}")
    depth = 0
    for index in range(brace_start, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace_start + 1:index]
    fail(f"unterminated method body for {label}")

sales_core = read(sales_core_path)
dispatch_body = extract_method_body(
    sales_core,
    r"public\s+DispatchConfirmResponse\s+confirmDispatch\s*\([^)]*\)\s*\{",
    "SalesCoreEngine.confirmDispatch"
)
for required_call, label in [
    ("accountingFacade.postCogsJournal(", "AccountingFacade.postCogsJournal call inside SalesCoreEngine.confirmDispatch"),
    ("accountingFacade.postSalesJournal(", "AccountingFacade.postSalesJournal call inside SalesCoreEngine.confirmDispatch"),
]:
    if required_call not in dispatch_body:
        fail(f"missing {label}")

command_dispatcher = read(command_dispatcher_path)
if "dispatchBatch(" in command_dispatcher:
    fail("CommandDispatcher must not keep the retired dispatchBatch shortcut")
if "DispatchRequest" in command_dispatcher:
    fail("CommandDispatcher must not reference the retired DispatchRequest payload")
if dispatch_request_path.exists():
    fail("DispatchRequest payload source must be deleted with the retired orchestrator dispatch shortcut")

integration_coordinator = read(integration_coordinator_path)
update_fulfillment_body = extract_method_body(
    integration_coordinator,
    r"public\s+AutoApprovalResult\s+updateFulfillment\s*\(\s*String\s+orderId,\s*String\s+requestedStatus,\s*String\s+companyId,\s*String\s+traceId,\s*String\s+idempotencyKey\s*\)\s*\{",
    "IntegrationCoordinator.updateFulfillment"
)
for token in ["SHIPPED", "DISPATCHED", "FULFILLED", "COMPLETED"]:
    if f'case "{token}":' not in update_fulfillment_body:
        fail(f"IntegrationCoordinator.updateFulfillment must explicitly reject dispatch-like status {token}")
if "/api/v1/dispatch/confirm" not in update_fulfillment_body:
    fail("IntegrationCoordinator.updateFulfillment must direct callers to /api/v1/dispatch/confirm")

forbidden_patterns = {
    "postDispatchJournal(": "removed orchestrator dispatch journal method",
    "createAccountingEntry(": "removed orchestrator accounting-entry helper",
    "DISPATCH-": "legacy orchestrator DISPATCH-* journal namespace",
    ".postSalesJournal(": "orchestrator-side sales journal posting",
    ".postCogsJournal(": "orchestrator-side COGS journal posting",
}

violations: list[str] = []
for path in sorted(orchestrator_dir.rglob("*.java")):
    text = read(path)
    relative = path.relative_to(orchestrator_dir)
    for needle, label in forbidden_patterns.items():
        if needle in text:
            violations.append(f"{relative}: found {label} via '{needle}'")

if violations:
    fail(" ; ".join(violations))

print("[guard_workflow_canonical_paths] verified canonical dispatch source path")
PY

echo "[guard_workflow_canonical_paths] OK"
