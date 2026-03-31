# Accounting Playwright Journeys

These journeys are for frontend implementation and regression coverage. Each one
should run in the accounting portal shell, not in a generic admin shell that
happens to expose the same APIs.

## 1. Onboarding Dependency To COA Readiness

1. Login as an accounting user.
2. Visit `/accounting/gl/chart-of-accounts`.
3. Assert seeded accounts render for a properly onboarded tenant.
4. For an intentionally incomplete tenant fixture, assert the UI blocks finance
   setup and points to superadmin onboarding remediation.
5. Assert the blocked state does not expose ad hoc COA template selection or
   direct tenant-creation controls inside accounting.

## 2. COA, Default Accounts, And Tax Safety

1. Visit `/accounting/gl/default-accounts`.
2. Update default inventory, valuation, COGS, revenue, discount, and tax
   accounts.
3. Visit `/accounting/tax`.
4. Assert GST mode requires valid input, output, and payable accounts.
5. Switch to non-GST mode and assert GST-only assumptions are removed from the
   save payload.

## 3. Product Mapping Readiness To Opening Stock

1. Create a stock-bearing item through the canonical catalog flow.
2. Visit `/accounting/catalog/account-readiness`.
3. Assert readiness passes only when account mapping is complete.
4. Assert the UI shows whether accounts came from explicit item metadata or
   company defaults.
5. Assert opening-stock upload stays hidden or disabled while readiness is
   blocked.
6. Once ready, open `/accounting/inventory/opening-stock` and submit the import
   with idempotency and batch identifiers.

## 4. Journal Create And Single Reverse Path

1. Visit `/accounting/gl/journals`.
2. Create a manual journal through the canonical journal-entry route.
3. Open a reversible entry detail page.
4. Submit reverse with related-entry options through the canonical reverse
   route.
5. Assert original entry becomes reversed and reversal reference is visible.
6. Assert no legacy manual-journal or alternate reverse route is called.

## 5. Settlement And Reconciliation

1. Seed upstream purchasing data so a supplier, PO, GRN, and purchase invoice
   already exist.
2. Visit `/accounting/ap/settlements` and post a supplier settlement.
3. Assert supplier statement and supplier aging refresh with the new cleared
   amount.
4. Visit `/accounting/ar/receipts` and post a dealer receipt or hybrid receipt.
5. Visit `/accounting/ar/settlements` and post a dealer settlement.
6. Assert dealer ledger, dealer invoices, dealer aging, and aged receivables
   refresh after settlement.
7. Visit `/accounting/reconciliation/bank` and create a bank session.
8. Visit `/accounting/reconciliation/subledger` and resolve one discrepancy.
9. Assert reconciliation summaries refresh after settlement and discrepancy
   resolution.

## 6. Period Close Maker-Checker

1. Maker opens `/accounting/period-close` and completes checklist steps.
2. Maker submits `request-close`.
3. Checker opens approval inbox and approves or rejects.
4. Assert period status updates without ever calling the direct close endpoint.

## 7. Reports And Export Governance

1. Visit `/accounting/reports`.
2. Load a report with accounting period and tenant filters.
3. Submit `POST /api/v1/exports/request`.
4. Assert accounting shows the request as `PENDING`.
5. Approve the request from the tenant-admin inbox.
6. Return to `/accounting/reports`.
7. Assert `GET /api/v1/exports/{requestId}/download` enables the real download
   action only after approval.

## 8. Negative: Incomplete Product Mapping Blocks Opening Stock

1. Create a stock-bearing item without the required finished-good or
   raw-material account mappings.
2. Visit `/accounting/catalog/account-readiness`.
3. Assert the item is marked blocked with the exact missing account ids.
4. Assert `/accounting/inventory/opening-stock` remains disabled or redirected
   back to readiness.

## 9. Negative: Invalid GST Setup Blocks Save

1. Visit `/accounting/tax`.
2. Switch to GST mode while leaving input, output, or payable accounts blank.
3. Assert save is blocked with field-level validation for the missing GST
   accounts.

## 10. Negative: Closed-Period Write Attempt

1. Open a closed period fixture.
2. Attempt to create a manual journal or settlement inside that closed period.
3. Assert the UI stays on the same page and renders a blocked-period error.

## 11. Negative: Export Pending Approval

1. Request an export from `/accounting/reports`.
2. Before approval, call the download contract.
3. Assert the UI shows `PENDING` and does not render a direct file link.
