# Orchestrator Review

ticket: TKT-ERP-STAGE-092
slice: SLICE-03
status: in_review

## Notes
- Slice patch covers runtime fail-closed enforcement and integration-test environment hardening:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/AuthTenantAuthorityIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/test/AbstractIntegrationTest.java`
- Required checks:
  - `bash ci/check-architecture.sh` -> PASS
  - `cd erp-domain && mvn -B -ntp test` -> FAIL with 5 assertions in `AccountingControllerIdempotencyHeaderParityTest`
  - `cd erp-domain && mvn -B -ntp test -Dtest=AuthTenantAuthorityIT` (with exported Java 21 + `ERP_TEST_DB_*`) -> PASS (11 tests)
  - `cd erp-domain && mvn -B -ntp test -Dtest=AccountingControllerIdempotencyHeaderParityTest` (with exported Java 21 + `ERP_TEST_DB_*`) -> FAIL (same 5 assertions)
- Baseline proof:
  - Same 5 failures reproduced on untouched base with:
    - `cd erp-domain && mvn -B -ntp test -Dtest=AccountingControllerIdempotencyHeaderParityTest`
- Reviewer statuses:
  - `qa-reliability` -> approved

## Merge Readiness
- Ready to merge with documented residual baseline failures outside slice scope.
