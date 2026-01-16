# LF-007 Evidence Run (payroll idempotency scoped by company)

## Objective
Show the same payroll idempotency key can exist across two companies without conflict.

## Command log
```bash
TS=$(date -u +"%Y%m%dT%H%M%SZ")
PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-007/SQL/01_payroll_idempotency_cross_company.sql \
  > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-007/OUTPUTS/${TS}_payroll_idempotency_cross_company.txt"
```
