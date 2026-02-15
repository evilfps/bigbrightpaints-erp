# Sales Payment and Accounting Contract

## Scope
This document captures the current backend contract for sales-order payment modes, credit-limit enforcement, and idempotency behavior.
- Async-loop verification baseline for this slice is run on Flyway **V2** (`db/migration_v2`, `flyway_schema_history_v2` profile/path).

## Payment Modes
- Allowed order payment modes are exactly `CASH`, `CREDIT`, `SPLIT` (default is `CREDIT` when omitted).
- `CREDIT`:
  - Dealer credit-limit check is enforced at order create/update.
  - Enforcement currently uses posted receivable outstanding + attempted order value.
- `SPLIT`:
  - Dealer credit-limit check is enforced (split still has credit exposure).
  - Allocation-leg posting rules are tracked in `M15-S6` (pending hardening).
- `CASH`:
  - Dealer credit-limit check is enforced at order create/update.
  - Cash collection is still settled downstream via accounting receipt/settlement flows; `paymentMode=CASH` does not bypass order-time credit policy.

## Accounting Method Map (Dealer Collection Paths)
- `recordDealerReceipt` (`cashAccountId` + explicit invoice allocations):
  - Journal shape: `Dr cash/bank account`, `Cr dealer receivable`.
  - Requires allocation rows; each allocation must target a dealer invoice.
- `recordDealerReceiptSplit` (`incomingLines[]`):
  - Journal shape: each incoming line debits its cash/bank account; one receivable credit line is posted for total receipt.
  - Requires at least one incoming line and rejects totals above open receivable exposure.
- `settleDealerInvoices` (`POST /api/v1/accounting/settlements/dealers`; allocation + optional payments):
  - Net cash formula: `cashAmount = totalApplied + totalFxGain - totalFxLoss - totalDiscount - totalWriteOff`.
  - If `payments[]` is provided, `sum(payments.amount)` must equal `cashAmount`.
  - If `payments[]` is omitted and `cashAmount > 0`, `cashAccountId` is required and used as implicit single-tender mapping.
- Payment-account validation (all collection paths):
  - account must be active.
  - account must be `ASSET`.
  - account must not be AR/AP control.
- Settlement/receipt idempotency binds account mapping (request/account/amount signatures are replay-validated).

## Idempotency Contract (Sales Order Create)
- Canonical request signature uses normalized payload fields and appends payment mode token only for non-default modes.
- Canonical auto-derived idempotency key appends payment mode token only for non-default modes.
- Compatibility bridge is active for adjacent deployed format drift:
  - Legacy default-credit key shape (`...|CREDIT|...`) is still accepted.
  - Legacy default-credit signature shape (`...|CREDIT|...`) is still accepted.
  - Accepted legacy signatures are upgraded in-place to canonical signature form on replay.

## Idempotency Contract (Orchestrator Production/Dispatch)
- For orchestrator fulfillment/dispatch/payroll command endpoints, key resolution now uses:
  - `Idempotency-Key` header first,
  - then `X-Request-Id` header,
  - then deterministic auto-key derived from command + company + normalized payload.
- This removes brittle header-only failures while preserving exactly-once replay behavior for repeated start/dispatch submissions.

## Accounting and Lifecycle Notes
- Revenue recognition must remain shipment-gated; order creation alone should not expose recognized revenue (`M15-S2`).
- Admin portal dashboard revenue metrics (`/api/v1/portal/dashboard`) are derived from shipment-posted invoice rows, not order status labels.
- Dashboard recognized-revenue invoice statuses:
  - `ISSUED`
  - `PAID`
  - `PARTIAL`
- Non-invoiced orders remain excluded from recognized revenue totals even if manually moved to terminal order statuses.
- Partial-dispatch orders with issued invoices are included in revenue totals, preventing under-reporting while dispatch backlog remains.
- Dealer utilized-credit reporting must include pending/open commitments (`M15-S4`).
- Dealer pending-order visibility must match admin lifecycle truth (`M15-S5`).
- Cash/credit account mapping should be backend-policy driven, not ad-hoc operator selection (`M15-S7`).
- Payroll required-account guardrails (for `SALARY-EXP`) are tracked in `M15-S8`.

## Pending Credit-Exposure Projection
- Dealer credit exposure now has two components:
  - posted receivable outstanding (`invoice outstanding` / dealer ledger balance),
  - plus open order commitments that are still pre-invoice.
- Pending exposure statuses are centrally mapped by `SalesOrderCreditExposurePolicy`:
  - `BOOKED`
  - `RESERVED`
  - `PENDING_PRODUCTION`
  - `PENDING_INVENTORY`
  - `PROCESSING`
  - `READY_TO_SHIP`
  - `CONFIRMED`
  - `ON_HOLD`
- Exposure excludes rows already linked to fulfillment invoice markers.
- Exposure also excludes orders with active invoice rows (`status` normalized and not in `DRAFT/VOID/REVERSED`) to avoid double counting partially invoiced multi-slip orders.
- Dealer portal payloads now expose:
  - `pendingOrderExposure`
  - `creditUsed` (`totalOutstanding + pendingOrderExposure`)
  - `pendingOrderCount` and per-order `pendingCreditExposure` flags.
- Pending exposure is currently a dealer-visibility/monitoring metric; hard credit-limit rejection remains tied to posted outstanding + attempted order amount and is enforced for all standard order payment modes at create/update.

## User-Facing Summary
- Selecting `CASH`, `CREDIT`, or `SPLIT` may show credit-limit rejection when posted outstanding + attempted order exceeds dealer limit.
- Cash settlement still occurs through accounting receipt/settlement flows after order booking.
- Dealer dashboard/aging credit usage includes pre-invoice pending production/order commitments.
- Retry/replay of the same order request must return the same order outcome without creating duplicates, including requests crossing adjacent deployment versions.
