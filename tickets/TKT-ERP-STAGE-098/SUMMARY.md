# Ticket TKT-ERP-STAGE-098

- title: Gate Fast Threshold Closure Tranche 1
- goal: Raise anchored gate_fast changed-files line/branch coverage by adding deterministic tests for highest-deficit tenant runtime, company, admin/portal, purchasing, sales, and accounting-period services.
- priority: high
- status: merged
- base_branch: harness-engineering-orchestrator
- created_at: 2026-02-20T10:48:51+00:00
- updated_at: 2026-02-23T05:29:01+05:30

## Slice Board

| Slice | Agent | Lane | Status | Branch |
| --- | --- | --- | --- | --- |
| SLICE-01 | accounting-domain | w1 | merged | `tickets/tkt-erp-stage-098/accounting-domain` |
| SLICE-02 | auth-rbac-company | w2 | merged | `tickets/tkt-erp-stage-098/auth-rbac-company` |
| SLICE-03 | purchasing-invoice-p2p | w3 | merged | `tickets/tkt-erp-stage-098/purchasing-invoice-p2p` |
| SLICE-04 | reports-admin-portal | w4 | merged | `tickets/tkt-erp-stage-098/reports-admin-portal` |
| SLICE-05 | sales-domain | w1 | merged | `tickets/tkt-erp-stage-098/sales-domain` |
| SLICE-06 | refactor-techdebt-gc | w2 | merged | `tickets/tkt-erp-stage-098/refactor-techdebt-gc` |

## Implemented In This Tranche

- Added/updated deterministic tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodServiceTest.java` (new)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/security/TenantRuntimeEnforcementServiceTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/service/CompanyServiceTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementServiceTest.java` (new)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/service/TenantRuntimePolicyServiceTest.java` (new)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/portal/service/TenantRuntimeEnforcementInterceptorTest.java` (new)
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsControllerTenantRuntimeContractTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_SalesReturnCreditNoteIdempotencyTest.java` (dispatch exception override-request contract alignment)

## Verification Snapshot

1. `bash ci/check-architecture.sh` -> PASS.
2. Targeted deterministic suite (85 tests) -> PASS.
3. Anchored `gate_fast` rerun:
   - `line_ratio`: `0.3134212567882079`
   - `branch_ratio`: `0.33048211508553654`
   - status: FAIL (unchanged from pre-tranche baseline).
4. `SLICE-05` required gate rerun (Docker/Testcontainers-capable local env):
   - `cd erp-domain && mvn -B -ntp -Dapi.version=1.44 -Dtest='*Sales*' test` -> PASS (`141` tests; `0` failures, `0` errors).
   - `bash ci/check-architecture.sh` -> PASS.
5. `SLICE-06` targeted validation on slice branch:
   - `bash ci/check-architecture.sh` -> PASS.
   - `cd erp-domain && mvn -B -ntp -Dtest=CR_DispatchBusinessMathFuzzTest,AccountingCatalogControllerIdempotencyHeaderTest,PackingControllerTest,InventoryAdjustmentControllerTest,OpeningStockImportControllerTest,RawMaterialControllerTest,PortalInsightsControllerIT test` -> PASS (`25` tests; `0` failures, `0` errors).
6. Full-suite rerun for `SLICE-06` promotion on release-ops:
   - `cd erp-domain && mvn -B -ntp test` -> PASS (`1576` tests; `0` failures, `0` errors, `4` skipped).
7. Integration merge:
   - `b62fe0cd` (`merge(ticket-098): integrate slice-06 into release-ops`).

## Key Finding

`gate_fast` coverage is computed from the gate lane test set; newly added module tests are not currently included in that lane execution, so they do not move anchored gate ratios despite passing locally.

## Remaining Queue

1. Merge `tickets/tkt-erp-stage-098/release-ops` into `harness-engineering-orchestrator` after required gates/review and R3 human checkpoint.
