# LF-008 Evidence Run (orchestrator traces company-scoped)

## Objective
Show orchestrator audit records are stored with company_id and can be filtered by company.

## Command log
```bash
# Schema + index inspection
TS=$(date -u +"%Y%m%dT%H%M%SZ")
PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-07/SQL/02_orchestrator_audit_schema.sql \
  > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008/OUTPUTS/${TS}_orchestrator_audit_schema.txt"

# Insert a trace for company A and verify company filter
TS=$(date -u +"%Y%m%dT%H%M%SZ")
PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008/SQL/01_orchestrator_trace_company_scope.sql \
  > "tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008/OUTPUTS/${TS}_orchestrator_trace_company_scope.txt"
```
