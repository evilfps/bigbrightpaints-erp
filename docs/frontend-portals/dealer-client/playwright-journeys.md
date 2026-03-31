# Dealer Client Playwright Journeys

## 1. Order To Invoice Tracking

1. Log in as a dealer user.
2. Open `/dealer/orders`.
3. Select an order that has already moved through internal dispatch.
4. Assert order detail shows shipment status and invoice readiness.
5. Open the linked invoice and verify amount and payment state.

## 2. Ledger And Aging Read

1. Open `/dealer/ledger`.
2. Assert ledger lines render only for the authenticated dealer.
3. Open `/dealer/aging`.
4. Assert aging buckets and overdue totals align with dealer account state.

## 3. Support Request Submission

1. Open `/dealer/support`.
2. Start a new request tied to an order or invoice.
3. Submit the request.
4. Assert the new ticket appears in support history.

## 4. Self-Service Credit Request

1. Open `/dealer/credit-requests/new`.
2. Submit a credit request with justification.
3. Assert request status returns as pending.
4. Assert no internal approval UI or settlement controls are visible.
