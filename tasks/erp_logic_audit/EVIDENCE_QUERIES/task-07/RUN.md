# Task 07 Evidence Run (Tenancy / RBAC / Portal Entrypoints)

## SQL (read-only)

```bash
psql -v company_id=<COMPANY_ID> -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-07/SQL/01_tenancy_cross_company_links.sql
psql -f tasks/erp_logic_audit/EVIDENCE_QUERIES/task-07/SQL/02_orchestrator_audit_schema.sql
```

Pass/Fail:
- `01_tenancy_cross_company_links.sql`: **PASS** if all result sets are empty. **FAIL** if any rows return (cross-company link detected).
- `02_orchestrator_audit_schema.sql`: **FAIL** if `orchestrator_audit` has no `company_id` column (trace data is not company-scoped).

## curl (GET-only)

```bash
export BASE_URL=http://localhost:8080
export TOKEN=<JWT>
export COMPANY_CODE=<COMPANY_CODE>

bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-07/curl/01_dealer_portal_gets.sh
bash tasks/erp_logic_audit/EVIDENCE_QUERIES/task-07/curl/02_orchestrator_health_gets.sh
```

Pass/Fail:
- Dealer portal GETs: **PASS** if dealer sees only their own data. **FAIL** if another dealer/company data is visible.
- Orchestrator health GETs: **PASS** if endpoints are reachable only with authenticated tokens; record counts for backlog review.

## Targeted repro (dev-only; optional)
- Generate a trace in Company A (e.g., POST `/api/v1/orchestrator/dispatch`), capture `traceId`.
- Use a user from Company B and call `GET /api/v1/orchestrator/traces/{traceId}`.
- **FAIL** if trace is returned without company scoping.
