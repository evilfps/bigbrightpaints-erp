# Task 06 Evidence Run (Period Close / Lock / Reopen)

## SQL (read-only)

```bash
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/SQL/01_orphans_documents_without_journal.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/SQL/02_period_integrity_backdating_and_post_close_edits.sql
```

Pass/Fail:
- `01_orphans_documents_without_journal.sql`: **FAIL** if any rows return (posted docs missing JE links).
- `02_period_integrity_backdating_and_post_close_edits.sql`: **FAIL** if any rows return (posting/edit after close).

## curl (GET-only)

```bash
export BASE_URL=http://localhost:8081
export TOKEN=<JWT>
export COMPANY_CODE=<COMPANY_CODE>

bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/curl/01_accounting_reports_gets.sh
```

Pass/Fail:
- Month-end checklist/periods should reflect linkage/reconciliation issues; **REVIEW** any mismatch with SQL probes.

## Escalation probes (dev-only; controlled POST)
> Capture request/response JSON in `OUTPUTS/` and reference them here if used.

- Attempt journal entry posting dated into a closed/locked period without override (expect hard fail).
- Attempt journal entry posting with override (expect auditable acceptance or explicit rejection).
- Attempt reversal of journal in locked period (expect hard fail unless policy allows).

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
rg -n "AccountingPeriod" erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting
sed -n '220,320p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java
rg -n "journal" erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java | head
sed -n '120,190p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java
sed -n '1,160p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/dto/JournalEntryRequest.java
sed -n '210,300p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java
rg -n "validateEntryDate" erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java
sed -n '1730,1775p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java
rg -n "isPeriodLockEnforced" erp-domain/src/main/java
sed -n '60,120p' erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/SystemSettingsService.java
sed -n '1,80p' erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/SystemSettingsService.java
sed -n '214,260p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java
sed -n '80,150p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java
sed -n '150,220p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java
sed -n '256,380p' tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T083017Z_accounting_reports_gets.json
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -X POST -H 'Content-Type: application/json' -d '{"email":"admin@bbp.dev","password":"ChangeMe123!","companyCode":"BBP"}' http://localhost:8081/api/v1/auth/login > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_login.json"; jq -r '.accessToken' "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_login.json" > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_token.txt"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); TS=$(date -u +"%Y%m%dT%H%M%SZ"); BASE_URL=http://localhost:8081 COMPANY_CODE=BBP TOKEN="$TOKEN" bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/curl/01_accounting_reports_gets.sh > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_accounting_reports_gets.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/SQL/01_orphans_documents_without_journal.sql > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_sql_orphans_documents_without_journal.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/SQL/02_period_integrity_backdating_and_post_close_edits.sql > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_sql_period_integrity.txt"
ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS | head -n 20
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084442Z_sql_orphans_documents_without_journal.txt
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084442Z_sql_period_integrity.txt
rg -n "month-end|periods" tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084435Z_accounting_reports_gets.json
sed -n '256,340p' tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084435Z_accounting_reports_gets.json
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_open_request.json"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); REQ_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_open_request.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -w "\nHTTP_STATUS:%{http_code}\n" -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" -H 'Content-Type: application/json' -d @"${REQ_FILE}" http://localhost:8081/api/v1/accounting/journal-entries > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_open_response.json"
RESP_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_open_response.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); jq -r '.data.id' "${RESP_FILE}" > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_open_id.txt"; cat "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_open_id.txt"
sed -n '1,80p' $(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_open_response.json | head -n 1)
RESP_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_open_response.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); python - <<'PY' "${RESP_FILE}" > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_open_id.txt"
import json
import sys
path = sys.argv[1]
with open(path, 'r', encoding='utf-8') as f:
    content = ''.join(line for line in f if not line.startswith('HTTP_STATUS:'))
obj = json.loads(content)
print(obj.get('data', {}).get('id', ''))
PY
RESP_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_open_response.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed '$d' "${RESP_FILE}" | jq -r '.data.id' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_open_id.txt"; cat "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_open_id.txt"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_period_lock_request.json"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); REQ_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_period_lock_request.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -w "\nHTTP_STATUS:%{http_code}\n" -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" -H 'Content-Type: application/json' -d @"${REQ_FILE}" http://localhost:8081/api/v1/accounting/periods/6/lock > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_period_lock_response.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_locked_request.json"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); REQ_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_locked_request.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -w "\nHTTP_STATUS:%{http_code}\n" -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" -H 'Content-Type: application/json' -d @"${REQ_FILE}" http://localhost:8081/api/v1/accounting/journal-entries > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_locked_response.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_locked_override_request.json"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); REQ_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_locked_override_request.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -w "\nHTTP_STATUS:%{http_code}\n" -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" -H 'Content-Type: application/json' -d @"${REQ_FILE}" http://localhost:8081/api/v1/accounting/journal-entries > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_locked_override_response.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_reverse_request.json"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); ENTRY_ID=$(cat $(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_open_id.txt | head -n 1)); REQ_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_journal_reverse_request.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -w "\nHTTP_STATUS:%{http_code}\n" -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" -H 'Content-Type: application/json' -d @"${REQ_FILE}" http://localhost:8081/api/v1/accounting/journal-entries/${ENTRY_ID}/reverse > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_journal_reverse_response.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_period_reopen_request.json"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); REQ_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_period_reopen_request.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -w "\nHTTP_STATUS:%{http_code}\n" -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" -H 'Content-Type: application/json' -d @"${REQ_FILE}" http://localhost:8081/api/v1/accounting/periods/6/reopen > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_period_reopen_response.json"
TS=$(date -u +"%Y%m%dT%H%M%SZ"); cat <<'EOF' > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_period_close_request.json"
TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_token.txt | head -n 1); TOKEN=$(cat "$TOKEN_FILE"); REQ_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_period_close_request.json | head -n 1); TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -w "\nHTTP_STATUS:%{http_code}\n" -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" -H 'Content-Type: application/json' -d @"${REQ_FILE}" http://localhost:8081/api/v1/accounting/periods/6/close > "tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/${TS}_period_close_response.json"
ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS | head -n 30
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084648Z_period_lock_response.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084702Z_journal_locked_response.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084715Z_journal_locked_override_response.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084730Z_journal_reverse_response.json
cat tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084743Z_period_reopen_response.json
cat $(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/*_period_close_response.json | head -n 1)
ls tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS
git status -sb
```
- Notes:
  - `python` was not available; used `sed '$d'` + `jq` to parse `journal_open_response.json` after HTTP status append.
- Outputs captured:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084428Z_login.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084428Z_token.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084435Z_accounting_reports_gets.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084442Z_sql_orphans_documents_without_journal.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084442Z_sql_period_integrity.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084545Z_journal_open_request.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084554Z_journal_open_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084602Z_journal_open_id.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084625Z_journal_open_id.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084632Z_journal_open_id.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084639Z_period_lock_request.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084648Z_period_lock_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084656Z_journal_locked_request.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084702Z_journal_locked_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084709Z_journal_locked_override_request.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084715Z_journal_locked_override_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084721Z_journal_reverse_request.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084730Z_journal_reverse_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084735Z_period_reopen_request.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084743Z_period_reopen_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084847Z_period_close_request.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084854Z_period_close_response.json`
