# LEAD-002 Evidence Run

## Objective
Verify whether raw material over-issue fails closed (expected) or silently clamps stock to zero.

## Command log
```bash
# Check RM stock (raw_material_id=1)
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -v raw_material_id=1 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/SQL/01_raw_material_stock.sql \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/${TS}_raw_material_stock.txt

# Login
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -X POST -H 'Content-Type: application/json' \
  -d '{"email":"admin@bbp.dev","password":"ChangeMe123!","companyCode":"BBP"}' \
  http://localhost:8081/api/v1/auth/login \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/${TS}_login.json
jq -r '.accessToken' tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/${TS}_login.json \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/${TS}_token.txt

# Attempt production log with material quantity > available
TOKEN=$(cat tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/*_token.txt | tail -n 1)
REQ=$(ls tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/*_production_log_request_over_issue.json | tail -n 1)
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -w "\nHTTP_STATUS:%{http_code}\n" -X POST \
  -H "Authorization: Bearer ${TOKEN}" -H "X-Company-Id: BBP" -H 'Content-Type: application/json' \
  --data @"${REQ}" http://localhost:8081/api/v1/factory/production/logs \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/${TS}_production_log_over_issue_response.txt

# Re-check RM stock
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -v raw_material_id=1 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/SQL/01_raw_material_stock.sql \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/${TS}_raw_material_stock_after_over_issue.txt
```

## Outputs captured
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/*_raw_material_stock.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/*_production_log_request_over_issue.json`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/*_production_log_over_issue_response.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/*_raw_material_stock_after_over_issue.txt`
