# LF-012 Evidence Run (WIP over-credit on labor/overhead)

## Objective
Show WIP debits and credits remain balanced when labor/overhead are provided on production logs.

## Planned probes
1) Run WIP delta SQL to show historical mismatch (pre-fix logs).
2) Restock raw material for new log.
3) Create new production log with labor/overhead.
4) Run WIP delta for the new log (expect zero delta).

## Command log
```bash
# Historical mismatch (pre-fix log still present)
TS=$(date -u +"%Y%m%dT%H%M%SZ")
PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -v company_id=5 \
  -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/SQL/01_production_wip_debit_credit_delta.sql \
  > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/OUTPUTS/${TS}_wip_delta_after_fix.txt"

# Restock raw material (id 1)
TS=$(date -u +"%Y%m%dT%H%M%SZ")
PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -c "INSERT INTO raw_material_batches (raw_material_id, batch_code, quantity, unit, cost_per_unit)
       VALUES (1, 'AUDIT-RESTOCK-${TS}', 10, 'KG', 5.00);
       UPDATE raw_materials SET current_stock = current_stock + 10 WHERE id = 1;"

# Create production log with labor/overhead
TS=$(date -u +"%Y%m%dT%H%M%SZ")
REQUEST_FILE="tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/OUTPUTS/${TS}_production_log_request.json"
cat > "$REQUEST_FILE" <<'REQ'
{
  "brandId": 2,
  "productId": 3,
  "batchSize": 5,
  "unitOfMeasure": "KG",
  "mixedQuantity": 5,
  "producedAt": "2026-01-13",
  "createdBy": "Evidence",
  "laborCost": 2.0,
  "overheadCost": 1.0,
  "materials": [
    {
      "rawMaterialId": 1,
      "quantity": 5,
      "unitOfMeasure": "KG"
    }
  ]
}
REQ

TOKEN_FILE=$(ls -t tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/*_token_after_fix.txt | head -n 1)
TOKEN=$(cat "$TOKEN_FILE")
BASE_URL=http://localhost:8081 COMPANY_CODE=BBP TOKEN="$TOKEN" REQUEST_FILE="$REQUEST_FILE" \
  tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/curl/01_production_log_create.sh \
  > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/OUTPUTS/${TS}_production_log_create_after_fix.txt"

# WIP delta for new log (id 4)
TS=$(date -u +"%Y%m%dT%H%M%SZ")
PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -c "WITH log AS (
          SELECT pl.id, pl.production_code, pl.material_cost_total, pl.labor_cost_total, pl.overhead_cost_total,
                 NULLIF(pp.metadata->>'wipAccountId','')::bigint AS wip_account_id
          FROM production_logs pl
          JOIN production_products pp ON pp.id = pl.product_id
          WHERE pl.id = 4
        )
        SELECT log.id, log.production_code, log.material_cost_total, log.labor_cost_total, log.overhead_cost_total,
               log.wip_account_id,
               COALESCE(rm.wip_debit, 0) AS wip_debit_from_rm,
               COALESCE(sf.wip_credit, 0) AS wip_credit_from_semifg,
               (COALESCE(rm.wip_debit, 0) - COALESCE(sf.wip_credit, 0)) AS wip_delta
        FROM log
        LEFT JOIN LATERAL (
          SELECT SUM(jl.debit) AS wip_debit
          FROM journal_entries je
          JOIN journal_lines jl ON jl.journal_entry_id = je.id
          WHERE je.company_id = 5
            AND je.reference_number = log.production_code || '-RM'
            AND jl.account_id = log.wip_account_id
        ) rm ON true
        LEFT JOIN LATERAL (
          SELECT SUM(jl.credit) AS wip_credit
          FROM journal_entries je
          JOIN journal_lines jl ON jl.journal_entry_id = je.id
          WHERE je.company_id = 5
            AND je.reference_number = log.production_code || '-SEMIFG'
            AND jl.account_id = log.wip_account_id
        ) sf ON true;" \
  > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/OUTPUTS/${TS}_wip_delta_for_log_after_fix.txt"
```
