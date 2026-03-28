# Dealer Client Workflows

## 1. Dealer Dashboard

1. Log in as a dealer.
2. Load open orders, open invoices, current balance, overdue aging, and support
   shortcuts.
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

## 5. Support Request

1. Open support screen.
2. Create a new support request tied to an order, invoice, or general issue.
3. Track request status and response history where backend supports it.

## 6. Self-Service Credit Request

1. Open current credit exposure and available credit summary.
2. Start a new credit request when blocked or approaching the limit.
3. Submit the request with dealer-provided justification.
4. Show request status as pending, approved, or rejected.

## Failure Handling

- Unauthorized scope: hard-stop and redirect to dealer-safe error handling.
- Missing invoice: show dispatch and order context first.
- Support submission failure: preserve the draft and show retry guidance.
- Credit request rejection: show decision reason if backend provides it.
