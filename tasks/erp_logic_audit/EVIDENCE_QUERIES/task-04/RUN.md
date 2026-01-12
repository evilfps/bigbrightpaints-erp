# Task 04 Evidence Run (Production Costing + WIP)

## SQL (read-only)

```bash
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/SQL/01_production_rm_cost_vs_log.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/SQL/02_production_wip_debit_credit_delta.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/SQL/03_production_receipt_journal_mismatch.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/SQL/04_production_wastage_missing_journal.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/SQL/05_packaging_movements_missing_journal.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql
```

Pass/Fail:
- `01_production_rm_cost_vs_log.sql`: **FAIL** if any rows return (material costs drift).
- `02_production_wip_debit_credit_delta.sql`: **FLAG** if rows return; check if WIP debits/credits are expected to differ for labor/overhead.
- `03_production_receipt_journal_mismatch.sql`: **FAIL** if any rows return (receipt not linked to expected journal).
- `04_production_wastage_missing_journal.sql`: **FAIL** if any rows return.
- `05_packaging_movements_missing_journal.sql`: **FAIL** if any rows return.
- `02_orphans_movements_without_journal.sql`: **FAIL** if any rows return.
- `07_inventory_control_vs_valuation.sql`: **FAIL** if variance outside tolerance (target = 0.00).

## curl (GET-only)

```bash
export BASE_URL=http://localhost:8081
export TOKEN=<JWT>
export COMPANY_CODE=<COMPANY_CODE>

bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh
bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/curl/01_production_gets.sh
```

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

Pass/Fail:
- Production endpoints: **PASS** if production logs/packing history are readable and consistent with SQL references.
- Accounting reports: **PASS** if inventory valuation + reconciliation align with ledger expectations.
