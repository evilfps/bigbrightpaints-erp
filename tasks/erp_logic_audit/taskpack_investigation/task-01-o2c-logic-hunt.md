# Task 01 — O2C Logic Hunt (Sales → Dispatch → Invoice → AR)

## Scope
- Workflows: Order-to-Cash (O2C), dispatch confirmation, invoicing, dealer ledger, AR reconciliation.
- Portals: Sales, Factory/Dispatch, Accounting, Dealer portal (read-only).
- Modules (primary): `sales`, `inventory`, `invoice`, `accounting`, `reports`.

## ERP expectation (what must be true)
- Dispatch confirmation is the canonical “financial event”:
  - stock is issued (inventory movements recorded)
  - AR/Revenue/Tax journal is posted and linked
  - COGS journal is posted and linked
  - invoice is issued and linked to journal(s)
  - dealer subledger reflects AR activity and ties to control accounts within tolerance
- No “success” response may leave orphaned/unlinked artifacts.
- Re-running dispatch confirmation is idempotent (no duplicate journals, invoices, movements).

## Where to inspect in code (likely hotspots)
- Sales order creation + GST math:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`createOrder`, `mapOrderItems`)
- Reservation + packaging slips:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java` (`reserveForOrder`, slip creation)
- Dispatch confirmation orchestration (the core chain-of-evidence):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`confirmDispatch`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java` (`confirmDispatch`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java` (`POST /api/v1/dispatch/confirm`)
- Posting semantics + reference rules:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` (`postSalesJournal`, `postCogsJournal`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` (`createJournalEntry`)
- Invoice persistence + settlement fields:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/Invoice.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/InvoiceLine.java`
- Dealer ledger updates:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerLedgerService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/DealerLedgerRepository.java`

## Evidence to gather (read-only first)

### SQL probes
- Document linkage:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/03_dispatch_slips_without_cogs_journal.sql`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/04_journals_without_document_link.sql`
- Totals/tax arithmetic:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/10_tax_and_totals_variances.sql`
- Reconciliation tie-outs (AR):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/05_ar_ap_tieouts.sql`

### GET-only API probes
- Accounting reports + month-end checklist:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`
- Dealer portal read-only surface:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/02_dealer_portal_gets.sh`

### Logs / traces (if available)
- App logs around dispatch confirmation and posting (capture paths used in your environment).
- Orchestrator audit for trace correlation (if trace IDs are propagated).

## What counts as a confirmed flaw (LF)
Add an LF item only if you can show at least one of:
- A DISPATCHED slip exists where invoice/journal links are missing (and code path does not guarantee eventual repair).
- A journal entry exists with O2C reference patterns (INV-/COGS-) but cannot be traced to a source document.
- AR control vs dealer ledger variance outside tolerance with a clear causal chain (posting or ledger update mismatch).
- Idempotency replay creates duplicates (invoice/journal/movement) under same business key/reference.

## Why tests might still pass
- Tests may not assert cross-module linkage chains (slip → invoice → JE → dealer ledger).
- Tests may not cover partial dispatch/backorder paths, or negative config cases.
- Tests may not exercise concurrency/idempotency replays under load.

## Deliverable
- Append confirmed LF items to `tasks/erp_logic_audit/LOGIC_FLAWS.md` with:
  - code pointers + SQL/curl output references
  - severity + repro steps
  - fix direction (bullets only; no implementation)
- Add/update a row in `tasks/erp_logic_audit/FINDINGS_INDEX.md` for each confirmed LF (or LEAD).

