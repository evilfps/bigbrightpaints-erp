# B04 Dispatch Eligibility Parity Evidence

Date: 2026-02-27  
Branch: `tickets/tkt-erp-stage-113/b04-inventory-dispatch-eligibility`  
Head: pending push from local branch

## Scope

Blocker: `B04`  
Objective: close canceled-slip dispatch bypass and keep sales/inventory dispatch eligibility behavior aligned.

## Implementation

### Runtime changes

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
  - Added fail-closed guard `assertSlipDispatchable` to reject `CANCELLED` packing slips before dispatch execution.
  - Standardized active-slip selection through `isDispatchSelectableSlip` to keep selection logic centralized.
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java`
  - `/api/v1/dispatch/pending` now excludes `CANCELLED` and `DISPATCHED` slips from pending dispatch listings.

### Test changes

- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/SalesServiceTest.java`
  - Added `confirmDispatchRejectsCancelledPackingSlip`.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchControllerTest.java`
  - Added `getPendingSlips_filtersTerminalStatusesButKeepsBackorder`.

## Command Log

1. `cd erp-domain && mvn -B -ntp -Dtest='DispatchControllerTest,SalesServiceTest,FinishedGoodsServiceTest' test`  
   - Result: PASS (`126` run, `0` failed, `0` errors)
2. `cd erp-domain && /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/scripts/erp-testcontainers-env.sh mvn -B -ntp -Dtest='*Dispatch*' test`  
   - Result: PASS (`37` run, `0` failed, `0` errors)
3. `cd erp-domain && /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp/scripts/erp-testcontainers-env.sh mvn -B -ntp -Dtest='*Inventory*' test`  
   - Result: FAIL (`29` run, `1` error)  
   - Non-B04 blocker observed: `CR_InventoryGlAutomationProdOffIT` fails Spring context boot due `JwtProperties` static placeholder secret hardening (`JWT secret uses an unsafe static placeholder`).
4. `bash ci/check-architecture.sh`  
   - Result: PASS
5. `bash ci/check-enterprise-policy.sh`  
   - Result: PASS
6. `bash ci/check-orchestrator-layer.sh`  
   - Result: PASS
7. `DIFF_BASE=origin/harness-engineering-orchestrator bash scripts/gate_fast.sh`  
   - Result: FAIL at changed-files coverage only (`line_ratio=0.0476`, `branch_ratio=0.0`) on stacked branch diff.
8. `python3 scripts/changed_files_coverage.py --diff-base origin/harness-engineering-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml --output artifacts/gate-fast/changed-coverage-harness.json`  
   - Result: FAIL (`line_ratio=0.0476`, `branch_ratio=0.0`)
9. `python3 scripts/changed_files_coverage.py --diff-base origin/tickets/tkt-erp-stage-113/b03-sales-failclosed-return --jacoco erp-domain/target/site/jacoco/jacoco.xml --output artifacts/gate-fast/changed-coverage-b03base.json`  
   - Result: PASS (`line_ratio=1.0`, `branch_ratio=1.0`)

## Acceptance Mapping

- Canceled slips are blocked from dispatch execution path (`SalesService.confirmDispatch`).
- Canceled slips are hidden from pending dispatch API list (`DispatchController.getPendingSlips`).
- Backorder continuity remains intact (required by existing CODE-RED dispatch invariants).
