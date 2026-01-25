# Constraint vs Repository Assumptions (Task 00 EPIC E1)

## Mismatches (candidate hardening)

- `journal_reference_mappings`: DB enforces uniqueness on `(company_id, legacy_reference)` only; `canonical_reference` is indexed but not unique, while repository exposes `Optional<...> findByCompanyAndCanonicalReferenceIgnoreCase(...)`.  
  Files: `erp-domain/src/main/resources/db/migration/V88__journal_reference_mappings.sql`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalReferenceMappingRepository.java`

- `packaging_slips`: DB enforces uniqueness on `(company_id, slip_number)` only; `sales_order_id` is not unique, while repository exposes `Optional<...> findByCompanyAndSalesOrderId(...)` and `findAndLockBySalesOrderId(...)`.  
  Files: `erp-domain/src/main/resources/db/migration/V9__finished_goods_inventory.sql`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/PackagingSlipRepository.java`

- `invoices`: DB enforces uniqueness on `(company_id, invoice_number)` only; `sales_order_id` is not unique, while repository exposes `Optional<...> findByCompanyAndSalesOrderId(...)` and `lockByCompanyAndSalesOrderId(...)`.  
  Files: `erp-domain/src/main/resources/db/migration/V12__invoices.sql`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/InvoiceRepository.java`

- `inventory_movements`: DB has no uniqueness constraint on `(reference_type, reference_id, movement_type, finished_good_id)`; idempotency relies on service-layer guards.  
  Files: `erp-domain/src/main/resources/db/migration/V9__finished_goods_inventory.sql`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryMovementRepository.java`
