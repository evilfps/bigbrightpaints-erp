# LEAD-003 Evidence Run

## Objective
Assess whether `/api/v1/dispatch/confirm` double-invokes confirmation logic and causes duplicate side effects.

## Command log
```bash
# Check pending slips (company_id=5)
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/SQL/01_pending_packaging_slips.sql \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/${TS}_pending_packaging_slips.txt

# Login
TS=$(date -u +"%Y%m%dT%H%M%SZ"); curl -sS -X POST -H 'Content-Type: application/json' \
  -d '{"email":"admin@bbp.dev","password":"ChangeMe123!","companyCode":"BBP"}' \
  http://localhost:8081/api/v1/auth/login \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/${TS}_login.json
jq -r '.accessToken' tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/${TS}_login.json \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/${TS}_token.txt

# Fetch slip details (slip_id=2)
TOKEN=$(cat tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/*_token.txt | tail -n 1)
TS=$(date -u +"%Y%m%dT%H%M%SZ"); BASE_URL=http://localhost:8081 COMPANY_CODE=BBP TOKEN="$TOKEN" SLIP_ID=2 \
  bash tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/curl/01_slip_get.sh \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/${TS}_dispatch_slip_2.txt

# Capture confirm/guard logic in code
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '80,160p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/${TS}_dispatch_controller_confirm_excerpt.txt
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '1260,1335p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/${TS}_sales_service_confirm_dispatch_excerpt.txt
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '590,640p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/${TS}_finished_goods_confirm_dispatch_excerpt.txt
```

## Outputs captured
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/*_pending_packaging_slips.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/*_dispatch_slip_2.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/*_dispatch_controller_confirm_excerpt.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/*_sales_service_confirm_dispatch_excerpt.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/*_finished_goods_confirm_dispatch_excerpt.txt`
