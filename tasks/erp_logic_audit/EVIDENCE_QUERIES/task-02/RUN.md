# Task 02 Evidence Run (P2P Logic Hunt)

## SQL (read-only)

```bash
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/SQL/01_p2p_purchase_missing_je.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/SQL/02_p2p_rm_movements_missing_je.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/SQL/03_p2p_ap_tieout.sql
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/SQL/04_p2p_settlement_idempotency_duplicates.sql
```

Pass/Fail:
- `01_p2p_purchase_missing_je.sql`: **FAIL** if any rows return.
- `02_p2p_rm_movements_missing_je.sql`: **FAIL** if any rows return.
- `03_p2p_ap_tieout.sql`: **FAIL** if variance is outside tolerance (target = 0.00).
- `04_p2p_settlement_idempotency_duplicates.sql`: **FAIL** if any rows return.

## curl (GET-only)

```bash
export BASE_URL=http://localhost:8080
export TOKEN=<JWT>
export COMPANY_CODE=<COMPANY_CODE>

bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/curl/01_p2p_accounting_reports_gets.sh
bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/curl/02_p2p_purchases_gets.sh
```

## Auth helper (admin JWT)

```bash
export BASE_URL=http://localhost:8080
export COMPANY_CODE=<COMPANY_CODE>
export ADMIN_EMAIL=<ADMIN_EMAIL>
export ADMIN_PASSWORD=<ADMIN_PASSWORD>

TOKEN=$(curl -sS -X POST -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\",\"companyCode\":\"${COMPANY_CODE}\"}" \
  "${BASE_URL}/api/v1/auth/login" | jq -r '.accessToken')
```

Note:
- `/api/v1/reports/reconciliation-dashboard` requires `bankAccountId` as a query param; omit it and you will see a 400 response.

Pass/Fail:
- Accounting reports: **PASS** if AP tie-outs and checklist status align with ledger expectations.
- Purchases list: **PASS** if purchase status + journal linkage look consistent for recent entries.

## Targeted repro (dev-only; optional)
- Retry purchase return submission without a stable `referenceNumber` and compare:
  - `journal_entries` reference numbers for purchase returns
  - `raw_material_movements` counts for `reference_type = PURCHASE_RETURN`
- **FAIL** if duplicates are created on retry.
