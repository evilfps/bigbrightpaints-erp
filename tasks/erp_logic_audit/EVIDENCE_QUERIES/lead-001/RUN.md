# LEAD-001 Evidence Run

## Objective
Validate whether invoice/purchase creation can persist an `outstanding_amount=0` or if the system always forces outstanding to total at creation.

## Command log
```bash
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '80,120p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/Invoice.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/${TS}_invoice_prepersist_excerpt.txt
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '1,120p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/dto/RawMaterialPurchaseRequest.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/${TS}_raw_material_purchase_request.txt
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '130,170p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/${TS}_purchasing_service_outstanding_amount.txt
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '1,120p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/controller/InvoiceController.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/${TS}_invoice_controller_excerpt.txt
TS=$(date -u +"%Y%m%dT%H%M%SZ"); sed -n '90,140p' erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/${TS}_invoice_service_outstanding_amount.txt
```

## Outputs captured
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/*_invoice_prepersist_excerpt.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/*_raw_material_purchase_request.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/*_purchasing_service_outstanding_amount.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/*_invoice_controller_excerpt.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/*_invoice_service_outstanding_amount.txt`
