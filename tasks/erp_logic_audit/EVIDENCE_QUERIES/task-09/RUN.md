# Task 09 Evidence Run (Ops Failure Modes)

## SQL (read-only)

```bash
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/01_orphans_documents_without_journal.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/02_journals_without_document_link.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/03_period_integrity_backdating_and_post_close_edits.sql
psql -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/04_outbox_backlog_and_duplicates.sql
```

Pass/Fail:
- `01_orphans_documents_without_journal.sql`: **FAIL** if any rows return (posted docs missing JE links).
- `02_journals_without_document_link.sql`: **REVIEW** if rows return (verify if truly system-generated journals).
- `03_period_integrity_backdating_and_post_close_edits.sql`: **FAIL** if any rows return (posting/edit after close).
- `04_outbox_backlog_and_duplicates.sql`: **REVIEW** if duplicate rows appear for the same (aggregate_type, aggregate_id, event_type) or backlog spikes.

## curl (GET-only)

```bash
export BASE_URL=http://localhost:8081
export TOKEN=<JWT>
export COMPANY_CODE=<COMPANY_CODE>

bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/01_health_gets.sh
bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/02_orchestrator_health_gets.sh
bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/03_accounting_reports_gets.sh
```

Pass/Fail:
- Health endpoints: **FAIL** if `/actuator/health` is UP while config health is missing required defaults for posting.
- Orchestrator health: **PASS** if counts are visible and stable; **REVIEW** on sustained pending/retrying/dead-letter spikes.

## Auth helper (admin JWT)

```bash
export BASE_URL=http://localhost:8081
export COMPANY_CODE=<COMPANY_CODE>
export ADMIN_EMAIL=<ADMIN_EMAIL>
export ADMIN_PASSWORD=<ADMIN_PASSWORD>

TOKEN=$(curl -sS -X POST -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\",\"companyCode\":\"${COMPANY_CODE}\"}" \
  "${BASE_URL}/api/v1/auth/login" | jq -r '.accessToken')
```

## Run log

- Commands executed:
```bash
git status -sb
git rev-parse --abbrev-ref HEAD
git rev-parse HEAD
git checkout -b audit-inv-09-06-ops-close
cat /home/realnigga/.codex/skills/erp-release-readiness-audit/SKILL.md
cat /home/realnigga/.codex/skills/erp-release-readiness-audit/references/release-readiness-checklist.md
cat /home/realnigga/.codex/skills/erp-reconciliation-close-audit/SKILL.md
cat /home/realnigga/.codex/skills/erp-reconciliation-close-audit/references/reconciliation-review-guide.md
cat tasks/erp_logic_audit/taskpack_investigation/task-09-ops-failure-modes-hunt.md
cat tasks/erp_logic_audit/taskpack_investigation/task-06-period-close-adjustments-hunt.md
rg -n "LF-01[1-4]" tasks/erp_logic_audit/LOGIC_FLAWS.md
rg -n "LEAD-014|LEAD-015" tasks/erp_logic_audit/HUNT_NOTEBOOK.md
rg -n "LEAD-014" tasks/erp_logic_audit/HUNT_NOTEBOOK.md
sed -n '1,160p' tasks/erp_logic_audit/HUNT_NOTEBOOK.md
cat SCOPE.md
cat .codex/AGENTS.md
ls -a tasks/erp_logic_audit/EVIDENCE_QUERIES
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/00_health_gets.sh
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/03_orchestrator_health_gets.sh
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/04_journals_without_document_link.sql
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/08_period_integrity_backdating_and_post_close_edits.sql
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/13_outbox_backlog_and_duplicates.sql
ls tasks/erp_logic_audit/EVIDENCE_QUERIES/curl
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/README.md
rg --files -g '*login*' tasks/erp_logic_audit
rg -n "login|auth" tasks/erp_logic_audit -g "*.md"
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/RUN.md
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/README.md
cat docker-compose.yml
cat .env.example
rg -n "ERP_SECURITY_ENCRYPTION_KEY"
sed -n '1,200p' docs/ops_and_debug/EVIDENCE.md
ls -a tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/RUN.md
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-07/RUN.md
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/01_idempotency_duplicates.sql
rg -n "/actuator/health" docs/ops_and_debug -g "*.md"
rg -n "management" erp-domain/src/main/resources/application*.yml
sed -n '60,120p' erp-domain/src/main/resources/application.yml
sed -n '70,120p' erp-domain/src/main/resources/application-prod.yml
sed -n '35,90p' erp-domain/src/main/resources/application-dev.yml
rg -n "seed" tasks/erp_logic_audit -g "*.md"
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/RUN.md
rg -n "seed" erp-domain/src/main/resources -g "application*.yml"
rg -n "seed|Seeder" erp-domain/src/main/java
sed -n '1,140p' erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java
sed -n '1,80p' erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/MockDataInitializer.java
sed -n '1,80p' erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/BbpSampleDataInitializer.java
rg -n "actuator|management" erp-domain/src/main/java/com/bigbrightpaints/erp/core/security
sed -n '1,140p' erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java
mkdir -p tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/{SQL,curl,OUTPUTS} tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/{SQL,curl,OUTPUTS}
cat <<'EOF' > tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/RUN.md
cat <<'EOF' > tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/RUN.md
cp tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/01_orphans_documents_without_journal.sql
cp tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/04_journals_without_document_link.sql tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/02_journals_without_document_link.sql
cp tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/08_period_integrity_backdating_and_post_close_edits.sql tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/03_period_integrity_backdating_and_post_close_edits.sql
cp tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/13_outbox_backlog_and_duplicates.sql tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/04_outbox_backlog_and_duplicates.sql
cp tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/SQL/01_orphans_documents_without_journal.sql
cp tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/08_period_integrity_backdating_and_post_close_edits.sql tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/SQL/02_period_integrity_backdating_and_post_close_edits.sql
cat <<'EOF' > tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/01_health_gets.sh
cat <<'EOF' > tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/02_orchestrator_health_gets.sh
cat <<'EOF' > tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/03_accounting_reports_gets.sh
cat <<'EOF' > tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/curl/01_accounting_reports_gets.sh
chmod +x tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/01_health_gets.sh tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/02_orchestrator_health_gets.sh tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/03_accounting_reports_gets.sh tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/curl/01_accounting_reports_gets.sh
TS=$(date -u +"%Y%m%dT%H%M%SZ"); JWT_SECRET='dev-jwt-secret-32bytes-0123456789abcdef' ERP_SECURITY_ENCRYPTION_KEY='dev-encryption-key-32bytes-0123456789abcdef' MANAGEMENT_PORT=19090 SPRING_PROFILES_ACTIVE=prod,seed docker compose up -d > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_compose_up.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); DB_PORT=55432 JWT_SECRET='dev-jwt-secret-32bytes-0123456789abcdef' ERP_SECURITY_ENCRYPTION_KEY='dev-encryption-key-32bytes-0123456789abcdef' MANAGEMENT_PORT=19090 SPRING_PROFILES_ACTIVE=prod,seed docker compose up -d > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_compose_up_retry.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); docker compose ps > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_compose_ps.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); DB_PORT=55432 JWT_SECRET='dev-jwt-secret-32bytes-0123456789abcdef' ERP_SECURITY_ENCRYPTION_KEY='dev-encryption-key-32bytes-0123456789abcdef' MANAGEMENT_PORT=19090 SPRING_PROFILES_ACTIVE=prod,seed docker compose ps > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_compose_ps.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS http://localhost:19090/actuator/health > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_actuator_health.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS http://localhost:19090/actuator/health/readiness > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_actuator_readiness.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS http://localhost:19090/actuator/health/liveness > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_actuator_liveness.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS http://localhost:8081/api/integration/health > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_integration_health.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); BASE_URL=http://localhost:8081 bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/01_health_gets.sh > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_health_gets_app_port.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -X POST -H 'Content-Type: application/json' -d '{"email":"admin@bbp.dev","password":"ChangeMe123!","companyCode":"BBP"}' http://localhost:8081/api/v1/auth/login > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_login.json"; jq -r '.accessToken' "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_login.json" > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_token.txt"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); TS=$(date -u +"%Y%m%dT%H%M%SZ"); BASE_URL=http://localhost:8081 COMPANY_CODE=BBP TOKEN="$TOKEN" bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/02_orchestrator_health_gets.sh > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_orchestrator_health_gets.json"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); TS=$(date -u +"%Y%m%dT%H%M%SZ"); BASE_URL=http://localhost:8081 COMPANY_CODE=BBP TOKEN="$TOKEN" bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/curl/03_accounting_reports_gets.sh > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_accounting_reports_gets.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain -t -A -c "select id from companies where code = 'BBP';" > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_company_id.txt"
cat $(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/*_company_id.txt | head -n 1)
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/01_orphans_documents_without_journal.sql > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_sql_orphans_documents_without_journal.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/02_journals_without_document_link.sql > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_sql_journals_without_document_link.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/03_period_integrity_backdating_and_post_close_edits.sql > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_sql_period_integrity.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/SQL/04_outbox_backlog_and_duplicates.sql > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_sql_outbox_backlog_and_duplicates.txt"
ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS | head -n 20
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083043Z_sql_orphans_documents_without_journal.txt
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083043Z_sql_journals_without_document_link.txt
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083043Z_sql_period_integrity.txt
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083043Z_sql_outbox_backlog_and_duplicates.txt
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082939Z_actuator_health.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082940Z_actuator_readiness.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082940Z_actuator_liveness.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082943Z_integration_health.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082949Z_health_gets_app_port.txt
head -n 80 tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083017Z_accounting_reports_gets.json
rg -n "month-end|periods" tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083017Z_accounting_reports_gets.json
sed -n '158,260p' tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083017Z_accounting_reports_gets.json
rg -n "dispatchMapping" erp-domain/src/main/java
rg -n "dispatch" erp-domain/src/main/java/com/bigbrightpaints/erp/core/health
rg -n "Dispatch" erp-domain/src/main/java/com/bigbrightpaints/erp/core/health
rg -n "dispatchMapping|DispatchMapping|dispatch debit|dispatch credit" erp-domain/src/main/java
sed -n '1,120p' erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/config/DispatchMappingHealthIndicator.java
rg -n "confirmDispatch" erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java
sed -n '980,1070p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java
rg -n "confirmDispatch|dispatch" erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java
sed -n '560,760p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java
sed -n '760,900p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java
rg -n "cogs|journal" erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java | head
sed -n '1260,1395p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -H "Authorization: Bearer ${TOKEN}" http://localhost:19090/actuator/health > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_actuator_health_authed.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -H "Authorization: Bearer ${TOKEN}" http://localhost:19090/actuator/health/readiness > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_actuator_readiness_authed.json"
cat $(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/*_actuator_health_authed.json | head -n 1)
cat $(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/*_actuator_readiness_authed.json | head -n 1)
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" http://localhost:8081/api/v1/accounting/default-accounts > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_default_accounts.json"
cat $(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/*_default_accounts.json | head -n 1)
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_compose_up_port_5432_error.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/${TS}_compose_ps_missing_env_error.txt"
ls tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/RUN.md
git status -sb
```
- Outputs captured:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082846Z_compose_up.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082910Z_compose_up_retry.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082923Z_compose_ps.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082930Z_compose_ps.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082939Z_actuator_health.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082940Z_actuator_liveness.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082940Z_actuator_readiness.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082943Z_integration_health.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082949Z_health_gets_app_port.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083003Z_login.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083003Z_token.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083012Z_orchestrator_health_gets.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083017Z_accounting_reports_gets.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083026Z_company_id.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083043Z_sql_journals_without_document_link.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083043Z_sql_orphans_documents_without_journal.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083043Z_sql_outbox_backlog_and_duplicates.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083043Z_sql_period_integrity.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083138Z_actuator_health_authed.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083138Z_actuator_readiness_authed.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083257Z_default_accounts.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083532Z_compose_up_port_5432_error.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083538Z_compose_ps_missing_env_error.txt`
