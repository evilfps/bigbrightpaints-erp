# Redemption Bugs

## Fixed
- R-001 Cancelled packaging slips did not release inventory reservations, leaving stock locked and future allocations blocked.
  - Fix: release reservations before resetting slip lines; use the managed order entity when creating new slips.
  - Locations: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java:228`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java:242`
- R-002 Multiple packing slips for a single order caused reserve/dispatch/confirm flows to hard-fail with no fallback path.
  - Fix: select the most recent slip when multiples exist and log a warning.
  - Locations: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java:232`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java:388`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:1123`
- R-003 Sales fulfillment dispatched using order ID even after reserving a specific slip, which could target the wrong slip when duplicates exist.
  - Fix: pass the reserved slip ID into dispatch confirmation when available.
  - Location: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesFulfillmentService.java:124`
- R-004 Fulfillment options allowed `issueInvoice=true` with `reserveInventory=false`, leading to missing slip errors in dispatch.
  - Fix: force `reserveInventory=true` when issuing invoices.
  - Location: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesFulfillmentService.java:256`
- R-005 `createJournalEntry` accepted a null request and failed with a null pointer before logging audit metadata.
  - Fix: validate request early and throw a domain validation error.
  - Location: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java:219`
- R-007 GST tax accounts and company defaults could be missing or misaligned (e.g., default tax account pointing at AP), blocking startup when validation is enabled.
  - Fix: backfill GST accounts + defaults via Flyway and align dev seeding to canonical chart codes.
  - Locations: `erp-domain/src/main/resources/db/migration/V110__ensure_gst_accounts_defaults.sql`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java:52`
  - Reset script (optional): `erp-domain/scripts/reset_chart_of_accounts.sql`

## Open
- R-006 Dispatch confirmation throws when multiple invoices exist for an order, but `DispatchConfirmRequest` has no invoice selector.
  - Risk: dispatch is blocked for multi-invoice orders.
  - Locations: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:1165`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/dto/DispatchConfirmRequest.java:6`
  - Suggestion: add optional `invoiceId` to the request and route selection through confirm dispatch.

## Async cross-module run
- Command: `mvn -B -ntp verify` (async via `nohup`, log `/tmp/ci-verify.log`)
- Result: BUILD SUCCESS, tests passed (268 run, 0 failures, 0 errors, 4 skipped)
