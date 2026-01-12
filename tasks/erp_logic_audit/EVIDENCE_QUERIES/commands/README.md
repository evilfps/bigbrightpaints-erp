# How to run evidence probes (read-only)

## SQL probes (Postgres)

1) Determine `company_id`:
- Run `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/00_company_lookup.sql`

2) Run a query with psql variables:
- Example:
  - `psql -v company_id=1 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql`

If you use Docker Compose with the repo’s DB container, typical pattern (adjust names/creds to match your env):
- `docker exec -e PGPASSWORD=erp erp_db psql -U erp -d erp_domain -v company_id=1 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql`

## GET-only curl probes

Prereqs:
- `jq` installed locally.
- `BASE_URL`, `TOKEN`, and `COMPANY_CODE` exported.

Example:
- `export BASE_URL=http://localhost:8080`
- `export TOKEN=...`
- `export COMPANY_CODE=BBP`
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`

