# Hydration / Verification Rules (V1)

## Non-Negotiable Gates
1) Idempotency
   - Sales order creation idempotent by (company, idempotencyKey).
   - Dispatch confirmation idempotent per packaging slip.
   - Payroll run idempotent by (company, runType, periodStart, periodEnd).

2) Ledger invariants
   - All posted journal entries are balanced.
   - Period lock enforced for all postings (system + manual).
   - No manual journal reference collides with system reference namespace.

3) Inventory invariants
   - No batch quantity goes negative.
   - Dispatch cannot ship more than reserved/available.
   - COGS is computed from dispatch unit cost (FIFO/WAC) deterministically.

4) Timezone invariants
   - Business dates use CompanyClock + company timezone.
   - No ZoneId.systemDefault() in business logic.

## Required Evidence for Every PR
- Code references (file + method) for every business rule touched.
- DB migration impact explained (scan -> clean -> constrain).
- Tests updated to reflect code (not the other way around).

## Required Test Matrix
- Sales: order idempotency, reservation, partial dispatch, invoice amounts, journal uniqueness.
- Purchasing: GRN partials, invoice posting, purchase return.
- Payroll: idempotency, attendance windows, advances/deductions.
- Manufacturing: WIP journals, packing journals, cost allocation month boundaries.

## Verification Log
- 2026-01-27: EPIC 01 / M01.0 verified.
  - Code: `SalesService.confirmDispatch` now resolves invoices per packaging slip; backorder dispatch creates a new invoice.
  - Tests: `OrderFulfillmentE2ETest.partialDispatch_invoicesShippedQty_andCreatesBackorderSlip`.
  - Command: `scripts/verify_local.sh`.
- 2026-01-27: EPIC 01 / M01.1 verified.
  - Code: `SalesFulfillmentService.validateAndNormalizeOptions` now fails closed when `issueInvoice=false`.
  - Tests: `SalesFulfillmentServiceTest.rejectsFulfillmentWithoutDispatchInvoice`.
  - Command: `scripts/verify_local.sh`.
- 2026-01-27: EPIC 01 / M01.2 verified.
  - Code: `SalesJournalService.postSalesJournal` now fails closed (order-truth posting disabled).
  - Tests: `SalesJournalServiceTest` (order-truth posting now rejected).
  - Command: `scripts/verify_local.sh`.
- 2026-01-28: EPIC 01 / M01.3 verified.
  - Code: `V116__packaging_slip_journal_unique.sql` enforces slip-level journal uniqueness; `scripts/db_predeploy_scans.sql` adds duplicate slip journal scans.
  - Tests: `scripts/verify_local.sh`.
- 2026-01-28: EPIC 02 / M02.1 verified.
  - Code: `AccountingPeriodService.closePeriod` blocks closing when uninvoiced goods receipts exist; month-end checklist now reports uninvoiced receipts.
  - Tests: `PeriodCloseLockIT.closePeriodRejectsUninvoicedReceipts`.
  - Command: `scripts/verify_local.sh`.
- 2026-01-28: EPIC 00 / M00.2 verified.
  - Code: CODE-RED scripts no longer hard-depend on `rg`; CI now runs schema drift scan + triage steps.
  - Tests: `scripts/schema_drift_scan.sh`, `scripts/triage_tests.sh`.
