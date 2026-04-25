# Dealer Client Workflows

## 1. Dealer Dashboard

1. Log in as a dealer.
2. Load open orders, open invoices, current balance, and overdue aging.
3. Ensure all data is scoped to the authenticated dealer only.

## 2. Order Tracking

1. Open the order list.
2. Filter by order state.
3. Open one order detail page.
4. Show commercial status, shipment progress, and invoice readiness.
5. If invoice is missing, explain whether dispatch is still pending or partial.

## 3. Invoice Review

1. Open invoice list.
2. Filter by open, paid, overdue, or partially paid state.
3. Open invoice detail.
4. Show amount, due date, payment status, and download action if allowed.

## 4. Ledger And Aging

1. Open ledger view for transaction history.
2. Open aging view for current and overdue buckets.
3. Keep these screens read-only and dealer-safe.

## 5. Report a problem

1. Open report screen.
2. Capture issue category and summary with optional diagnostics.
3. Submit to `POST /api/v1/incidents/report`.
4. Show recorded report reference for follow-up.

## 6. Self-Service Credit Request

1. Open current credit exposure and available credit summary.
2. Start a new credit request when blocked or approaching the limit.
3. Submit the request with dealer-provided justification.
4. Show request status as pending, approved, or rejected.

## Failure Handling

- Unauthorized scope: hard-stop and redirect to dealer-safe error handling.
- Missing invoice: show dispatch and order context first.
- Report submission failure: preserve draft and show retry guidance.
- Credit request rejection: show decision reason if backend provides it.
