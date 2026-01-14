# HYDRATION

## Completed Epics
- Epic 03: branch `epic-03-production-stock`, tip `3f2370c38c0152153369507159e5ae26ca1fa048`.
- Epic 04: branch `epic-04-p2p-ap`, tip `c5dd42334a397b1137d821bd81f50b1504debca4`.
- Epic 05: branch `epic-05-hire-to-pay`, tip `dd1589c00634f9a122ebc9d35caf5114ada1f561`.
- Epic 06: branch `epic-06-admin-security`, tip `dabaeebc8de027491f0974050032bb86afbee5cc`.
- Epic 07: branch `epic-07-performance-scalability`, tip `96c0c71c0d751f3767cfbfb43e970842da9112b5`.
- Epic 08: branch `epic-08-reconciliation-controls`, tip `afe04b5561d9d6510d61bce58640da2dfbec5010`.
- Epic 09: branch `epic-09-operational-readiness`, tip `ca3851aea88ca5b791e65b896a1419a741283c49`.
- Epic 10: branch `epic-10-cross-module-traceability`, tip `c94755d70bcb5ba452ae64ddd7d8a6b96b50d392`.
- Audit investigation 07-08: branch `audit-inv-07-08`, commits `63352c5592b3f1d6c62c40f72e5b17d41803d0c1` (task-07), `381473579213e070e9b9ddfe8dee52012492e1a9` (task-08).
- Audit investigation 01-02: branch `audit-inv-01-02`, commits `2972890f8af382e3a17dcdf9378b87de9664ced4` (task-01), `a0dfdf97a372fa63be015c9db5fec95e39ccea39` (task-02), `edb3fd8a0fa08cbdf3b7ad93a0fd6570bc3ad1df` (task-02 evidence), `225c16a2b4fd3616d9ee1a1208450332d1f0269e` (task-01 evidence).
- Audit investigation 03: branch `audit-inv-03-evidence-and-inventory`, commit `7abdc72d039d7f5d7fbaab6639bf2ee11aa72759` (LEAD-010/011 evidence + task-03 probes).
- Audit investigation 04-05: branch `audit-inv-04-05-prod-tax`, commits `1e0ecd39a48b9d4874d6d11c0d356849f4215d9e` (task-04 evidence) and `c2293c3a44f63fb952ac9f96027014e7c60e28e3` (task-05 evidence).
- Audit investigation 09-06: branch `audit-inv-09-06-ops-close`, commits `1e0e9e7868dfe0fb0b8413c485785f89f9611d6e` (task-09 evidence) and `52b60d91ead0dc65a6b18025d97e281ea7a59d79` (task-06 evidence).

## Repo / Worktree State
- Worktree: `/home/realnigga/Desktop/CLI_BACKEND_epic04`
- Branch: `fix-phase5-lead015-and-lf011-014`
- Tip: `0a5eea9efd6dec594fb533e631d9d42676f4d5cb`
- Dirty: untracked logs present under `docs/ops_and_debug/LOGS` + `interview/` (pre-existing).

## Environment Setup
- No new installs; Docker/Testcontainers working.

## Commands Run (Latest)
- `DB_PORT=55432 APP_PORT=8081 MANAGEMENT_PORT=19090 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... SPRING_PROFILES_ACTIVE=prod,seed,mock docker compose up -d` (failed config validation; task-08).
- `DB_PORT=55432 APP_PORT=8081 MANAGEMENT_PORT=19090 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... SPRING_PROFILES_ACTIVE=prod,seed,mock docker compose run -d --service-ports -e ERP_ENVIRONMENT_VALIDATION_ENABLED=false app` (task-08 runtime).
- `curl -X POST http://localhost:8081/api/v1/auth/login` (mock admin; token captured in task-08 OUTPUTS).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/curl/02_parallel_post.sh` (sales order, payroll run, purchase return, bulk pack parallel probes).
- `psql -h localhost -p 55432 -U erp -d erp_domain -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/{05_sales_order_idempotency_check,06_payroll_run_idempotency_check,07_purchase_return_reference_check,08_packaging_movement_check,01_idempotency_duplicates,02_outbox_backlog_and_duplicates,03_partner_settlement_idempotency_index}.sql` (task-08 SQL probes).
- `DB_PORT=55432 APP_PORT=8081 MANAGEMENT_PORT=19090 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... SPRING_PROFILES_ACTIVE=prod,seed docker compose up -d --build` (app failed config validation; restarted with dev profile).
- `DB_PORT=55432 APP_PORT=8081 MANAGEMENT_PORT=19090 JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... SPRING_PROFILES_ACTIVE=dev docker compose up -d --no-deps --force-recreate app` (task-03/task-06 runtime).
- `psql -h localhost -p 55432 -U erp -d erp_domain -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/{06_inventory_valuation_fifo,07_inventory_control_vs_valuation,02_orphans_movements_without_journal,12_orphan_reservations,03_dispatch_slips_without_cogs_journal}.sql` (task-03 large-data rerun).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh` (task-03 accounting reports GETs).
- `psql -h localhost -p 55432 -U erp -d erp_domain -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/SQL/*.sql` (task-06 SQL probes rerun).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/curl/01_accounting_reports_gets.sh` (task-06 accounting reports GETs).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; Phase 5 verification).
- `mvn -f erp-domain/pom.xml checkstyle:check` (FAIL; 29479 violations reported; baseline).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 219, Failures 0, Errors 0, Skipped 4).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; task-09/06 verification; `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T085735Z_mvn_compile.txt`).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported; `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T085741Z_mvn_checkstyle.txt`).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 213, Failures 0, Errors 0, Skipped 4; `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T085754Z_mvn_test.txt`).
- `docker compose up -d` (with `DB_PORT=55432`, `APP_PORT=8081`, `MANAGEMENT_PORT=19090`; PASS).
- `curl http://localhost:19090/actuator/health` (UP).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; audit task 04 evidence gate).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; audit task 04 evidence gate).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; audit task 05 evidence gate).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; audit task 05 evidence gate).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 213, Failures 0, Errors 0, Skipped 4).
- `curl http://localhost:8081/actuator/health` (UP).
- `docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'` (containers up).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; audit task 03 evidence gate).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported; audit task 03 evidence gate).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 213, Failures 0, Errors 0, Skipped 4).
- `curl http://localhost:9090/actuator/health` (UP).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; task-02 evidence gate).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported; task-02 evidence gate).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; task-01 evidence gate).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported; task-01 evidence gate).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 213, Failures 0, Errors 0, Skipped 4).
- `curl http://localhost:9090/actuator/health` (UP).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 213, Failures 0, Errors 0, Skipped 4).
- `mvn -f erp-domain/pom.xml -Dtest=PerformanceBudgetIT,PerformanceExplainIT,OrchestratorControllerIT,IntegrationCoordinatorTest test` (PASS; Tests run 16, Failures 0, Errors 0, Skipped 0).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; audit task 07 + 08).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported; audit task 07 + 08).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; audit task 01).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported; audit task 01).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; audit task 02).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported; audit task 02).

## Evidence Paths (Latest)
- Task-08 outputs: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T081157Z_sales_order_conflict_response.json`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T081225Z_payroll_run_conflict_response.json`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T081253Z_sql_purchase_return_reference.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T081326Z_sql_packaging_movements.txt`.
- Task-03 outputs: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T075752Z_06_inventory_valuation_fifo.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T075752Z_07_inventory_control_vs_valuation.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T075408Z_01_accounting_reports_gets.txt`.
- Task-06 outputs: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260114T075416Z_sql_orphans_documents_without_journal.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260114T075416Z_sql_period_integrity.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260114T075425Z_accounting_reports_gets.json`.
- Phase 5 evidence: `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-011/OUTPUTS/`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/OUTPUTS/`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-013/OUTPUTS/`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-014/OUTPUTS/`.
- Task-09 outputs: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082939Z_actuator_health.json`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082949Z_health_gets_app_port.txt`.
- Task-06 outputs: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084648Z_period_lock_response.json`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084715Z_journal_locked_override_response.json`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084854Z_period_close_response.json`.
- Verification gates: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T085735Z_mvn_compile.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T085741Z_mvn_checkstyle.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T085754Z_mvn_test.txt`.

## Warnings / Notes
- Task-08: prod+seed+mock boot failed config validation; used `ERP_ENVIRONMENT_VALIDATION_ENABLED=false` with `docker compose run` to start app.
- Task-08: purchase return retries with same reference duplicated RM movements (4x) → LEAD-019.
- Task-08: conflicting payloads with same `idempotencyKey` returned existing records (sales order + payroll) → LEAD-020.
- App boot under `SPRING_PROFILES_ACTIVE=prod,seed` failed config validation (GST accounts + production metadata); ran reconciliation probes on `SPRING_PROFILES_ACTIVE=dev` runtime.
- Task-03 inventory reconciliation variance is 9130 (valuation 9183 vs ledger 53) → LEAD-018 logged.
- Checkstyle baseline violations remain (29479); `checkstyle:check` fails as expected.
- Testcontainers auth config warnings and dynamic agent loading notices persisted.
- Test logs include expected warnings (invalid company IDs, negative balances, dispatch mapping, sequence contention/duplicate key retries, HTML-to-PDF CSS parsing); no failures.
- Outbox queries returned zero pending/retrying/dead-letter events on seeded dataset; stuck retry count 0.
- Task-09: app-port actuator health returns 404; management port health is UP (LEAD-014).
- Task-06: admin override did not bypass locked period posting; reopen required (LEAD-016).
- Task-04 production/WIP probes confirmed LF-012 (WIP over-credit), LF-013 (packing status stale), LF-014 (FG creation 500 when discount default missing); LEAD-015 promoted to LF-015 and fixed; LEAD-017 later confirmed → LF-018 (unpacked-batches lazy-load).
- Task-05 GST return failed with GST accounts unset while config health reported healthy → LF-011.

## Current Task
- ERP logic audit program: Task-08 idempotency stress run completed on `fix-phase5-lead015-and-lf011-014`; new leads LEAD-019/LEAD-020 logged.
- Next recommended step: validate LEAD-019 (purchase return movement duplication) and LEAD-020 (idempotency conflict rejection policy) or promote if confirmed.

## Commands Run (Audit)
- `sed -n ... SCOPE.md` and `.codex/AGENTS.md` (scope + execution rules).
- `sed -n ...` on repo docs required by the audit brief (module map, API matrix, stabilization log, debugging/predeploy tasks, ops evidence).
- `git rev-parse --abbrev-ref HEAD` / `git rev-parse HEAD` / `git status --porcelain` (captured in audit report).
- `docker compose up -d` with `DB_PORT=55432`, `APP_PORT=8081`, `MANAGEMENT_PORT=19090` (task-09 runtime).
- `docker compose ps` + `curl http://localhost:19090/actuator/health` + `curl http://localhost:8081/actuator/health` (ops probes).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/*.sql` (task-09 ops SQL probes; no rows).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/*.sh` (task-09 ops GET probes).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/SQL/*.sql` (task-06 period close SQL probes; no rows).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/curl/*.sh` (task-06 accounting reports GET probes).
- `curl -X POST /api/v1/accounting/journal-entries` + `POST /api/v1/accounting/periods/{id}/lock|reopen|close` (task-06 lock/close POST probes; outputs captured).
- `psql -h localhost -p 55432 -U erp -d erp_domain -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/{06_inventory_valuation_fifo,07_inventory_control_vs_valuation,02_orphans_movements_without_journal,12_orphan_reservations,03_dispatch_slips_without_cogs_journal}.sql` (task-03 probes).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh` (task-03 accounting reports GETs).
- Targeted POST repros for LEAD-010/011 (sales order idempotency + purchase return retry; outputs saved under task-01/task-02 OUTPUTS).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/SQL/*.sql` (P2P probes; no rows returned).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-01/SQL/*.sql` (O2C probes; no rows returned).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/curl/*.sh` (admin GET probes; recon dashboard requires bankAccountId).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-01/curl/*.sh` (admin + dealer portal GET probes).
- `psql -h localhost -p 55432 -U erp -d erp_domain -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/00_company_lookup.sql` (company lookup).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/SQL/*.sql` (task-04 production/WIP probes + base movement/valuation checks).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql` (task-04 base orphan check).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql` (task-04 base valuation check).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/SQL/*.sql` (task-05 tax/rounding probes + GST snapshot).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/10_tax_and_totals_variances.sql` (task-05 base tax arithmetic probe).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh` (task-04/task-05 accounting reports).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/curl/01_production_gets.sh` (task-04 production GETs).
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/curl/01_tax_reports_gets.sh` (task-05 GST return GET).
- `curl -X POST /api/v1/accounting/accounts` + `PUT /api/v1/accounting/default-accounts` (seed WIP + discount defaults).
- `curl -X POST /api/v1/accounting/catalog/products` (seed FG product).
- `curl -X POST /api/v1/factory/production/logs` + `POST /api/v1/factory/packing-records` (seed production log + packing).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/SQL/{10_production_log_status,11_production_journal_lines}.sql` (production status + journal lines).
- `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/SQL/03_company_tax_accounts.sql` (GST config snapshot).
- `curl http://localhost:8081/actuator/health` (service health).
- `docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'` (container status).

## Resume Instructions (ERP logic audit)
1. Stay on branch `fix-phase5-lead015-and-lf011-014` (no worktrees).
2. Review `tasks/erp_logic_audit/README.md`, `tasks/erp_logic_audit/LOGIC_FLAWS.md`, and `tasks/erp_logic_audit/HUNT_NOTEBOOK.md` for Task-08 updates (LEAD-019/LEAD-020).
3. Decide follow-up for LEAD-019 (purchase return movement duplication) and LEAD-020 (idempotency conflict rejection policy).
4. Keep untracked logs under `docs/ops_and_debug/LOGS` and `interview/` untouched.
## 2026-01-13 lead resolution (LEAD-014/016)
- Branch: audit-inv-leads-014-016
- Tip SHA: 59d9ad6b48e6ec3d28f1c330fb335fbad6772436
- Commands executed (detail in RUN.md): docker compose up -d; curl/psql probes in `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014` and `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-016`; git add/commit for lead dispositions
- Evidence paths: `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/`; `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-016/OUTPUTS/`
- Resume steps: from repo root, run `docker compose up -d` with required env vars; rerun lead scripts if repro needed; continue from `tasks/erp_logic_audit/HUNT_NOTEBOOK.md`

## 2026-01-13 costing lead investigation (LEAD-COST-001/002/005)
- Branch (time of probe): `fix-phase5-lead015-and-lf011-014`
- Tip SHA (time of probe): `918636a890a6e06542e8ebb3d0fa881a59ab20de`
- Scope: LEAD-COST-001, LEAD-COST-002, LEAD-COST-005 only (investigation).
- Commands executed:
  - `psql -h localhost -p 55432 -U erp -d erp_domain -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/00_company_lookup.sql`
  - `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/SQL/*.sql`
  - `curl -X POST http://localhost:8081/api/v1/auth/login` (dev admin; token captured)
  - `curl -X POST http://localhost:8081/api/v1/factory/pack` twice with the same request (dev-only idempotency probe)
- Evidence paths:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/SQL/`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/`
- Disposition:
  - LEAD-COST-001 confirmed → LF-016
  - LEAD-COST-002 confirmed → LF-017
  - LEAD-COST-005 closed (material-only unit_cost policy)

## 2026-01-13 LF-016/LF-017 fix run (bulk packing idempotency + movement linkage)
- Branch (time of fix): `fix-phase5-lead015-and-lf011-014`
- Tip SHA (time of fix): `b4418f2b0f1377ebc8414f642b031bca3a2d4604`
- Design fork: chose deterministic pack reference + existing-movement lookup under `lockById` (PESSIMISTIC_WRITE) to enforce idempotency without adding a DB unique constraint/migration.
- Commands executed:
  - `docker compose build app` (rebuilt image with fixes)
  - `docker compose run -d --service-ports -e ERP_ENVIRONMENT_VALIDATION_ENABLED=false app`
  - `curl -X POST http://localhost:8081/api/v1/auth/login`
  - `curl -X POST http://localhost:8081/api/v1/factory/production/logs` (seed bulk batch)
  - `curl -X POST http://localhost:8081/api/v1/factory/pack` (twice, same request)
  - `psql -v company_id=5 -v pack_reference=... -f tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/SQL/*.sql`
- Tests executed:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (pass)
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (pass; 29651 warnings)
  - `mvn -f erp-domain/pom.xml test` (pass; 220 tests, 4 skipped)
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/BulkPackMovementIdempotencyRegressionIT.java`
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120613Z_production_log_create_for_bulk.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120701Z_sql_08_bulk_pack_reference_lookup_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_01_bulk_pack_child_receipts_missing_journal_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_02_bulk_pack_missing_bulk_issue_movement_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_04_bulk_pack_movements_vs_journals_linkage_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_07_bulk_pack_recent_journals_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_08_bulk_pack_movements_by_type_after_fix.txt`

## 2026-01-14 lead closure sweep (LEAD-001..009, LEAD-017)
- Branch: `fix-phase5-lead015-and-lf011-014`
- Tip SHA: `35340b7d31733cc9d58146409cfa0ab695e91fad`
- Scope: evidence-only lead closure; no production code changes.
- Changes:
  - LEAD-001/002/003/005/006/008/009 closed with evidence.
  - LEAD-004 confirmed → LF-019.
  - LEAD-007 confirmed → LF-020.
  - LEAD-017 confirmed → LF-018.
- Commands executed (detail in RUN.md per lead):
  - `docker compose up -d` with `DB_PORT=55432`, `APP_PORT=8081`, `MANAGEMENT_PORT=19090` (runtime).
  - `curl -X POST http://localhost:8081/api/v1/auth/login` (token for lead probes).
  - `curl` probes for `/api/v1/factory/unpacked-batches`, `/api/v1/factory/production/logs`, `/api/v1/raw-material-batches/{id}`, `/api/v1/payroll/summary/monthly`, `/api/v1/payroll/runs/*`.
  - `psql -h localhost -p 55432 -U erp -d erp_domain -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-0xx/SQL/*.sql`.
  - `rg -n` and `sed -n` for code anchor excerpts.
  - `docker logs erp_domain_app --since 10m` (lazy-load error capture).
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/20260114T080457Z_invoice_controller_excerpt.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/20260114T075548Z_production_log_over_issue_response.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/20260114T080510Z_dispatch_controller_confirm_excerpt.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-004/OUTPUTS/20260114T080023Z_monthly_summary.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-004/OUTPUTS/20260114T080049Z_monthly_run_lines.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/20260114T080314Z_journal_line_account_company_mismatch.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-006/OUTPUTS/20260114T080351Z_inventory_movement_event_search.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-007/OUTPUTS/20260114T080250Z_duplicate_batch_codes.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-008/OUTPUTS/20260114T080355Z_inventory_reval_event_search.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/20260114T080530Z_ar_ap_accounts.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-017/OUTPUTS/20260114T075446Z_unpacked_batches_get.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-017/OUTPUTS/20260114T075453Z_app_logs.txt`
- Tests: none (evidence-only run).

## 2026-01-14 LF-016/LF-017 verification rerun (bulk pack idempotency + movement linkage)
- Branch: `fix-phase5-lead015-and-lf011-014`
- Tip SHA: `f207e5f0c8dee1ce6c0be3b0910288e5c4ed76d2`
- Design fork reminder: deterministic pack reference + `lockById` (PESSIMISTIC_WRITE) for idempotency/concurrency; no DB unique constraint.
- Commands executed:
  - `curl -X POST http://localhost:8081/api/v1/auth/login`
  - `curl -X POST http://localhost:8081/api/v1/factory/production/logs` (seed bulk batch)
  - `curl -X POST http://localhost:8081/api/v1/factory/pack` (twice, same request)
  - `psql -v company_id=5 -v pack_reference=... -f tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/SQL/*.sql`
- Tests executed:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (pass)
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (pass; 29651 warnings)
  - `mvn -f erp-domain/pom.xml test` (pass; 220 tests, 4 skipped)
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/BulkPackMovementIdempotencyRegressionIT.java`
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084710Z_production_log_create_for_bulk.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084750Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084750Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084816Z_sql_08_bulk_pack_reference_lookup_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084831Z_sql_01_bulk_pack_child_receipts_missing_journal_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_02_bulk_pack_missing_bulk_issue_movement_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_04_bulk_pack_movements_vs_journals_linkage_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_07_bulk_pack_recent_journals_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_08_bulk_pack_movements_by_type_after_fix.txt`

## 2026-01-14 LEAD-018 confirmation (inventory reconciliation variance)
- Branch: `fix-phase5-lead015-and-lf011-014`
- Tip SHA: `979a0fefe451ebc94c80c7e0c0940d9f164cecc8`
- Outcome: LEAD-018 confirmed → LF-021 (inventory valuation vs ledger variance 9135).
- Commands executed:
  - `curl -X POST http://localhost:8081/api/v1/auth/login`
  - `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/{06_inventory_valuation_fifo,07_inventory_control_vs_valuation,02_orphans_movements_without_journal,12_orphan_reservations,03_dispatch_slips_without_cogs_journal}.sql`
  - `psql -c "select ... from companies c left join accounts a ..."` (inventory control account balance)
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090230Z_sql_06_inventory_valuation_fifo.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090230Z_sql_07_inventory_control_vs_valuation.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090237Z_accounting_reports_gets.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090337Z_sql_inventory_account_balance.txt`

## 2026-01-14 LEAD-019/LEAD-020 confirmation (task-08 idempotency rerun)
- Branch: `fix-phase5-lead015-and-lf011-014`
- Tip SHA: `398ad30ed8582e717dfbbc03d6692b249f1c7fad`
- Outcome: LEAD-019 confirmed → LF-022 (purchase return replay drifts RM stock); LEAD-020 confirmed → LF-023 (idempotency conflicts accepted).
- Commands executed:
  - `curl -X POST http://localhost:8081/api/v1/auth/login` (MOCK token)
  - `psql -v company_id=6 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/04_candidate_ids.sql`
  - `curl -X POST http://localhost:8081/api/v1/sales/orders` (base + conflict payload)
  - `curl -X POST http://localhost:8081/api/v1/hr/payroll-runs` (base + conflict payload)
  - `curl -X POST http://localhost:8081/api/v1/purchasing/raw-material-purchases/returns` (twice, same reference)
  - `psql -c "select id, sku, name, current_stock from raw_materials ..."` (stock before/after)
  - `psql -v reference=... -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/07_purchase_return_reference_check.sql`
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090838Z_sales_order_conflict_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090855Z_payroll_run_conflict_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090908Z_sql_raw_material_stock_before_return.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090922Z_sql_raw_material_stock_after_return_1.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090938Z_sql_raw_material_stock_after_return_2.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090944Z_sql_purchase_return_reference.txt`
