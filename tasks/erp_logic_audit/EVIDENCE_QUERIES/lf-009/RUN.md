# LF-009 Evidence Run (settlement idempotency multi-allocation)

## Objective
Show partner settlements can store multiple allocations under the same idempotency key.

## Command log
```bash
# Index inspection
TS=$(date -u +"%Y%m%dT%H%M%SZ")
PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/03_partner_settlement_idempotency_index.sql \
  > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009/OUTPUTS/${TS}_settlement_idempotency_indexes.txt"

# Insert two allocations under the same idempotency key
TS=$(date -u +"%Y%m%dT%H%M%SZ")
PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009/SQL/01_settlement_multi_alloc_idempotency.sql \
  > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009/OUTPUTS/${TS}_settlement_multi_alloc_idempotency.txt"
```
