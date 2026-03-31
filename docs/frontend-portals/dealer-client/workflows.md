# Dealer Client Workflows

## 1. Order Tracking

1. View dashboard with order summary and credit exposure.
2. Navigate to order list with status filters.
3. Open order detail to see timeline, dispatch status, and linked invoice.
4. If dispatch is pending, show factory-owned status without exposing actions.

## 2. Invoice Review

1. Navigate to invoice list filtered by dealer's company.
2. Open invoice detail to review line items and tax breakdown.
3. Download invoice PDF.
4. If invoice is not ready, show dispatch-read status.

## 3. Ledger And Aging

1. View account ledger with date range filters.
2. Review receivable aging summary by due date bucket.
3. If payment is due, show payment reminder without exposing internal
   accounting actions.

## 4. Self-Service Credit Request

1. Navigate to credit section.
2. Submit a new credit request with required fields.
3. View existing request status and history.
4. Do not expose internal sales or accounting approval workflow.

## 5. Support Ticket

1. Create a new support ticket with issue description.
2. View ticket list and status.
3. Open ticket detail to see conversation thread.
4. Do not expose internal admin resolution actions.

## Failure Handling

- Invoice unavailable: show dispatch-read state instead of generic error.
- Credit request rejected: show rejection reason and contact sales for escalation.
- Support ticket not resolved: show current status and escalation path.
