# Sales Playwright Journeys

## 1. Dealer Creation To Confirmed Order

1. Log in as a sales user.
2. Open `/sales/dealers/new` and create a dealer with valid commercial fields.
3. Open `/sales/orders/new` and create an order for that dealer with a ready
   SKU.
4. Confirm the order.
5. Assert order detail shows commercial confirmation and reservation state.
6. Assert the UI does not expose dispatch-confirm controls.

## 2. Reservation Block

1. Log in as sales.
2. Create or edit an order that exceeds available stock.
3. Attempt confirmation.
4. Assert the order stays editable.
5. Assert the UI shows the exact reservation blocker and does not fabricate a
   dispatched or invoiced state.

## 3. Dispatch Read Follow-Up

1. Open an already confirmed order that is waiting in factory execution.
2. Assert timeline shows pending dispatch and invoice not ready.
3. Assert any operational CTA points to factory ownership, not a direct sales
   write action.

## 4. Credit Escalation

1. Open an order for a dealer that exceeds allowed credit.
2. Attempt confirmation.
3. Assert the UI shows current exposure, limit, and the credit-request CTA.
4. Assert no accounting or tenant-admin workaround actions are visible.
