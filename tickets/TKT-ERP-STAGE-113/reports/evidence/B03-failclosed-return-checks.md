# B03 Fail-Closed Confirm + Return Reconciliation Evidence

- Ticket: `TKT-ERP-STAGE-113`
- Blocker: `B03`
- Branch: `tickets/tkt-erp-stage-113/b03-sales-failclosed-return`
- Worktree: `/Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/TKT-ERP-STAGE-113/blocker-remediation-orchestrator_worktrees/TKT-ERP-STAGE-113/B03-sales-failclosed-return`
- Timestamp (UTC): `2026-02-27T11:10:02Z`

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

1. `cd erp-domain && /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/scripts/erp-testcontainers-env.sh mvn -B -ntp test`
   - Result: `FAIL (non-B03 codered baseline)`
   - Summary: `Tests run: 2198, Failures: 0, Errors: 7, Skipped: 4`
   - Finish time: `2026-02-27T11:09:38Z`
   - Failing classes:
     - `CR_ActuatorProdHardeningIT`
     - `CR_FactoryLegacyBatchProdGatingIT`
     - `CR_FinishedGoodBatchProdGatingIT`
     - `CR_HealthEndpointProdHardeningIT`
     - `CR_InventoryGlAutomationProdOffIT`
     - `CR_OpeningStockImportProdGatingIT`
     - `CR_SwaggerProdHardeningIT`
   - Root cause from surefire: `JwtProperties.validate` rejects static placeholder (`JWT secret uses an unsafe static placeholder`).
2. `bash ci/check-architecture.sh`
   - Result: `PASS`
3. `bash ci/check-enterprise-policy.sh`
   - Result: `PASS`
4. `bash ci/check-orchestrator-layer.sh`
   - Result: `PASS`
5. `cd erp-domain && /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/scripts/erp-testcontainers-env.sh mvn -B -ntp -Dtest='SalesServiceTest,SalesReturnServiceTest' test`
   - Result: `PASS`
   - Summary: `Tests run: 95, Failures: 0, Errors: 0, Skipped: 0`
6. `python3 scripts/changed_files_coverage.py --diff-base origin/harness-engineering-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml --output artifacts/gate-fast/changed-coverage.json`
   - Result: `PASS`
   - Summary: `line_ratio=1.0`, `branch_ratio=1.0`, `passes=true`

## Residual risk / next required validation

- The local full-suite failure is currently isolated to codered prod-hardening tests using the blocked static JWT placeholder path, not to B03 sales lifecycle logic.
- Required follow-up to close B03 workflow gate:
  1. Run merge-specialist + code-review + QA-reliability/release-ops evidence on the same head SHA.
  2. Decide whether codered prod-hardening secret test fixtures are handled in this ticket or a separate auth hardening follow-up.
