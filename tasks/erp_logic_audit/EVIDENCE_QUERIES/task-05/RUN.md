# Task 05 Evidence Run (Tax + Rounding)

## SQL (read-only)

```bash
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/10_tax_and_totals_variances.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/SQL/01_invoice_tax_vs_journal_tax.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/SQL/02_gst_return_journal_snapshot.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/SQL/03_company_tax_accounts.sql
```

Pass/Fail:
- `10_tax_and_totals_variances.sql`: **FLAG** any rows; verify rounding policy vs arithmetic defects.
- `01_invoice_tax_vs_journal_tax.sql`: **FAIL** if any rows return (invoice tax != journal tax).
- `02_gst_return_journal_snapshot.sql`: **PASS** if output/input/net align with `/api/v1/accounting/gst/return`.
- `03_company_tax_accounts.sql`: **INFO** (GST account configuration visibility).

## curl (GET-only)

```bash
export BASE_URL=http://localhost:8081
export TOKEN=<JWT>
export COMPANY_CODE=<COMPANY_CODE>

bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh
bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/curl/01_tax_reports_gets.sh
```

Pass/Fail:
- Config health should reflect missing GST account configuration; **FAIL** if health is reported as OK while `/api/v1/accounting/gst/return` fails due to missing accounts.

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
