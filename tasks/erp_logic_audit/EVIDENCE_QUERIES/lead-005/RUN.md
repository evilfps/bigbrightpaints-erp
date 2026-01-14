# LEAD-005 Evidence Run

## Objective
Check for cross-company account linkage in journal lines and document event-handler account scoping.

## Command log
```bash
# Cross-company account usage in journal lines
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/SQL/01_journal_line_account_company_mismatch.sql \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/${TS}_journal_line_account_company_mismatch.txt

# Capture event listener account lookups (no company filter)
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '70,120p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/${TS}_inventory_accounting_event_listener_excerpt.txt
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '150,210p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/${TS}_inventory_movement_event_listener_excerpt.txt
```

## Outputs captured
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/*_journal_line_account_company_mismatch.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/*_inventory_accounting_event_listener_excerpt.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/*_inventory_movement_event_listener_excerpt.txt`
