# Evidence Log

## 2026-01-11 Task 06 M1 — RBAC alignment
- Findings (RBAC mismatches):
  - `/api/v1/sales/promotions` allowed `ROLE_DEALER` even though dealer portal access is restricted to `/api/v1/dealer-portal/**`.
  - `/api/v1/dealers/{dealerId}/ledger|invoices|aging` allowed `ROLE_DEALER` despite being admin/sales endpoints.
- Fixes (fail-closed):
  - Removed `ROLE_DEALER` from the above endpoints in `SalesController` and `DealerController`.
  - Added AdminUserSecurityIT coverage to assert dealer access is forbidden.
  - Added `docs/API_PORTAL_MATRIX.md` to document portal-to-endpoint role mapping summary.
- Verification:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AdminUserSecurityIT test`
- Logs:
  - `docs/ops_and_debug/LOGS/20260111T105541Z_task06_M1_compile.txt`
  - `docs/ops_and_debug/LOGS/20260111T105559Z_task06_M1_checkstyle.txt`
  - `docs/ops_and_debug/LOGS/20260111T105615Z_task06_M1_test.txt`
  - `docs/ops_and_debug/LOGS/20260111T105823Z_task06_M1_focus_auth_admin.txt`
