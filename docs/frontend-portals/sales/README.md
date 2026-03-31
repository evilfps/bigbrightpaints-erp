# Sales Portal

The sales portal is the internal commercial workspace for dealer master,
quotation-to-order execution, reservation visibility, order-linked invoice
follow-up, and credit escalation. It owns commercial intent and customer-facing
commercial state. It does not own factory execution, a standalone invoice
browser, or accounting correction.

## Portal Ownership

- dealer create, edit, and commercial-profile maintenance
- sales-order draft, review, and confirm flows
- reservation visibility and order timeline tracking
- order-linked invoice status and read-only invoice follow-up after factory
  dispatch confirmation
- commercial credit-limit visibility and credit escalation
- sales dashboards and dealer performance views

## Explicit Non-Ownership

- `POST /api/v1/dispatch/confirm`
- production logs, packing records, packaging mappings, or batch execution
- manual journals, reversals, settlements, or period-close actions
- tenant-user management, export approval, or superadmin controls

## Canonical Boundary Rules

- Sales order confirmation is not the accounting-posting boundary.
- Factory dispatch confirmation is the only canonical O2C posting trigger.
- Sales may show dispatch state, invoice readiness, and a read-only invoice
  summary launched from an order timeline, but it must never offer a
  dispatch-confirm button, a standalone invoice list, or any accounting
  workaround when dispatch stalls.
- If credit, stock, or pricing rules block order confirmation, the sales UI
  must stop at the commercial boundary and surface the exact blocker.

## Frontend Folder Guidance

- Keep all dealer, order, reservation, quote-to-order, and sales KPI screens in
  this folder.
- If a screen's primary action is operational dispatch execution, move it to the
  factory portal.
- If a screen's primary action is settlement, journal correction, export
  approval, or finance remediation, move it to the accounting or tenant-admin
  portal.
