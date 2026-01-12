# Task 02 — P2P Logic Hunt (Purchasing → RM Receipt → AP)

## Scope
- Workflows: Procure-to-Pay (P2P), raw material purchases, receipts, purchase returns, supplier payments/settlements.
- Portals: Accounting (purchasing), Inventory.
- Modules (primary): `purchasing`, `inventory`, `accounting`, `reports`.

## ERP expectation
- Purchase posting must create a balanced journal (inventory + input tax = AP).
- RM receipt movements must be traceable to the purchase and (where applicable) linked to the purchase journal.
- Supplier subledger ties to AP control accounts within tolerance.
- Purchase returns unwind inventory and AP with proper traceability.
- Idempotency: invoice_number and/or reference/idempotency keys prevent duplicates under retry.

## Where to inspect in code
- Purchase creation + posting + receipt recording:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java` (`createPurchase`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/RawMaterialService.java` (`recordReceipt`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` (`postPurchaseJournal`)
- Purchase returns:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java` (`recordPurchaseReturn`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` (`postPurchaseReturn`)
- Supplier payments/settlements + allocations:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` (`recordSupplierPayment`, `settleSupplierInvoices`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/PartnerSettlementAllocation.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/SupplierLedgerService.java`

## Evidence to gather

### SQL probes
- Posted purchases missing journals:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql`
- RM movements missing journal links (esp. purchase receipts/returns):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql`
- AP tie-outs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/05_ar_ap_tieouts.sql`
- Idempotency duplicates:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/11_idempotency_duplicates.sql`

### GET-only API probes
- Accounting reports + month-end checklist:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`

## What counts as a confirmed flaw (LF)
- Purchase with `status=POSTED` exists but `journal_entry_id` is null.
- RM receipt/return created but cannot be traced to a purchase journal where the flow claims linkage.
- AP control vs supplier ledger variance outside tolerance with code-level causal chain.
- Retry creates duplicate settlement allocations or duplicate purchase postings despite business-key uniqueness.

## Why tests might still pass
- Tests may cover purchase creation but not verify ledger tie-outs and linkage back to movements.
- Idempotency may not be tested under concurrent duplicate submissions.

## Deliverable
- Confirmed LF items appended to `tasks/erp_logic_audit/LOGIC_FLAWS.md` with evidence.
- Findings indexed in `tasks/erp_logic_audit/FINDINGS_INDEX.md`.

