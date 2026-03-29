# Sales Workflows

## 1. Dealer Setup To Order Readiness

1. Create or update the dealer record.
2. Validate commercial profile, GST fields, credit policy, and selling context.
3. If dealer readiness fails, stop before order creation and show the exact
   issue.

## 2. Order Draft To Commercial Confirmation

1. Create the order against a valid dealer and ready SKU set.
2. Review quantities, pricing, discounts, taxes, and delivery intent.
3. Confirm the order to lock the commercial intent and reservation request.
4. Surface reservation outcome without pretending the dispatch has happened.

## 3. Reservation To Factory Handoff

1. Read dispatch preparation state from dispatch-read endpoints.
2. Show whether the order is waiting on production, packing, dispatch queue, or
   partial dispatch.
3. Do not expose a dispatch-confirm action in this portal.
4. Route operational follow-up to the factory portal when user action is needed.

## 4. Dispatch Outcome To Invoice Visibility

1. After factory confirms dispatch, refresh order timeline and invoice state.
2. Show invoice identifiers, amounts, and current receivable status inside the
   current order detail or order timeline only.
3. If a read-only invoice drill-down is needed, launch it from the current order
   context rather than a standalone sales invoice browser.
4. If invoice is missing, show dispatch-read state first rather than assuming an
   accounting fault.

## 5. Credit Escalation

1. Detect credit holds or limit breaches during dealer or order review.
2. Surface the commercial reason and current exposure.
3. Route to the credit workflow.
4. Do not offer hidden finance or admin workarounds.

## Failure Handling

- Credit blocked: keep the order unconfirmed and route into credit escalation.
- Stock or reservation blocked: keep the order in editable state with exact
  shortage messaging.
- Dispatch pending: render factory-owned next-step context without surfacing
  factory actions in sales.
- Invoice unavailable: show dispatch-read status and last known factory event.
