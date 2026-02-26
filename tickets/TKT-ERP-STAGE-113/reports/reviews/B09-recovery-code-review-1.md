# B09 Recovery Code Review 1

Ticket: `TKT-ERP-STAGE-113`
Branch: `tickets/tkt-erp-stage-113/b09-orchestrator-correlation-sanitization-recovery`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B09-orchestrator-correlation-sanitization-recovery`
Review Scope: current `HEAD` (`f67e9fc2d855c7cd5ebc792d5e28c73a19dc26ac`), including test-only coverage remediation commit.

## Findings (Ordered by Severity)

### P1 - high confidence
**Malformed `orderId` is fail-open in approve/fulfillment flows and still emits trace/outbox side effects.**

- **Impacted anchors:**
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/OrchestratorController.java:49`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/OrchestratorController.java:82`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:149`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:151`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:309`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:311`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:77`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:87`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:168`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java:173`
- **Concrete failure/regression scenario:**
  - `POST /api/v1/orchestrator/orders/abc/approve` (or `/fulfillment`) is accepted because path variables are strings and `parseNumericId` returns `null` instead of fail-closed rejection.
  - `approve` path then emits `OrderApprovedEvent` + trace/audit despite malformed order identity.
  - `fulfillment` path returns `AutoApprovalResult("INVALID", false)` but still emits `OrderFulfillmentUpdated` + trace/audit and `202 Accepted`.
  - This creates false operational/audit artifacts and weakens command integrity for privileged orchestration endpoints.
- **Why current tests/checks miss it:**
  - `OrchestratorControllerIT` added malformed header coverage but does not exercise malformed `orderId` path parameters.
  - `TS_RuntimeOrchestratorCorrelationCoverageTest` is branch-coverage oriented and does not assert fail-closed malformed order identity behavior.
  - `scripts/guard_orchestrator_correlation_contract.sh` only checks symbol/literal presence, not runtime fail-closed semantics.
- **Minimal remediation guidance:**
  - Enforce numeric path identity at ingress (`@PathVariable Long` or explicit validator) for approve/fulfillment routes.
  - In service layer, replace silent `null`/`INVALID` fallbacks for malformed IDs with `ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, ...)`.
  - Block side effects when identity parsing fails before dispatching events/traces.
- **Missing regression tests to add with the fix:**
  - `OrchestratorControllerIT`: malformed `orderId` cases (`abc`, `%0A`, overlong) for approve/fulfillment must return `400` and keep outbox/audit counts unchanged.
  - `IntegrationCoordinatorTest`: malformed ID inputs for `reserveInventory`/`updateFulfillment` must throw validation exceptions and never call downstream services.

### P2 - medium confidence
**Raw malformed identifier values are logged unsanitized in numeric parse failure path.**

- **Impacted anchor:**
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:752`
- **Concrete failure/regression scenario:**
  - `parseNumericId` logs raw user-provided `id` (`log.warn("Value {} is not a numeric identifier", id)`).
  - If malformed path text with control/newline characters reaches this branch (for example encoded path inputs accepted by upstream), logs can be polluted with multi-line/injected content.
- **Why current tests/checks miss it:**
  - No log-capture assertions exist for malformed path identifier handling.
  - Correlation sanitization tests cover correlation fields, not non-correlation identifiers used in parse warnings.
- **Minimal remediation guidance:**
  - Replace raw identifier logging with sanitized/fingerprinted representation (length + fingerprint, no raw value).
  - Keep rejection/error semantics fail-closed before this logging path where possible.
- **Missing regression tests to add with the fix:**
  - Unit test for malformed identifier containing control characters must assert log output remains single-line and does not contain raw injected content.

### P3 - high confidence
**Recovery evidence metadata is stale for current HEAD, weakening traceability of the remediation commit.**

- **Impacted anchor:**
  - `tickets/TKT-ERP-STAGE-113/reports/evidence/B09-recovery-checks.md:8`
- **Concrete failure/regression scenario:**
  - Evidence file declares `Resulting HEAD: 406c8329...` while review target HEAD is `f67e9fc2...`.
  - Auditors/merge reviewers cannot reliably tie recorded commands to the actual reviewed SHA without manual reconciliation.
- **Why current tests/checks miss it:**
  - No automated evidence-consistency guard validates report SHA metadata against branch HEAD.
- **Minimal remediation guidance:**
  - Update evidence metadata to current HEAD and keep command outputs tied to the same SHA.
  - Consider adding a lightweight evidence consistency check in ticket/report automation.
- **Missing regression checks:**
  - Add a report validation step that fails if declared HEAD SHA does not match `git rev-parse HEAD`.

## Validation Evidence

- `bash scripts/guard_orchestrator_correlation_contract.sh` -> **PASS** (`[guard_orchestrator_correlation_contract] OK`)
- `cd erp-domain && mvn -B -ntp -Dtest='*Orchestrator*' test` -> **PASS** (`Tests run: 63, Failures: 0, Errors: 0, Skipped: 0`)
- `python3 scripts/changed_files_coverage.py --diff-base tickets/tkt-erp-stage-113/blocker-remediation-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml` -> **PASS** (`line_ratio=0.9635`, `branch_ratio=0.9394`, thresholds met)
- `bash ci/check-architecture.sh` -> **PASS**
- `bash ci/check-enterprise-policy.sh` -> **PASS**

## Assessment Summary

- Changed-files coverage closure is technically achieved on current HEAD.
- Production safety is **not** fully validated for merge due unresolved fail-open malformed `orderId` handling and raw malformed identifier logging risk.
