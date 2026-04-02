#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISPATCHER="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java"
COORDINATOR="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java"
SANITIZER="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CorrelationIdentifierSanitizer.java"
CONTROLLER="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/OrchestratorController.java"
IDEMPOTENCY_SERVICE="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrchestratorIdempotencyService.java"
TRACE_SERVICE="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/TraceService.java"
EVENT_PUBLISHER="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/EventPublisherService.java"
COORDINATOR_TEST="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java"
DISPATCHER_TEST="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcherTest.java"
CONTROLLER_IT="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java"
SANITIZER_TEST="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/CorrelationIdentifierSanitizerTest.java"
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

require_absent_literal() {
  local needle="$1"
  local file="$2"
  local label="$3"
  if command -v rg >/dev/null 2>&1; then
    if rg -q --fixed-strings -- "$needle" "$file"; then
      fail "$label"
    fi
    return
  fi
  if grep -Fq -- "$needle" "$file"; then
    fail "$label"
  fi
}

for path in "$DISPATCHER" "$COORDINATOR" "$SANITIZER" "$CONTROLLER" "$IDEMPOTENCY_SERVICE" "$TRACE_SERVICE" "$EVENT_PUBLISHER" "$COORDINATOR_TEST" "$DISPATCHER_TEST" "$CONTROLLER_IT" "$SANITIZER_TEST"; do
  [[ -f "$path" ]] || fail "missing required file: $path"
done

# Sanitizer contract must define strict allowlist and deterministic request hashing.
require_literal "SAFE_IDENTIFIER_PATTERN" "$SANITIZER" \
  "CorrelationIdentifierSanitizer missing strict identifier allowlist"
require_literal "sanitizeOptionalRequestId" "$SANITIZER" \
  "CorrelationIdentifierSanitizer missing request id normalization method"
require_literal "REQUEST_ID_HASH_PREFIX" "$SANITIZER" \
  "CorrelationIdentifierSanitizer missing deterministic request hash prefix"
require_literal "safeIdempotencyForLog" "$SANITIZER" \
  "CorrelationIdentifierSanitizer missing safe log representation helper"

# Dispatcher must propagate trace/idempotency into active orchestrator side-effects.
require_multiline_pattern "reserveInventory\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.approveOrder does not pass traceId/idempotencyKey to reserveInventory"
require_multiline_pattern "updateFulfillment\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.updateOrderFulfillment does not pass traceId/idempotencyKey to updateFulfillment"
require_multiline_pattern "syncEmployees\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.runPayroll does not pass traceId/idempotencyKey to syncEmployees"
require_multiline_pattern "generatePayroll\\([\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.runPayroll does not pass traceId/idempotencyKey to generatePayroll"
require_multiline_pattern "recordPayrollPayment\\([\\s\\S]*request\\.creditAccountId\\(\\),[\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.runPayroll does not pass traceId/idempotencyKey to recordPayrollPayment"
require_literal "CorrelationIdentifierSanitizer.normalizeRequestId(requestId, idempotencyKey)" "$DISPATCHER" \
  "CommandDispatcher does not normalize request identifiers via sanitizer contract"
require_literal "CorrelationIdentifierSanitizer.sanitizeRequiredIdempotencyKey" "$DISPATCHER" \
  "CommandDispatcher does not enforce idempotency sanitizer contract"

# Coordinator must expose overloads that accept correlation fields across flows.
require_multiline_pattern "public InventoryReservationResult reserveInventory\\(\\s*String orderId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware reserveInventory overload"
require_multiline_pattern "public AutoApprovalResult updateFulfillment\\(\\s*String orderId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware updateFulfillment overload"
require_multiline_pattern "public void updateProductionStatus\\(\\s*String planId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware updateProductionStatus overload"
require_absent_literal "public void releaseInventory(" "$COORDINATOR" \
  "IntegrationCoordinator must not expose the removed releaseInventory legacy batch seam"
require_multiline_pattern "public void syncEmployees\\(\\s*String companyId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware syncEmployees overload"
require_multiline_pattern "public PayrollRunDto generatePayroll\\(\\s*LocalDate payrollDate,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware generatePayroll overload"
require_multiline_pattern "public JournalEntryDto recordPayrollPayment\\(\\s*Long payrollRunId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware recordPayrollPayment overload"
require_literal "CorrelationIdentifierSanitizer.safeTraceForLog" "$COORDINATOR" \
  "IntegrationCoordinator logs are not using safe trace rendering"
require_literal "CorrelationIdentifierSanitizer.safeIdempotencyForLog" "$COORDINATOR" \
  "IntegrationCoordinator logs are not using safe idempotency rendering"
require_literal "CorrelationIdentifierSanitizer.sanitizeOptionalTraceId" "$COORDINATOR" \
  "IntegrationCoordinator memo/trace surfaces are not sanitized"
require_literal "CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey" "$COORDINATOR" \
  "IntegrationCoordinator memo/idempotency surfaces are not sanitized"

# Ingress and persistence surfaces must sanitize correlation identifiers.
require_literal "CorrelationIdentifierSanitizer.sanitizeOptionalRequestId(requestId)" "$CONTROLLER" \
  "OrchestratorController does not sanitize X-Request-Id header"
require_literal "CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(idempotencyKey)" "$CONTROLLER" \
  "OrchestratorController does not sanitize Idempotency-Key header"
require_literal "CorrelationIdentifierSanitizer.sanitizeRequiredTraceId(traceId)" "$CONTROLLER" \
  "OrchestratorController trace endpoint does not sanitize traceId path parameter"
require_literal "CorrelationIdentifierSanitizer.sanitizeRequiredIdempotencyKey(idempotencyKey)" "$IDEMPOTENCY_SERVICE" \
  "OrchestratorIdempotencyService does not enforce idempotency sanitizer contract"
require_literal "CorrelationIdentifierSanitizer.sanitizeTraceIdOrFallback" "$IDEMPOTENCY_SERVICE" \
  "OrchestratorIdempotencyService does not sanitize generated trace identifiers"
require_literal "CorrelationIdentifierSanitizer.sanitizeRequiredTraceId(traceId)" "$TRACE_SERVICE" \
  "TraceService does not sanitize trace ids before persistence/lookups"
require_literal "CorrelationIdentifierSanitizer.sanitizeOptionalRequestId(requestId)" "$TRACE_SERVICE" \
  "TraceService does not sanitize request ids before persistence"
require_literal "CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(idempotencyKey)" "$TRACE_SERVICE" \
  "TraceService does not sanitize idempotency keys before persistence"
require_literal "CorrelationIdentifierSanitizer.sanitizeRequiredTraceId(event.traceId())" "$EVENT_PUBLISHER" \
  "EventPublisherService does not sanitize trace ids before outbox persistence"
require_literal "CorrelationIdentifierSanitizer.sanitizeOptionalRequestId(event.requestId())" "$EVENT_PUBLISHER" \
  "EventPublisherService does not sanitize request ids before outbox persistence"
require_literal "CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(event.idempotencyKey())" "$EVENT_PUBLISHER" \
  "EventPublisherService does not sanitize idempotency keys before outbox persistence"

# Regression tests must pin correlation propagation.
require_literal "retiredDispatchShortcutIsRemovedFromCommandDispatcher" "$DISPATCHER_TEST" \
  "CommandDispatcherTest missing retired dispatch shortcut removal assertion"
require_literal "updateOrderFulfillmentPropagatesTraceAndIdempotencyToCoordinator" "$DISPATCHER_TEST" \
  "CommandDispatcherTest missing fulfillment correlation propagation assertion"
require_literal "reserveInventoryCorrelationAnnotatesProductionArtifactsAndAttachesTrace" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing reserveInventory correlation assertion"
require_literal "integrationCoordinatorNoLongerExposesLegacyReleaseInventoryCaller" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing legacy releaseInventory removal assertion"
require_literal "recordPayrollPaymentPropagatesTraceAndIdempotencyInMemo" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing payroll correlation memo assertion"
require_literal "recordPayrollPaymentRejectsMalformedIdempotencyKey" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing malformed idempotency rejection assertion"
require_literal "approve_order_rejects_malformed_idempotency_header" "$CONTROLLER_IT" \
  "OrchestratorControllerIT missing malformed Idempotency-Key rejection assertion"
require_literal "fulfillment_rejects_malformed_request_id_header" "$CONTROLLER_IT" \
  "OrchestratorControllerIT missing malformed X-Request-Id rejection assertion"
require_literal "safeIdempotencyForLogRedactsInvalidRawValue" "$SANITIZER_TEST" \
  "CorrelationIdentifierSanitizerTest missing safe log redaction assertion"

echo "[guard_orchestrator_correlation_contract] OK"
