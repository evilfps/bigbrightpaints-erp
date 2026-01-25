# Cross‑Module Trace Map (Task 00)

Purpose: evidence‑backed mapping of business events across inventory → sales → invoice → accounting → ledgers → tax/settlement/returns.

## Event Evidence Table

| Event | Entrypoint(s) | Core services (method chain) | Repositories / tables touched | Journals + reference scheme | Idempotency / constraints |
| --- | --- | --- | --- | --- | --- |
| Dispatch (confirm) | `SalesController.confirmDispatch` (`POST /api/v1/sales/dispatch/confirm`)<br>`DispatchController.confirmDispatch` (`POST /api/v1/dispatch/confirm`) | `SalesService.confirmDispatch` → `AccountingFacade.postSalesJournal` + `AccountingFacade.postCogsJournal` → `DealerLedgerService.syncInvoiceLedger` → `FinishedGoodsService.confirmDispatch` + `FinishedGoodsService.linkDispatchMovementsToJournal` | `packaging_slips`, `packaging_slip_lines`<br>`sales_orders`<br>`inventory_movements`<br>`invoices`, `invoice_lines`<br>`journal_entries`, `journal_lines`<br>`dealer_ledger_entries`<br>`journal_reference_mappings` | AR/Revenue/Tax: canonical reference `SalesOrderReference.invoiceReference(orderNumber)`<br>COGS: `COGS-<slipNumber>` | `JournalReferenceResolver.findExistingEntry(...)`<br>`journal_entries` unique `(company_id, reference_number)`<br>`packaging_slips` unique `(company_id, slip_number)`<br>`invoices` unique `(company_id, invoice_number)` |
| Invoice issuance | Dispatch path (above) creates invoice for shipped qty.<br>Order‑level invoice: `InvoiceService.issueInvoiceForOrder` (internal) | `InvoiceService.issueInvoiceForOrder` → `SalesJournalService.postSalesJournal` → `DealerLedgerService.syncInvoiceLedger` → `linkInvoiceToPackagingSlip` | `invoices`, `invoice_lines`<br>`sales_orders`<br>`packaging_slips`<br>`journal_entries`, `journal_lines`<br>`dealer_ledger_entries` | Sales journal uses canonical reference `SalesOrderReference.invoiceReference(order)` | Invoice per order guard: `invoiceRepository.findAllByCompanyAndSalesOrderId(...)`<br>`journal_entries` unique `(company_id, reference_number)` |
| Payment (dealer receipt) | `AccountingController.recordDealerReceipt` (`POST /api/v1/accounting/receipts/dealer`)<br>`AccountingController.recordDealerHybridReceipt` (`POST /api/v1/accounting/receipts/dealer/hybrid`) | `AccountingService.recordDealerReceipt` / `recordDealerReceiptSplit` → `createJournalEntry` → `InvoiceSettlementPolicy.applySettlement` → `DealerLedgerService.syncInvoiceLedger` | `journal_entries`, `journal_lines`<br>`partner_settlement_allocations`<br>`invoices`<br>`dealer_ledger_entries` | Journal reference: `referenceNumberService.dealerReceiptReference(...)` or provided `referenceNumber` | Idempotency key = journal reference → `settlementAllocationRepository.findByCompanyAndIdempotencyKey(...)`<br>`journal_entries` unique `(company_id, reference_number)` |
| Settlement (dealer) | `AccountingController.settleDealer` (`POST /api/v1/accounting/settlements/dealers`) | `AccountingService.settleDealerInvoices` → `InvoiceSettlementPolicy.applySettlement` → `DealerLedgerService.syncInvoiceLedger` → `createJournalEntry` | `partner_settlement_allocations`<br>`journal_entries`, `journal_lines`<br>`invoices`<br>`dealer_ledger_entries` | Journal reference: `request.idempotencyKey` or `request.referenceNumber` (fallback `referenceNumberService.dealerReceiptReference(...)`) | Idempotency key stored on `partner_settlement_allocations` and checked before posting |
| Return (sales return) | `AccountingController.recordSalesReturn` (`POST /api/v1/accounting/sales/returns`) | `SalesReturnService.processReturn` → `AccountingFacade.postSalesReturn` → `restockFinishedGood` → `postCogsReversal` → `AccountingFacade.postInventoryAdjustment` | `invoices`, `invoice_lines`<br>`finished_goods`, `finished_good_batches`<br>`inventory_movements` (reference_type `SALES_RETURN`)<br>`journal_entries`, `journal_lines` | Sales return journal reference `CRN-<invoiceNumber>`<br>COGS reversal adjustment reference `CRN-<invoiceNumber>-COGS-<idx>` | Sales return dedupe via `journalEntryRepository.findByCompanyAndReferenceNumber(...)` |

## Call Chains (file paths + methods)

### Dispatch confirmation
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/SalesController.java` → `confirmDispatch(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java` → `confirmDispatch(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` → `confirmDispatch(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` → `postSalesJournal(...)`, `postCogsJournal(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java` → `confirmDispatch(...)`, `linkDispatchMovementsToJournal(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerLedgerService.java` → `syncInvoiceLedger(...)`

### Invoice issuance (order‑level)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java` → `issueInvoiceForOrder(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/SalesJournalService.java` → `postSalesJournal(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerLedgerService.java` → `syncInvoiceLedger(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java` → `linkInvoiceToPackagingSlip(...)`

### Payment (dealer receipt)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java` → `recordDealerReceipt(...)`, `recordDealerHybridReceipt(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` → `recordDealerReceipt(...)`, `recordDealerReceiptSplit(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceSettlementPolicy.java` → `applySettlement(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerLedgerService.java` → `syncInvoiceLedger(...)`

### Settlement (dealer)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java` → `settleDealer(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` → `settleDealerInvoices(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceSettlementPolicy.java` → `applySettlement(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerLedgerService.java` → `syncInvoiceLedger(...)`

### Sales return
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java` → `recordSalesReturn(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnService.java` → `processReturn(...)`, `postCogsReversal(...)`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` → `postSalesReturn(...)`, `postInventoryAdjustment(...)`

## Golden Path Trace (Order‑to‑Cash)

Evidence test:
- `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/ErpInvariantsSuiteIT.java` → `orderToCash_goldenPath()`

Trace steps (from test sequence):
1) Order create (idempotent)
   - Endpoint: `POST /api/v1/sales/orders`
   - Controller → service: `SalesController.createOrder(...)` → `SalesService.createOrder(...)`
   - Tables: `sales_orders`, `sales_order_items`
   - Idempotency: `idempotencyKey` in request (`"O2C-ORDER-001"` in test)
2) Order confirm
   - Endpoint: `POST /api/v1/sales/orders/{id}/confirm`
   - Controller → service: `SalesController.confirmOrder(...)` → `SalesService.confirmOrder(...)`
   - Tables: `sales_orders` (status update)
3) Dispatch confirm → invoice + journals + inventory
   - Endpoint: `POST /api/v1/sales/dispatch/confirm`
   - Controller → service: `SalesController.confirmDispatch(...)` → `SalesService.confirmDispatch(...)`
   - Artifacts asserted in test:
     - Invoice + AR journal link (`invoices`, `journal_entries`)
     - COGS journal link on slip (`packaging_slips`)
     - Inventory movements (`inventory_movements`)
     - Dealer ledger entries created (`dealer_ledger_entries`)
   - Idempotency check: dispatch replay preserves invoice/journal IDs and movement set
   - Evidence tests: `OrderFulfillmentE2ETest.dispatchConfirm_idempotent_andRestoresArtifacts`, `OrderFulfillmentE2ETest.dispatchEndpoints_areEquivalent`, `DispatchConfirmationIT`
4) Settlement (dealer)
   - Endpoint: `POST /api/v1/accounting/settlements/dealers`
   - Controller → service: `AccountingController.settleDealer(...)` → `AccountingService.settleDealerInvoices(...)`
   - Artifacts asserted in test:
     - Settlement journal balanced (`journal_entries`)
     - Dealer ledger entries updated to PAID (`dealer_ledger_entries`)
   - Idempotency check: settlement replay returns same journal entry

## Idempotency & Reference Scheme (Evidence by Event)

- Sales AR journal (dispatch/invoice)
  - Canonical reference: `SalesOrderReference.invoiceReference(...)` → `INV-<orderNumber>`  
    `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/util/SalesOrderReference.java`
  - Resolver: `JournalReferenceResolver.findExistingEntry(...)` checks direct + legacy + canonical  
    `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/JournalReferenceResolver.java`
  - Mapping table + legacy remap (`SALE-` → `INV-`):  
    `erp-domain/src/main/resources/db/migration/V88__journal_reference_mappings.sql`

- COGS journal (dispatch)
  - Reference in accounting: `COGS-<referenceId>`  
    `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
  - Dispatch path supplies slip number as referenceId (SalesService confirm path).

- Sales return
  - Credit note reference: `CRN-<invoiceNumber>` and duplicate check by reference  
    `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`

- Dealer receipt / settlement
  - Idempotency key stored on `partner_settlement_allocations.idempotency_key`  
    `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/PartnerSettlementAllocation.java`
  - Index scope (non‑unique):  
    `erp-domain/src/main/resources/db/migration/V102__partner_settlement_idempotency_scope.sql`
  - Usage: `AccountingService.recordDealerReceipt(...)` / `settleDealerInvoices(...)` checks existing rows by idempotency key.

- Inventory movements (dispatch/return)
  - Reference fields: `reference_type` + `reference_id`  
    `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryMovement.java`
  - No unique constraint declared on entity; confirm DB constraints in migrations if needed.

## Reference / Idempotency Sources
- Canonical reference rules: `erp-domain/src/main/resources/db/migration/V88__journal_reference_mappings.sql`
- Journal dedupe: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/JournalReferenceResolver.java`
- Sales order invoice reference: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/util/SalesOrderReference.java`
