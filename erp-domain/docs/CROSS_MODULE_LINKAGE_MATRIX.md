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
Actual link keys (ids/refs) used by entities and migrations are documented in M2.
