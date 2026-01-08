# Cross-Module Linkage Matrix (Epic 10)

This matrix documents the expected linkage chains across modules and the
verification points that must exist in tests. It reflects current behavior
only; no new flows are introduced.

## Order-to-Cash (O2C)
Chain: Sales Order -> Packaging Slip/Dispatch -> Inventory Movements -> Invoice -> Journal Entry -> Dealer Ledger.

Expected linkage checks:
- Sales order references packaging slips and the fulfillment invoice.
- Packaging slip references invoice and both AR/COGS journal entries after dispatch.
- Inventory movements reference the dispatch/slip and journal entry where applicable.
- Invoice references sales order and AR journal entry; totals balance.
- Dealer ledger entry references the AR journal entry and invoice identifiers.

Test expectations (current + to enforce):
- Sales fulfillment/dispatch E2E should assert slip/invoice/journal IDs after dispatch.
- Dealer ledger E2E should assert ledger entries map to the same invoice/journal.
- Full-cycle or regression E2E should assert order -> invoice -> journal -> ledger chain.

## Procure-to-Pay (P2P)
Chain: Purchase/Intake -> Raw Material Movements -> Supplier Settlement/Payment -> Journal Entry -> Supplier Ledger.

Expected linkage checks:
- Raw material purchase links to intake movement(s) and purchase journal entry.
- Raw material movements reference the purchase or purchase return and journal entry.
- Supplier settlement allocations link to purchase(s) and settlement journal entry.
- Supplier ledger entry references the settlement journal entry and supplier context.

Test expectations (current + to enforce):
- Purchasing E2E should assert purchase -> movements -> journal linkage.
- Supplier statement/settlement E2E should assert allocations link to purchase + journal.

## Production / Inventory
Chain: Production Log -> Packing Record -> Finished Goods Batch -> Inventory Movements -> (Optional) Journal Entry.

Expected linkage checks:
- Production log references packing record and produced batch.
- Packing record links to finished goods batch and inventory movements.
- Inventory movements reference production/packing and journal entry when posted.

Test expectations (current + to enforce):
- Production cycle E2E should assert production log -> batch -> movement linkage.
- Inventory GL reconciliation E2E should assert movement -> journal linkage.

## Payroll
Chain: Payroll Run -> Journal Entry -> Mark-paid/Payment -> Payroll Reports.

Expected linkage checks:
- Payroll run links to journal entry after posting.
- Payroll payments update run/line status and are traceable from reports.
- Attendance rows used for posting link back to the payroll run.

Test expectations (current + to enforce):
- Payroll batch payment E2E should assert run -> journal linkage + paid status.
- Payroll regression or invariant tests should assert attendance/run linkage.

## Reversals
Chain: Source Document -> Original Journal Entry -> Reversal Entry -> Ledger Update.

Expected linkage checks:
- Reversal entries reference the original journal entry.
- Source documents retain linkage to original + reversal where applicable.
- Ledger statements reflect reversal entries with proper references.

Test expectations (current + to enforce):
- Accounting invariants should assert reversal_of_id/reversal_entry_id chains.
- Regression E2E should assert reversals link back to the original document/journal.

## Link Keys
Linkage keys observed in entities (primary keys, FK columns, and reference fields).

### Order-to-Cash (O2C)
- `sales_orders.id` -> `packaging_slips.sales_order_id`, `invoices.sales_order_id`, `production_logs.sales_order_id`.
- `sales_orders.fulfillment_invoice_id` -> `invoices.id`.
- `sales_orders.sales_journal_entry_id` / `sales_orders.cogs_journal_entry_id` -> `journal_entries.id`.
- `packaging_slips.invoice_id` -> `invoices.id`.
- `packaging_slips.journal_entry_id` / `packaging_slips.cogs_journal_entry_id` -> `journal_entries.id`.
- `invoices.journal_entry_id` -> `journal_entries.id`.
- `dealer_ledger_entries.journal_entry_id` -> `journal_entries.id`.
- `dealer_ledger_entries.invoice_number` -> `invoices.invoice_number`.
- `partner_settlement_allocations.invoice_id` -> `invoices.id`; `partner_settlement_allocations.journal_entry_id` -> `journal_entries.id`.
- `packaging_slip_lines.packaging_slip_id` -> `packaging_slips.id`; `packaging_slip_lines.finished_good_batch_id` -> `finished_good_batches.id`.
- `inventory_movements.reference_type=SALES_ORDER` and `inventory_reservations.reference_type=SALES_ORDER` use `reference_id = sales_orders.id` (InventoryReference.SALES_ORDER).

### Procure-to-Pay (P2P)
- `raw_material_purchases.journal_entry_id` -> `journal_entries.id`.
- `raw_material_purchases.supplier_id` -> `suppliers.id`.
- `partner_settlement_allocations.purchase_id` -> `raw_material_purchases.id`; `partner_settlement_allocations.journal_entry_id` -> `journal_entries.id`.
- `supplier_ledger_entries.journal_entry_id` -> `journal_entries.id`.
- `raw_material_movements.reference_type=RAW_MATERIAL_PURCHASE` uses `reference_id = raw_material_batches.batch_code`.
- `raw_material_movements.reference_type=PURCHASE_RETURN` uses `reference_id = purchase return reference number`.

### Production / Inventory
- `production_logs.production_code` is used as `inventory_movements.reference_id` when `reference_type=PRODUCTION_LOG`.
- `packing_records.production_log_id` -> `production_logs.id`.
- `packing_records.finished_good_batch_id` -> `finished_good_batches.id`.
- `inventory_movements.reference_type=PACKING_RECORD` uses `reference_id = <production_code>-PACK-<lineIndex>`.
- `inventory_movements.journal_entry_id` -> `journal_entries.id`.
- `finished_good_batches.parent_batch_id` links bulk-to-size child batches.
- `inventory_movements.reference_type=MANUFACTURING_ORDER` uses `reference_id = finished_good_batches.public_id`.
- `inventory_movements.reference_type=OPENING_STOCK` uses `reference_id = opening stock batch code`.

### Payroll
- `payroll_runs.journal_entry_id` and `payroll_runs.journal_entry_ref_id` -> `journal_entries.id`.
- `payroll_run_lines.payroll_run_id` -> `payroll_runs.id`.
- `attendance.payroll_run_id` -> `payroll_runs.id`.

### Reversals / Reference Mapping
- `journal_entries.reversal_of_id` -> `journal_entries.id` (original entry); `journal_entries.reversal_entry` is the inverse link.
- `journal_reference_mappings.legacy_reference` / `canonical_reference` map to `journal_entries.reference_number` with `entity_type`/`entity_id` metadata.
