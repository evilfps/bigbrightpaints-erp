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

## 2026-01-11 Task 06 Final verification
- Verification:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AdminUserSecurityIT,CompanyControllerIT,DealerSecurityIT,DealerPortalSecurityIT test`
- Logs:
  - `docs/ops_and_debug/LOGS/20260111T112838Z_task06_final_compile.txt`
  - `docs/ops_and_debug/LOGS/20260111T112846Z_task06_final_checkstyle.txt`
  - `docs/ops_and_debug/LOGS/20260111T112901Z_task06_final_test.txt`
  - `docs/ops_and_debug/LOGS/20260111T113117Z_task06_final_focus.txt`

## 2026-01-12 Task 07 M1 — Performance budgets
- Changes:
  - Extended `PerformanceBudgetIT` to budget critical finance reports (trial balance, dealer statement, account statement, inventory reconciliation, reconciliation dashboard).
  - Standardized auth headers with `X-Company-Id` for report endpoints.
- Verification:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=PerformanceBudgetIT,PerformanceExplainIT test`
- Logs:
  - `docs/ops_and_debug/LOGS/20260112T064835Z_task07_M1_compile.txt`
  - `docs/ops_and_debug/LOGS/20260112T064846Z_task07_M1_checkstyle.txt`
  - `docs/ops_and_debug/LOGS/20260112T064920Z_task07_M1_test.txt`
  - `docs/ops_and_debug/LOGS/20260112_122255_task07_M1_focus_performance.txt`

## 2026-01-12 Task 07 M2 — Ops boot + health evidence
- Ops boot notes:
  - Initial compose up failed due to host port 5432 in use; reran with `DB_PORT=55432`.
  - Seeded admin profile via `SPRING_PROFILES_ACTIVE=prod,seed` with `ERP_LICENSE_ENFORCE=false` for local boot.
  - Fixed `accounts_id_seq` drift (sequence behind max id) before retrying app start.
- Env var checklist (short):
  - Required: `JWT_SECRET` (32+ bytes), `ERP_SECURITY_ENCRYPTION_KEY` (32+ bytes).
  - Prod license: `ERP_LICENSE_KEY` when `ERP_LICENSE_ENFORCE=true` (set `ERP_LICENSE_ENFORCE=false` for local boot if no key).
  - Optional: `ERP_DISPATCH_DEBIT_ACCOUNT_ID`, `ERP_DISPATCH_CREDIT_ACCOUNT_ID`, `DB_PORT`, `APP_PORT`, `MANAGEMENT_PORT`, `ERP_CORS_ALLOWED_ORIGINS`, `ERP_MAIL_FROM`, `ERP_MAIL_BASE_URL`.
- Verification:
- `docker compose up -d --build` (DB_PORT override + prod/seed profile) and **management-port** `/actuator/health` + readiness/liveness checks (app port does not expose actuator in prod).
  - Smoke checks: auth login + profile, reconciliation dashboard (bankAccountId=7005), inventory reconciliation, orchestrator health events/integrations.
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
- Logs:
  - `docs/ops_and_debug/LOGS/20260112_123424_task07_M2_compose_up.txt`
  - `docs/ops_and_debug/LOGS/20260112_123529_task07_M2_compose_up_retry.txt`
  - `docs/ops_and_debug/LOGS/20260112_123911_task07_M2_accounts_max_id.txt`
  - `docs/ops_and_debug/LOGS/20260112_123911_task07_M2_accounts_seq.txt`
  - `docs/ops_and_debug/LOGS/20260112_123942_task07_M2_accounts_seq_fix.txt`
  - `docs/ops_and_debug/LOGS/20260112_123942_task07_M2_accounts_seq_fix_check.txt`
  - `docs/ops_and_debug/LOGS/20260112_123953_task07_M2_compose_up_after_seq_fix.txt`
  - `docs/ops_and_debug/LOGS/20260112_124008_task07_M2_app_logs_tail_after_seq_fix.txt`
  - `docs/ops_and_debug/LOGS/20260112_124015_task07_M2_actuator_health.json`
  - `docs/ops_and_debug/LOGS/20260112_124015_task07_M2_actuator_readiness.json`
  - `docs/ops_and_debug/LOGS/20260112_124015_task07_M2_actuator_liveness.json`
  - `docs/ops_and_debug/LOGS/20260112_124229_task07_M2_bbp_accounts.txt`
  - `docs/ops_and_debug/LOGS/20260112_124252_task07_M2_login.json`
  - `docs/ops_and_debug/LOGS/20260112_124252_task07_M2_auth_profile.json`
  - `docs/ops_and_debug/LOGS/20260112_124252_task07_M2_reconciliation_dashboard.json`
  - `docs/ops_and_debug/LOGS/20260112_124252_task07_M2_inventory_reconciliation.json`
  - `docs/ops_and_debug/LOGS/20260112_124252_task07_M2_orchestrator_events.json`
  - `docs/ops_and_debug/LOGS/20260112_124252_task07_M2_orchestrator_integrations.json`
  - `docs/ops_and_debug/LOGS/20260112_124343_task07_M2_compile.txt`
  - `docs/ops_and_debug/LOGS/20260112_124353_task07_M2_checkstyle.txt`
  - `docs/ops_and_debug/LOGS/20260112_124427_task07_M2_test.txt`

## 2026-01-12 Task 07 M3 — Outbox + backup/restore evidence
- Outbox safety:
  - Retry policy: exponential backoff (30s * 2^retry), max 5 attempts, then status=FAILED + dead_letter=true (documented in deploy checklist; implemented in EventPublisherService).
  - Seeded dataset snapshot: outbox table empty; no retrying or dead-lettered events; stuck retry count (next_attempt_at older than 15m) = 0.
- Idempotency checks:
  - IntegrationCoordinator retry protection validated via IntegrationCoordinatorTest (no replay of reservations on retry).
  - OrchestratorControllerIT confirms order approval produces an outbox event for traceability.
- Backup/restore runbook:
  - `pg_dump` from `erp_domain`, restore into `erp_domain_restore_test`, sanity queries for Flyway history + companies/outbox counts.
- Verification:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=OrchestratorControllerIT,IntegrationCoordinatorTest test`
- Logs:
  - `docs/ops_and_debug/LOGS/20260112T072542Z_task07_M3_compose_ps.txt`
  - `docs/ops_and_debug/LOGS/20260112T072630Z_task07_M3_outbox_counts.txt`
  - `docs/ops_and_debug/LOGS/20260112T072635Z_task07_M3_outbox_total.txt`
  - `docs/ops_and_debug/LOGS/20260112T072639Z_task07_M3_outbox_retrying.txt`
  - `docs/ops_and_debug/LOGS/20260112T072651Z_task07_M3_outbox_stuck_count.txt`
  - `docs/ops_and_debug/LOGS/20260112T072702Z_task07_M3_outbox_deadletters.txt`
  - `docs/ops_and_debug/LOGS/20260112T072714Z_task07_M3_backup_dump.txt`
  - `docs/ops_and_debug/LOGS/20260112T072718Z_task07_M3_backup_ls.txt`
  - `docs/ops_and_debug/LOGS/20260112T072722Z_task07_M3_restore_db_exists.txt`
  - `docs/ops_and_debug/LOGS/20260112T072725Z_task07_M3_restore_dropdb.txt`
  - `docs/ops_and_debug/LOGS/20260112T072731Z_task07_M3_restore_createdb.txt`
  - `docs/ops_and_debug/LOGS/20260112T072737Z_task07_M3_restore_pg_restore.txt`
  - `docs/ops_and_debug/LOGS/20260112T072747Z_task07_M3_restore_flyway_count.txt`
  - `docs/ops_and_debug/LOGS/20260112T072750Z_task07_M3_restore_companies_count.txt`
  - `docs/ops_and_debug/LOGS/20260112T072756Z_task07_M3_restore_outbox_count.txt`
  - `docs/ops_and_debug/LOGS/20260112T072812Z_task07_M3_compile.txt`
  - `docs/ops_and_debug/LOGS/20260112T072821Z_task07_M3_checkstyle.txt`
  - `docs/ops_and_debug/LOGS/20260112T072850Z_task07_M3_test.txt`
  - `docs/ops_and_debug/LOGS/20260112T073255Z_task07_M3_focus_orchestrator.txt`

## 2026-01-12 Task 07 Final Verification
- Verification:
  - `mvn -f erp-domain/pom.xml -DskipTests compile`
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
  - `mvn -f erp-domain/pom.xml test`
  - `mvn -f erp-domain/pom.xml -Dtest=PerformanceBudgetIT,PerformanceExplainIT,OrchestratorControllerIT,IntegrationCoordinatorTest test`
- Logs:
  - `docs/ops_and_debug/LOGS/20260112T073555Z_task07_final_compile.txt`
  - `docs/ops_and_debug/LOGS/20260112T073604Z_task07_final_checkstyle.txt`
  - `docs/ops_and_debug/LOGS/20260112T073645Z_task07_final_test.txt`
  - `docs/ops_and_debug/LOGS/20260112T074052Z_task07_final_focus.txt`

## 2026-01-14 LF-021/LF-022/LF-023 — Opening stock posting + idempotency guards
- Changes:
  - Opening stock import posts OPEN-STOCK journal (Dr inventory, Cr OPEN-BAL) and links movement journal_entry_id.
  - Purchase returns reuse movements by reference and reject conflicting replays.
  - Sales order + payroll run idempotency conflicts rejected via request hash.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T105316Z_sql_07_inventory_control_vs_valuation.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T105325Z_accounting_reports_gets.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105215Z_sql_purchase_return_reference.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105208Z_sql_raw_material_stock_after_return_2.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105052Z_sales_order_conflict_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105110Z_payroll_run_conflict_response.json`
- Notes:
  - BBP seeded variance remains until opening stock entries are backfilled to GL.

## 2026-01-14 LF-001/LF-002/LF-006 — Report sign normalization + aged debtors + AP reconciliation
- Changes:
  - Balance sheet and profit/loss now normalize credit-normal balances for reporting.
  - Aged debtors buckets use invoice outstanding amounts.
  - AP reconciliation normalizes GL liabilities before comparing to supplier ledger totals.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-001/OUTPUTS/20260114T113219Z_accounting_reports_gets.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-002/OUTPUTS/20260114T113128Z_seed_invoice_for_aging.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-002/OUTPUTS/20260114T113227Z_aged_debtors_get.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-006/OUTPUTS/20260114T113234Z_sql_05_ar_ap_tieouts_after_fix.txt`

## 2026-01-16 LF-007/LF-008/LF-009 — Idempotency scoping + trace tenancy
- Changes:
  - Payroll idempotency scope aligned to `(company_id, idempotency_key)` (entity + migration).
  - Orchestrator traces now persist `company_id`; trace reads are company-scoped + role-protected.
  - Settlement idempotency indexes widened to allow multi-allocation keys.
- Evidence:
  - Pending local DB access; run:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-007/RUN.md`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008/RUN.md`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009/RUN.md`
- Verification:
  - `mvn -f erp-domain/pom.xml test` failed: Testcontainers JNA temp file permission / docker socket access in current environment.
