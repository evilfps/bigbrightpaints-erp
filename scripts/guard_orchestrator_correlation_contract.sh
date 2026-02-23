#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISPATCHER="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java"
COORDINATOR="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java"
COORDINATOR_TEST="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java"
DISPATCHER_TEST="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcherTest.java"
REMEDIATION_COMMAND="bash scripts/guard_orchestrator_correlation_contract.sh"

fail() {
  echo "[guard_orchestrator_correlation_contract] ERROR: $1" >&2
  echo "[guard_orchestrator_correlation_contract] REMEDIATION: run '$REMEDIATION_COMMAND'" >&2
  exit 1
}

require_multiline_pattern() {
  local pattern="$1"
  local file="$2"
  local label="$3"
  if command -v rg >/dev/null 2>&1; then
    rg -U -q "$pattern" "$file" || fail "$label"
    return
  fi
  if command -v perl >/dev/null 2>&1; then
    CORR_PATTERN="$pattern" perl -0777 -ne 'BEGIN { $p = $ENV{"CORR_PATTERN"}; $matched = 0 } $matched = 1 if /$p/sm; END { exit($matched ? 0 : 1) }' "$file" \
      || fail "$label"
    return
  fi
  fail "missing regex engine (need rg or perl) while checking: $label"
}

require_literal() {
  local needle="$1"
  local file="$2"
  local label="$3"
  if command -v rg >/dev/null 2>&1; then
    rg -q --fixed-strings -- "$needle" "$file" || fail "$label"
    return
  fi
  grep -Fq -- "$needle" "$file" || fail "$label"
}

for path in "$DISPATCHER" "$COORDINATOR" "$COORDINATOR_TEST" "$DISPATCHER_TEST"; do
  [[ -f "$path" ]] || fail "missing required file: $path"
done

# Dispatcher must propagate trace/idempotency into all orchestrator side-effects.
require_multiline_pattern "reserveInventory\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.approveOrder does not pass traceId/idempotencyKey to reserveInventory"
require_multiline_pattern "updateFulfillment\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.updateOrderFulfillment does not pass traceId/idempotencyKey to updateFulfillment"
require_multiline_pattern "updateProductionStatus\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.dispatchBatch does not pass traceId/idempotencyKey to updateProductionStatus"
require_multiline_pattern "releaseInventory\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.dispatchBatch does not pass traceId/idempotencyKey to releaseInventory"
require_multiline_pattern "postDispatchJournal\\([\\s\\S]*request\\.postingAmount\\(\\),[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.dispatchBatch does not pass traceId/idempotencyKey to postDispatchJournal"
require_multiline_pattern "syncEmployees\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.runPayroll does not pass traceId/idempotencyKey to syncEmployees"
require_multiline_pattern "generatePayroll\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.runPayroll does not pass traceId/idempotencyKey to generatePayroll"
require_multiline_pattern "recordPayrollPayment\\([\\s\\S]*request\\.creditAccountId\\(\\),[\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.runPayroll does not pass traceId/idempotencyKey to recordPayrollPayment"

# Coordinator must expose overloads that accept correlation fields across flows.
require_multiline_pattern "public InventoryReservationResult reserveInventory\\(String orderId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware reserveInventory overload"
require_multiline_pattern "public AutoApprovalResult updateFulfillment\\(String orderId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware updateFulfillment overload"
require_multiline_pattern "public void updateProductionStatus\\(String planId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware updateProductionStatus overload"
require_multiline_pattern "public void releaseInventory\\(String batchId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware releaseInventory overload"
require_multiline_pattern "public void postDispatchJournal\\(String batchId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware postDispatchJournal overload"
require_multiline_pattern "public void syncEmployees\\(String companyId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware syncEmployees overload"
require_multiline_pattern "public PayrollRunDto generatePayroll\\(LocalDate payrollDate,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware generatePayroll overload"
require_multiline_pattern "public JournalEntryDto recordPayrollPayment\\(Long payrollRunId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware recordPayrollPayment overload"

# Regression tests must pin correlation propagation.
require_literal "updateOrderFulfillmentPropagatesTraceAndIdempotencyToCoordinator" "$DISPATCHER_TEST" \
  "CommandDispatcherTest missing fulfillment correlation propagation assertion"
require_literal "reserveInventoryCorrelationAnnotatesProductionArtifactsAndAttachesTrace" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing reserveInventory correlation assertion"
require_literal "releaseInventoryPropagatesTraceAndIdempotencyInBatchNotes" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing releaseInventory correlation assertion"
require_literal "postDispatchJournalPropagatesTraceAndIdempotencyInMemo" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing dispatch correlation memo assertion"
require_literal "recordPayrollPaymentPropagatesTraceAndIdempotencyInMemo" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing payroll correlation memo assertion"

echo "[guard_orchestrator_correlation_contract] OK"
