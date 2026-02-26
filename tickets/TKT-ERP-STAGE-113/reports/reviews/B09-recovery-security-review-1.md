# B09 Recovery Security Review 1

Ticket: `TKT-ERP-STAGE-113`
Branch: `tickets/tkt-erp-stage-113/b09-orchestrator-correlation-sanitization-recovery`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B09-orchestrator-correlation-sanitization-recovery`
Reviewer: `security co-review`
Date: `2026-02-26`

## Findings (Ordered by Severity)

### HIGH - Malformed order identifiers are not fail-closed in approve/fulfillment command paths
- Impact:
  - Requests with non-numeric `orderId` still return accepted command traces and emit outbox/audit events instead of rejecting input.
  - This allows authenticated callers to create integration/audit noise for non-existent orders and weakens privileged operation fail-closed guarantees.
- Anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:149`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:151`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:309`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:311`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:75`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:77`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:87`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:168`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:173`
- Minimal remediation plan:
  - Enforce numeric identifier validation at controller ingress (`@PathVariable Long` or explicit validator) and reject malformed IDs with `400` before dispatch.
  - In service layer, replace `null`/`INVALID` fallback branches for malformed IDs with explicit `ApplicationException` (`VALIDATION_INVALID_INPUT`) to guarantee fail-closed behavior even for non-controller callers.
  - Add guard assertions that no outbox/audit writes occur on malformed ID rejection.
- Required verification to prove closure:
  - Add integration tests for `POST /api/v1/orchestrator/orders/{orderId}/approve` and `/fulfillment` with malformed `orderId` (`abc`, `%0A`, overlong) expecting `400` and zero outbox/audit increments.
  - Add unit tests for `IntegrationCoordinator.reserveInventory` and `updateFulfillment` malformed IDs asserting exception and no downstream service/event calls.
  - Re-run targeted suite and correlation guard:
    - `cd erp-domain && mvn -B -ntp -Dtest='OrchestratorControllerIT,IntegrationCoordinatorTest,CommandDispatcherTest,TS_RuntimeOrchestratorCorrelationCoverageTest' test`
    - `bash scripts/guard_orchestrator_correlation_contract.sh`

### MEDIUM - Raw malformed identifiers can still poison logs
- Impact:
  - Malformed identifier text is written directly to logs (`log.warn("Value {} is not a numeric identifier", id)`), enabling newline/control-character log injection if a crafted path value is accepted by the HTTP stack.
  - Correlation IDs are sanitized, but non-correlation IDs in this path remain unescaped.
- Anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:752`
- Minimal remediation plan:
  - Replace raw identifier logging with sanitized/fingerprinted representation (for example, `CorrelationIdentifierSanitizer.safeTraceForLog`-style helper generalized for entity IDs, or explicit control-char stripping + truncation).
  - Prefer logging parse-failure metadata (length/fingerprint) over raw input.
- Required verification to prove closure:
  - Add unit test for malformed ID containing `\n`/control chars asserting logged output does not contain raw injected content.
  - Add one integration test invoking malformed path ID and assert response is fail-closed (`400`) while log capture remains single-line sanitized.

## Residual Risks
- Header-level correlation/idempotency sanitization and sink-side persistence guards are materially improved in this branch.
- Residual risk remains until malformed path identifiers are rejected before dispatch and raw identifier logging is sanitized as noted above.
