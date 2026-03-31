# Dealer Client Portal

The dealer-client portal is the external self-service workspace for dealer
users. It is read-heavy, scoped to the logged-in dealer, and limited to dealer
safe actions such as order tracking, invoice review, ledger and aging reads,
support requests, and credit requests.

## Portal Ownership

- dealer dashboard and dealer-scoped summary cards
- dealer order list and order detail
- invoice list, invoice detail, and invoice download visibility after internal
  dispatch posting completes
- ledger read and aging read
- support request creation and support history
- self-service credit request and request-status tracking

## Explicit Non-Ownership

- internal sales order editing or dealer-master administration
- dispatch execution, production, or packing
- accounting settlement, journals, reversals, exports, or period close
- tenant-admin approvals or superadmin controls

## Core Rules

- Every screen must be dealer-scoped and must never expose another dealer's
  data.
- Dealer users can request support or credit, but they cannot approve, settle,
  reverse, or correct internal records.
- Dispatch and invoice progression are visible as status, not as editable
  operational actions.
- This portal owns the external invoice inbox and invoice detail experience.
  Internal sales only owns order-linked invoice status and summary reads.
