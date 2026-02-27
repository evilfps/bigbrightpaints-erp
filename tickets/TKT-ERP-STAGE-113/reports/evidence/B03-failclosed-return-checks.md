# B03 Fail-Closed Confirm + Return Reconciliation Evidence

- Ticket: `TKT-ERP-STAGE-113`
- Blocker: `B03`
- Branch: `tickets/tkt-erp-stage-113/b03-sales-failclosed-return`
- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B03-sales-failclosed-return`
- Timestamp (UTC): `2026-02-27T10:34:13Z`

## Scope implemented

1. `confirmOrder(...)` is fail-closed and only confirmable from:
   - `BOOKED`
   - `RESERVED`
   - `PENDING_PRODUCTION`
   - `PENDING_INVENTORY`
2. Invalid source states now raise:
   - `ApplicationException(ErrorCode.BUSINESS_INVALID_STATE)`
   - detail keys include `status` and `allowedStatuses`
3. Return unit-cost resolution no longer throws generic argument errors for missing dispatch layers.
   - It now returns deterministic fail-closed contract:
   - `ApplicationException(ErrorCode.BUSINESS_INVALID_STATE)`
   - detail keys include:
     - `reasonCode=RETURN_REQUIRES_DISPATCH_COST_RECONCILIATION`
     - `productCode`
     - `invoiceLineId`
     - `remediation`

## Files changed

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnService.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/SalesServiceTest.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnServiceTest.java`

## Commands and results

1. `cd erp-domain && mvn -B -ntp -Dtest='*Sales*' test`
   - Result: `BLOCKED (ENVIRONMENT)`
   - Error: Docker/Testcontainers unavailable (`NoSuchFileException: /var/run/docker.sock`)
   - Failing integration tests were Docker-dependent.
2. `bash ci/check-architecture.sh`
   - Result: `PASS`
3. `cd erp-domain && mvn -B -ntp -Dtest='SalesServiceTest,SalesReturnServiceTest' test`
   - Result: `PASS`
   - Summary: `Tests run: 92, Failures: 0, Errors: 0, Skipped: 0`

## Residual risk / next required validation

- Full `*Sales*` regression remains pending on a Docker-enabled runner.
- Required follow-up to close B03 QA gate:
  1. Re-run `cd erp-domain && mvn -B -ntp -Dtest='*Sales*' test` with Docker available.
  2. Execute merge-specialist + code-review + QA-reliability evidence on the same head SHA.
