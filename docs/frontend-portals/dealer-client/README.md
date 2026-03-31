# Dealer Client Portal

The dealer-client portal is the dealer self-service shell. It provides
dealer-facing commercial visibility and limited self-service actions without
granting access to internal sales editing, factory execution, accounting
correction, or admin approvals.

## Portal Ownership

- dealer dashboard: order summary, credit exposure, payment status
- dealer order tracking and history
- dealer invoice review, download, and ledger access
- dealer aging and payment reminder visibility
- dealer support ticket creation and follow-up
- dealer self-service credit request

## Explicit Non-Ownership

- internal dealer master create/edit by sales
- dispatch execution, production logs, packing records, packaging mappings
- accounting journal correction, period close, settlements
- tenant-user management, export approval, or admin controls
- viewing other dealers' data

## Canonical Boundary Rules

- Dealer is always a read-mostly consumer. Write actions are limited to
  self-service credit requests and support tickets.
- Dealer never sees internal sales-override controls, factory-only dispatch
  buttons, or accounting correction workflows.
- Dealer invoice access is tied to the authenticated dealer's own company code
  via `X-Company-Code` header, never through superadmin routes.

## Frontend Folder Guidance

- Keep all dealer-facing self-service screens in this folder.
- If a screen allows editing internal dealer master, it belongs in the sales
  portal.
- If a screen triggers factory execution or dispatch confirmation, it belongs
  in the factory portal.
- If a screen exposes journal correction, settlements, or admin approvals, it
  belongs in accounting or tenant-admin.
