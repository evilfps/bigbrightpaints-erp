# Dealer Client Playwright Journeys

## 1. Order Tracking

1. Log in as a dealer user.
2. Open `/dealer/dashboard` and assert summary shows order count and credit
   exposure.
3. Open `/dealer/orders` and filter by status.
4. Open an order detail and assert timeline shows dispatch status.
5. Assert the UI does not expose factory dispatch-confirm controls.

## 2. Invoice Review

1. Log in as a dealer.
2. Open `/dealer/invoices` and assert invoice list is scoped to the dealer's
   company.
3. Open an invoice detail and assert line items and tax breakdown are visible.
4. Click PDF download and assert the file downloads.

## 3. Ledger And Aging

1. Log in as a dealer.
2. Open `/dealer/ledger` and assert ledger entries are scoped to the dealer's
   company.
3. Open `/dealer/aging` and assert aging buckets are visible.
4. Assert no accounting correction controls are visible.

## 4. Credit Request Self-Service

1. Log in as a dealer.
2. Open `/dealer/credit` and assert existing requests are visible.
3. Submit a new credit request with required fields.
4. Assert the request appears in the list with `PENDING` status.
5. Assert internal sales override controls are not visible.

## 5. Support Ticket

1. Log in as a dealer.
2. Open `/dealer/support` and create a new ticket.
3. Assert the ticket appears in the list.
4. Open the ticket detail and assert conversation thread is visible.
5. Assert internal admin resolution controls are not visible.
