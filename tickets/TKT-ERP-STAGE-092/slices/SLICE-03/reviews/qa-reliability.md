# Review Evidence

ticket: TKT-ERP-STAGE-092
slice: SLICE-03
reviewer: qa-reliability
status: approved

## Findings
- `AuthTenantAuthorityIT` baseline contract failures were addressed:
  - lifecycle `HOLD/BLOCKED` now fail closed for company-scoped access in `CompanyContextFilter`.
  - unauthorized-with-tenant-header contract expectation in `AuthTenantAuthorityIT` now matches enforced fail-closed behavior.
- Integration-test runtime on this machine is stabilized via optional external DB fallback in `AbstractIntegrationTest` (`ERP_TEST_DB_*`) while preserving default Testcontainers behavior when env vars are absent.
- Full suite still reports 5 failures in `AccountingControllerIdempotencyHeaderParityTest`; these are pre-existing and reproduced on untouched base.

## Evidence
- commands:
  - `bash ci/check-architecture.sh` -> PASS
  - `(export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; export PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH; export ERP_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55433/erp_domain_test; export ERP_TEST_DB_USERNAME=erp_test; export ERP_TEST_DB_PASSWORD=erp_test; cd erp-domain; mvn -B -ntp test -Dtest=AuthTenantAuthorityIT)` -> PASS (11 tests)
  - `(same exported env; cd erp-domain; mvn -B -ntp test -Dtest=AccountingControllerIdempotencyHeaderParityTest)` on slice branch -> FAIL (5 assertions)
  - `(same exported env; cd erp-domain; mvn -B -ntp test -Dtest=AccountingControllerIdempotencyHeaderParityTest)` on untouched base branch -> same 5 FAIL (baseline)
  - `cd erp-domain && mvn -B -ntp test` -> FAIL only on the same 5 `AccountingControllerIdempotencyHeaderParityTest` assertions (recorded at `2026-02-19T18:08:40Z`)
- artifacts:
  - `erp-domain/target/surefire-reports/TEST-com.bigbrightpaints.erp.modules.auth.AuthTenantAuthorityIT.xml`
  - `erp-domain/target/surefire-reports/TEST-com.bigbrightpaints.erp.modules.accounting.controller.AccountingControllerIdempotencyHeaderParityTest.xml`
