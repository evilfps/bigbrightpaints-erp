# LEAD-009 Evidence Run

## Objective
Review AR/AP reconciliation account selection logic and verify current COA alignment.

## Command log
```bash
# Current AR/AP accounts by code pattern
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/SQL/01_ar_ap_accounts.sql \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/${TS}_ar_ap_accounts.txt

# Reconciliation service code filters
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '40,120p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/ReconciliationService.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/${TS}_reconciliation_service_ar_filter_excerpt.txt
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '120,200p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/ReconciliationService.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/${TS}_reconciliation_service_ap_filter_excerpt.txt
```

## Outputs captured
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/*_ar_ap_accounts.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/*_reconciliation_service_ar_filter_excerpt.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/*_reconciliation_service_ap_filter_excerpt.txt`
