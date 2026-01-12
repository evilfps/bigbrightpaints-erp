# Task 03 Evidence Run (Inventory Valuation + COGS)

## SQL (read-only)

```bash
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/06_inventory_valuation_fifo.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/12_orphan_reservations.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/03_dispatch_slips_without_cogs_journal.sql
```

Pass/Fail:
- `06_inventory_valuation_fifo.sql`: **FAIL** if negative or clearly inconsistent valuation rows appear for current stock.
- `07_inventory_control_vs_valuation.sql`: **FAIL** if variance is outside tolerance (target = 0.00).
- `02_orphans_movements_without_journal.sql`: **FAIL** if any rows return.
- `12_orphan_reservations.sql`: **FAIL** if any rows return.
- `03_dispatch_slips_without_cogs_journal.sql`: **FAIL** if any rows return.

## curl (GET-only)

```bash
export BASE_URL=http://localhost:8081
export TOKEN=<JWT>
export COMPANY_CODE=<COMPANY_CODE>

bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh
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
- Accounting reports: **PASS** if inventory valuation + reconciliation align with ledger expectations and checklist is green.
