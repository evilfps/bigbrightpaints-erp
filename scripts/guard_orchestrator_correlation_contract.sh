#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISPATCHER="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java"
COORDINATOR="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java"
COORDINATOR_TEST="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java"
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

for path in "$DISPATCHER" "$COORDINATOR" "$COORDINATOR_TEST"; do
  [[ -f "$path" ]] || fail "missing required file: $path"
done

# Dispatcher must propagate trace/idempotency into both accounting side-effect calls.
require_multiline_pattern "postDispatchJournal\\([\\s\\S]*request\\.postingAmount\\(\\),[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.dispatchBatch does not pass traceId/idempotencyKey to postDispatchJournal"
require_multiline_pattern "recordPayrollPayment\\([\\s\\S]*request\\.creditAccountId\\(\\),[\\s\\S]*companyId,[\\s\\S]*traceId,[\\s\\S]*idempotencyKey\\)" "$DISPATCHER" \
  "CommandDispatcher.runPayroll does not pass traceId/idempotencyKey to recordPayrollPayment"

# Coordinator must expose overloads that accept correlation fields.
require_multiline_pattern "public void postDispatchJournal\\(String batchId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware postDispatchJournal overload"
require_multiline_pattern "public JournalEntryDto recordPayrollPayment\\(Long payrollRunId,[\\s\\S]*String traceId,[\\s\\S]*String idempotencyKey\\)" "$COORDINATOR" \
  "IntegrationCoordinator missing correlation-aware recordPayrollPayment overload"

# Regression tests must pin correlation memo propagation.
require_literal "postDispatchJournalPropagatesTraceAndIdempotencyInMemo" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing dispatch correlation memo assertion"
require_literal "recordPayrollPaymentPropagatesTraceAndIdempotencyInMemo" "$COORDINATOR_TEST" \
  "IntegrationCoordinatorTest missing payroll correlation memo assertion"

echo "[guard_orchestrator_correlation_contract] OK"
