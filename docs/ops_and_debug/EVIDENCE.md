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

## 2026-01-11 Task 06 M2 — Company boundary enforcement
- Findings (company context drift):
  - `CompanyContextFilter` favored JWT claims even when `X-Company-Id` was provided, preventing explicit company context switching for multi-company users.
- Fixes (fail-closed):
  - Prefer `X-Company-Id` when present and fall back to JWT claim only if the header is absent.
  - Added CompanyControllerIT coverage for forbidden company switching and header-based company context selection.
- Verification:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=AdminUserSecurityIT,CompanyControllerIT test`
- Logs:
  - `docs/ops_and_debug/LOGS/20260111T111139Z_task06_M2_compile.txt`
  - `docs/ops_and_debug/LOGS/20260111T111153Z_task06_M2_checkstyle.txt`
  - `docs/ops_and_debug/LOGS/20260111T111212Z_task06_M2_test.txt`
  - `docs/ops_and_debug/LOGS/20260111T111428Z_task06_M2_focus_admin_company.txt`

## 2026-01-11 Task 06 M3 — Dealer portal boundaries
- Findings (dealer portal access gaps):
  - No focused coverage asserting dealer portal access is limited to dealer users and denied for admin/sales roles.
- Fixes (coverage only; no permission expansion):
  - Added DealerPortalSecurityIT to assert dealer users can access `/api/v1/dealer-portal/dashboard` and receive their dealer context.
  - Added DealerSecurityIT to assert admin users are forbidden from `/api/v1/dealer-portal/dashboard`.
- Verification:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=DealerSecurityIT,DealerPortalSecurityIT test`
- Logs:
  - `docs/ops_and_debug/LOGS/20260111T112314Z_task06_M3_compile.txt`
  - `docs/ops_and_debug/LOGS/20260111T112327Z_task06_M3_checkstyle.txt`
  - `docs/ops_and_debug/LOGS/20260111T112341Z_task06_M3_test.txt`
  - `docs/ops_and_debug/LOGS/20260111T112620Z_task06_M3_focus_dealer_portal.txt`
