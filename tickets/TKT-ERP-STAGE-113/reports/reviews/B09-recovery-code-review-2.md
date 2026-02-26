# B09 Recovery Code Review 2

Ticket: `TKT-ERP-STAGE-113`
Branch: `tickets/tkt-erp-stage-113/b09-orchestrator-correlation-sanitization-recovery`
Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B09-orchestrator-correlation-sanitization-recovery`
Reviewed HEAD: `355ca643bc9dfbb1f82c5db9140caf95f2fb36ae`

## Findings (Ordered by Severity)

No findings.

## Closure Validation (Prior Findings)

1. Prior fail-open malformed `orderId` behavior is closed.
   - Fail-closed validation now enforced before side effects in orchestrator service paths:
     - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:149`
     - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:204`
     - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:303`
     - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:739`
   - Runtime no-side-effect assertions for malformed `orderId` are present at service, dispatcher, and controller levels:
     - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java:237`
     - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java:251`
     - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcherTest.java:120`
     - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcherTest.java:194`
     - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java:453`
     - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java:479`

2. Prior log-poisoning malformed identifier logging path is closed.
   - Parse-failure logs now emit sanitized/fingerprinted identifiers only:
     - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:757`
     - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:758`
   - Sanitization helper used for this path:
     - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CorrelationIdentifierSanitizer.java:88`
     - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/CorrelationIdentifierSanitizer.java:92`
   - Regression test asserts no raw/newline content leaks to logs:
     - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java:264`

## Verification Evidence

- `cd erp-domain && mvn -B -ntp -Dtest=OrchestratorControllerIT,CommandDispatcherTest,IntegrationCoordinatorTest test` -> PASS (`Tests run: 60, Failures: 0, Errors: 0, Skipped: 0`)
- `bash scripts/guard_orchestrator_correlation_contract.sh` -> PASS (`[guard_orchestrator_correlation_contract] OK`)
- `cd erp-domain && mvn -B -ntp -Dtest=TS_RuntimeOrchestratorCorrelationCoverageTest test` -> PASS (`Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`)

## Residual Risks / Testing Gaps

- End-to-end malformed-path no-side-effect coverage is currently explicit for `abc` inputs; encoded newline (`%0A`) and overlong path IDs are not explicitly asserted at controller integration level.
- Existing coverage is still strong because service-level fail-closed guards execute before side-effecting calls and are unit-tested for control-character input, but additional HTTP-level malformed path variants would harden regression protection.
