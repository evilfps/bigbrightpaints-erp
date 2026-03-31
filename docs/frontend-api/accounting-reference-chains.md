# Accounting Reference Chains

Last reviewed: 2026-03-31

## Overview

Accounting transactions often reference other documents in the system. Understanding reference chains helps frontend applications display provenance, navigate between related documents, and audit transaction flows.

## Common Reference Chains

### Invoice → Journal Entry

An invoice post creates a journal entry. The journal entry references the invoice.

**Invoice response includes:**

```json
{
  "invoiceNumber": "INV-2026-0042",
  "journalEntryId": 1001,
  "postedAt": "2026-03-31T10:00:00Z"
}
```

**Journal entry response includes:**

```json
{
  "journalEntryId": 1001,
  "referenceNumber": "INV-2026-0042",
  "linkedDocuments": [
    {
      "type": "INVOICE",
      "id": 42,
      "reference": "INV-2026-0042"
    }
  ]
}
```

### Payment → Invoice → Journal Entry

A payment allocates to an invoice, which references the original journal entry.

**Payment response includes:**

```json
{
  "paymentId": 5001,
  "allocations": [
    {
      "invoiceId": 42,
      "invoiceNumber": "INV-2026-0042",
      "appliedAmount": 1500.00
    }
  ]
}
```

### Sales Order → Invoice → Journal Entry

A sales order may generate multiple invoices (progress billing). Each invoice references the originating order.

**Invoice response includes:**

```json
{
  "invoiceNumber": "INV-2026-0043",
  "orderId": 2001,
  "orderNumber": "SO-2026-0156"
}
```

## Audit Trail

All accounting transactions are recorded in the audit trail with provenance fields.

### Query Audit Trail

```
GET /api/v1/accounting/audit/transactions?filter.journalEntryId=1001
```

**Response:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "journalEntryId": 1001,
        "eventType": "POST",
        "actorEmail": "user@example.com",
        "timestamp": "2026-03-31T10:00:00Z",
        "drivingDocument": {
          "type": "INVOICE",
          "id": 42,
          "reference": "INV-2026-0042"
        },
        "linkedDocuments": [
          {
            "type": "SALES_ORDER",
            "id": 2001,
            "reference": "SO-2026-0156"
          }
        ]
      }
    ]
  }
}
```

## Provenance Fields

### drivingDocument

The primary document that triggered the transaction.

```json
{
  "drivingDocument": {
    "type": "INVOICE",
    "id": 42,
    "reference": "INV-2026-0042"
  }
}
```

### linkedDocuments

All related documents in the transaction chain.

```json
{
  "linkedDocuments": [
    { "type": "SALES_ORDER", "id": 2001, "reference": "SO-2026-0156" },
    { "type": "DEALER", "id": 101, "reference": "ACME01" }
  ]
}
```

### eventTrail

Complete history of the transaction lifecycle.

```json
{
  "eventTrail": [
    { "event": "CREATED", "timestamp": "2026-03-30T14:00:00Z", "actor": "user@example.com" },
    { "event": "POSTED", "timestamp": "2026-03-31T10:00:00Z", "actor": "user@example.com" }
  ]
}
```

## Canonical Audit Surfaces

The current canonical audit read surfaces are:

| Surface | Endpoint | Description |
|---|---|---|
| Accounting audit trail | `/api/v1/accounting/audit-trail` | Full accounting audit trail with all transaction events |
| Accounting audit transactions | `/api/v1/accounting/audit/transactions` | Paginated list of accounting transactions with filters |
| Accounting audit digest | `/api/v1/accounting/audit/digest` | Summary/digest view of accounting audit data |
| Business events audit | `/api/v1/audit/business-events` | Business-level audit events across modules |
| ML events audit | `/api/v1/audit/ml-events` | Machine learning/analytics audit events |

> **Note**: The paths `/api/v1/admin/audit/events` and `/api/v1/superadmin/audit/platform-events` do not exist in the current API contract. Use the accounting audit endpoints listed above for audit queries.

## Cross-Module Reference Types

| Type | Module | Description |
|---|---|---|
| `SALES_ORDER` | sales | Sales order documents |
| `INVOICE` | invoice | Invoice documents |
| `PURCHASE_ORDER` | purchasing | Purchase order documents |
| `GOODS_RECEIPT` | purchasing | Goods receipt notes |
| `PAYMENT` | accounting | Payment transactions |
| `JOURNAL_ENTRY` | accounting | Journal entries |
| `DEALER` | sales | Dealer/customer records |
| `SUPPLIER` | purchasing | Supplier records |
| `EMPLOYEE` | hr | Employee records |

## Frontend Display Guidance

1. **Navigation** — Allow users to click references to navigate to the related document.
2. **Breadcrumbs** — Show the full reference chain (e.g., "SO-2026-0156 → INV-2026-0042 → JE-1001").
3. **Audit trail** — Display the event trail to show who did what and when.
4. **Driven by** — Always show the `drivingDocument` as the primary source.

## Links

- See [`docs/frontend-portals/accounting/api-contracts.md`](../frontend-portals/accounting/api-contracts.md) for accounting-specific contracts.
- See [exports-and-approvals.md](./exports-and-approvals.md) for export and approval workflows.
