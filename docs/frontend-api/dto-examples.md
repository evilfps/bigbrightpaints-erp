# DTO Examples

Last reviewed: 2026-04-07

## Overview

This file provides sample request and response payloads for common frontend operations. All examples use the current mainline API contract.

## Authentication

### Login Request

```json
POST /api/v1/auth/login
{
  "email": "admin@example.com",
  "password": "Password123!",
  "companyCode": "ACME01"
}
```

### Login Response

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 3600
  },
  "message": "Login successful",
  "timestamp": "2026-03-31T10:00:00Z"
}
```

### Bootstrap (GET /api/v1/auth/me)

```json
{
  "success": true,
  "data": {
    "email": "admin@example.com",
    "displayName": "John Admin",
    "companyCode": "ACME01",
    "mfaEnabled": false,
    "mustChangePassword": false,
    "roles": ["ROLE_TENANT_ADMIN", "ROLE_ADMIN_SALES_FACTORY_ACCOUNTING"],
    "permissions": ["user:read", "user:write", "export:approve"]
  },
  "message": "Current user retrieved",
  "timestamp": "2026-03-31T10:00:00Z"
}
```

## Users

### List Users

```json
GET /api/v1/admin/users?page=0&size=20&sort=createdAt,desc
{
  "success": true,
  "data": {
    "content": [
      {
        "userId": 1001,
        "email": "user1@example.com",
        "displayName": "User One",
        "status": "ACTIVE",
        "roles": ["ROLE_SALES"],
        "createdAt": "2026-01-15T10:00:00Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 45,
    "totalPages": 3
  }
}
```

### Create User

```json
POST /api/v1/admin/users
{
  "email": "newuser@example.com",
  "displayName": "New User",
  "roles": ["ROLE_SALES"]
}
```

`CreateUserRequest` accepts only `email`, `displayName`, and `roles`.

## Dealers

### List Dealers

```json
GET /api/v1/dealers?status=ALL&page=0&size=50
{
  "success": true,
  "data": [
    {
      "id": 101,
      "publicId": "11111111-2222-3333-4444-555555555555",
      "code": "ACME01",
      "name": "Acme Corporation",
      "companyName": "Acme Corporation Pvt Ltd",
      "email": "ops@acme.example",
      "phone": "9999999999",
      "address": "Industrial Road",
      "receivableAccountId": 7001,
      "receivableAccountCode": "AR-ACME01",
      "portalEmail": "dealer@acme.example",
      "gstNumber": "27ABCDE1234F1Z5",
      "stateCode": "MH",
      "gstRegistrationType": "REGULAR",
      "paymentTerms": "NET_30",
      "region": "WEST",
      "creditStatus": "WITHIN_LIMIT"
    },
    {
      "id": 102,
      "publicId": "66666666-7777-8888-9999-000000000000",
      "code": "BETA02",
      "name": "Beta Retail",
      "companyName": "Beta Retail LLP",
      "email": "owner@beta.example",
      "phone": "8888888888",
      "address": null,
      "receivableAccountId": 7002,
      "receivableAccountCode": "AR-BETA02",
      "portalEmail": null,
      "gstNumber": null,
      "stateCode": "KA",
      "gstRegistrationType": "UNREGISTERED",
      "paymentTerms": "NET_15",
      "region": "SOUTH",
      "creditStatus": "NEAR_LIMIT"
    }
  ]
}
```

> **Dealer directory contract notes**
>
> - Omitting `page` and `size` returns the full active-only directory.
> - `status=ALL` lifts the active-only default.
> - Supplying `page` and/or `size` windows the returned list, but the response
>   still stays `data: DealerResponse[]` without `content`, `totalPages`, or
>   `totalElements`.

### Dealer detail

```json
GET /api/v1/dealers/101
{
  "success": true,
  "message": "Dealer detail",
  "data": {
    "id": 101,
    "publicId": "11111111-2222-3333-4444-555555555555",
    "code": "ACME01",
    "name": "Acme Corporation",
    "companyName": "Acme Corporation Pvt Ltd",
    "email": "ops@acme.example",
    "creditLimit": 1000000.0,
    "outstandingBalance": 120000.0,
    "creditStatus": "WITHIN_LIMIT"
  }
}
```

For missing dealers, `GET /api/v1/dealers/{dealerId}` returns `404 Not Found`.

## Sales orders (draft lifecycle contract)

### Create order (`201 Created` when draft-lifecycle fields are present)

```json
POST /api/v1/sales/orders
→ 201 Created
{
  "dealerId": 101,
  "paymentMode": "CREDIT",
  "paymentTerms": "NET_30",
  "gstTreatment": "INTRA_STATE",
  "items": [
    {
      "productCode": "FG-PRM-WHT-20L",
      "finishedGoodId": 501,
      "quantity": 10,
      "unitPrice": 1850.0,
      "gstRate": 18.0
    }
  ]
}
```

`POST /api/v1/sales/orders` remains `200 OK` for legacy payloads that do not use
`paymentTerms` and `finishedGoodId`.

### Order timeline (canonical + alias fields)

```json
GET /api/v1/sales/orders/1201/timeline
{
  "success": true,
  "data": [
    {
      "id": 77,
      "fromStatus": "DRAFT",
      "toStatus": "CONFIRMED",
      "status": "CONFIRMED",
      "changedBy": "sales.lead@acme.test",
      "actor": "sales.lead@acme.test",
      "changedAt": "2026-04-07T10:25:18Z",
      "timestamp": "2026-04-07T10:25:18Z",
      "reasonCode": "MANUAL_CONFIRM",
      "reason": "Sales confirmation after stock reservation"
    }
  ]
}
```

## Inventory and purchasing (M6 contract refresh)

### Finished goods list (paginated)

```json
GET /api/v1/finished-goods?page=0&size=20
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 501,
        "name": "Premium White 20L",
        "productCode": "FG-PRM-WHT-20L"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

### Raw material stock list

```json
GET /api/v1/raw-materials/stock
{
  "success": true,
  "data": [
    {
      "materialId": 1001,
      "sku": "RM-TIO2-25KG",
      "name": "Titanium Dioxide",
      "quantity": 245.5
    }
  ]
}
```

### Finished-goods stock summary and batches

```json
GET /api/v1/finished-goods/stock-summary
{
  "success": true,
  "data": [
    {
      "finishedGoodId": 501,
      "productCode": "FG-PRM-WHT-20L",
      "name": "Premium White 20L",
      "totalStock": 120,
      "reservedStock": 15,
      "availableStock": 105,
      "weightedAverageCost": 832.45
    }
  ]
}
```

```json
GET /api/v1/finished-goods/501/batches
{
  "success": true,
  "data": [
    {
      "batchId": 9001,
      "batchCode": "FG-501-20260401-A",
      "expiryDate": "2027-04-01",
      "quantity": 80
    }
  ]
}
```

### Inventory batch movement history

```json
GET /api/v1/inventory/batches/9001/movements
{
  "success": true,
  "data": [
    {
      "movementType": "IN",
      "quantity": 80,
      "timestamp": "2026-04-01T09:30:00Z"
    }
  ]
}
```

### Inventory adjustment create (`201 Created`)

```json
POST /api/v1/inventory/adjustments
→ 201 Created
{
  "success": true,
  "message": "Inventory adjustment posted",
  "data": {
    "id": 701,
    "adjustmentDate": "2026-04-07",
    "reason": "Cycle count correction",
    "status": "POSTED",
    "referenceNumber": "INV-ADJ-2026-00701"
  }
}
```

### Opening stock import response (`importedCount`)

```json
POST /api/v1/inventory/opening-stock
{
  "success": true,
  "data": {
    "openingStockBatchKey": "FY26-OPENING-STOCK-01",
    "rowsProcessed": 24,
    "importedCount": 24,
    "finishedGoodBatchesCreated": 8,
    "rawMaterialBatchesCreated": 16,
    "errors": []
  }
}
```

### Supplier create (`201 Created`) and supplier detail

```json
POST /api/v1/suppliers
→ 201 Created
{
  "success": true,
  "message": "Supplier created",
  "data": {
    "id": 301,
    "name": "ABC Chemicals",
    "status": "PENDING",
    "outstandingBalance": 0
  }
}
```

```json
GET /api/v1/suppliers/301
{
  "success": true,
  "data": {
    "id": 301,
    "name": "ABC Chemicals",
    "status": "APPROVED",
    "outstandingBalance": 45250.0
  }
}
```

### Purchase order create (`201 Created`) and timeline

```json
POST /api/v1/purchasing/purchase-orders
→ 201 Created
{
  "success": true,
  "message": "Purchase order recorded",
  "data": {
    "id": 8801,
    "status": "DRAFT",
    "supplierId": 301
  }
}
```

```json
GET /api/v1/purchasing/purchase-orders/8801/timeline
{
  "success": true,
  "data": [
    {
      "id": 1,
      "fromStatus": "DRAFT",
      "toStatus": "APPROVED",
      "status": "APPROVED",
      "changedAt": "2026-04-07T09:45:10Z",
      "timestamp": "2026-04-07T09:45:10Z",
      "actor": "controller@acme.test",
      "changedBy": "controller@acme.test",
      "reasonCode": "MANUAL_APPROVAL",
      "reason": "Approved after review"
    }
  ]
}
```

## Invoices

> **Note**: Invoice endpoints are **read-only** in the current API. There is no `POST /api/v1/invoices` endpoint for creating invoices. Invoice creation happens through other workflows (e.g., sales order fulfillment).

### List Invoices

```json
GET /api/v1/invoices
```

```json
GET /api/v1/invoices?orderId=1201
```

Use `orderId` to scope the list to invoices linked to a specific sales order.

### Invoice Response

```json
{
  "success": true,
  "data": {
    "invoiceId": 1001,
    "invoiceNumber": "INV-2026-0042",
    "dealerId": 101,
    "dealerName": "Acme Corporation",
    "invoiceDate": "2026-03-31",
    "dueDate": "2026-04-30",
    "status": "DRAFT",
    "subtotal": 1500.00,
    "taxAmount": 150.00,
    "totalAmount": 1650.00,
    "lines": [
      {
        "lineId": 1,
        "productId": 1001,
        "productName": "Paint - White",
        "quantity": 10,
        "unitPrice": 150.00,
        "taxRate": 10.0,
        "lineTotal": 1650.00
      }
    ],
    "createdAt": "2026-03-31T10:00:00Z",
    "createdBy": "user@example.com"
  }
}
```

## Journal Entries

### Create Journal Entry

```json
POST /api/v1/accounting/journal-entries
{
  "entryDate": "2026-03-31",
  "memo": "Rent payment for March",
  "lines": [
    {
      "accountCode": "2000",
      "debit": 5000.00,
      "credit": 0.00,
      "memo": "Rent expense"
    },
    {
      "accountCode": "1000",
      "debit": 0.00,
      "credit": 5000.00,
      "memo": "Cash payment"
    }
  ]
}
```

### Journal Entry Response

```json
{
  "success": true,
  "data": {
    "journalEntryId": 1001,
    "referenceNumber": "JE-2026-0156",
    "entryDate": "2026-03-31",
    "status": "POSTED",
    "totalDebit": 5000.00,
    "totalCredit": 5000.00,
    "memo": "Rent payment for March",
    "lines": [
      { "accountCode": "2000", "debit": 5000.00, "credit": 0.00 },
      { "accountCode": "1000", "debit": 0.00, "credit": 5000.00 }
    ],
    "postedAt": "2026-03-31T10:00:00Z",
    "postedBy": "user@example.com"
  }
}
```

## Approvals

### List Pending Approvals

```json
GET /api/v1/admin/approvals?filter.status=PENDING
{
  "success": true,
  "data": {
    "content": [
      {
        "approvalId": "approval-001",
        "originType": "CREDIT_REQUEST",
        "ownerType": "SALES",
        "requesterEmail": "user@example.com",
        "summary": "Credit limit increase request for Acme Corp",
        "status": "PENDING",
        "createdAt": "2026-03-31T10:00:00Z"
      }
    ]
  }
}
```

### Approve Request

> **Note**: Approve/reject actions are not exposed as separate REST endpoints on `/api/v1/admin/approvals`. Instead, approvals are handled through module-specific endpoints:
> - Credit limit requests: `POST /api/v1/credit/limit-requests/{id}/approve`
> - Credit limit overrides: `POST /api/v1/credit/override-requests/{id}/approve`
> - Payroll runs: `POST /api/v1/payroll/runs/{id}/approve`
> - Export requests: `PUT /api/v1/admin/exports/{requestId}/approve`
> - Period close: `POST /api/v1/accounting/periods/{periodId}/approve-close`
> - Purchase orders: `POST /api/v1/purchasing/purchase-orders/{id}/approve`

### Approve Response

```json
{
  "success": true,
  "data": {
    "approvalId": "approval-001",
    "status": "APPROVED",
    "approvedBy": "admin@example.com",
    "approvedAt": "2026-03-31T11:00:00Z",
    "note": "Approved after credit review"
  }
}
```

## Exports

### Request Export

> **Note**: The `parameters` field is typed as a `string` in the OpenAPI schema (not a native object). Pass parameters as a stringified JSON object, e.g., `"parameters": "{\"startDate\": \"2026-01-01\", \"endDate\": \"2026-03-31\"}"`.

```json
POST /api/v1/exports/request
{
  "reportType": "JOURNAL_ENTRY",
  "parameters": "{\"startDate\": \"2026-01-01\", \"endDate\": \"2026-03-31\"}"
}
```

### Export Response

```json
{
  "success": true,
  "data": {
    "requestId": "export-req-001",
    "status": "PROCESSING",
    "createdAt": "2026-03-31T10:00:00Z"
  }
}
```

## Tenant onboarding success

```json
{
  "success": true,
  "data": {
    "companyCode": "ACME",
    "adminEmail": "controller@acme.test",
    "seededChartOfAccounts": true,
    "defaultAccountingPeriodCreated": true,
    "tenantAdminProvisioned": true
  }
}
```

Treat the three bootstrap flags as required success truth, not optional
metadata.

## Company default accounts

```json
{
  "success": true,
  "data": {
    "inventoryAccountId": 1101,
    "cogsAccountId": 5101,
    "revenueAccountId": 4101,
    "discountAccountId": 4102,
    "fgDiscountAccountId": 4102,
    "taxAccountId": 2105
  }
}
```

Finished goods may also rely on explicit item metadata fields:

- `fgValuationAccountId`
- `fgCogsAccountId`
- `fgRevenueAccountId`
- `fgDiscountAccountId`
- `fgTaxAccountId`

`POST /api/v1/catalog/items` also accepts explicit account override fields in
`CatalogItemRequest`:

- `inventoryAccountId`
- `cogsAccountId`
- `revenueAccountId`

`GET /api/v1/accounting/accounts/tree` returns recursive nodes where each
`AccountNode` includes `children`.

## Journal create request

```json
{
  "entryDate": "2026-03-28",
  "referenceNumber": "JRNL-UI-2026-00042",
  "memo": "Write-off adjustment",
  "lines": [
    {
      "accountId": 6102,
      "debit": 500.00,
      "credit": 0
    },
    {
      "accountId": 1101,
      "debit": 0,
      "credit": 500.00
    }
  ]
}
```

Public route:

- `POST /api/v1/accounting/journal-entries`

## Dealer receipt request

`POST /api/v1/accounting/receipts/dealer` now supports true on-account receipts
without forcing invoice allocations.

### Unallocated on-account receipt (no allocations)

```json
{
  "dealerId": 101,
  "cashAccountId": 1101,
  "amount": 1250.00,
  "referenceNumber": "DR-ON-2026-0042",
  "memo": "On-account receipt"
}
```

### Explicit unapplied carry row

```json
{
  "dealerId": 101,
  "cashAccountId": 1101,
  "amount": 1250.00,
  "referenceNumber": "DR-ON-2026-0043",
  "memo": "On-account receipt",
  "allocations": [
    {
      "appliedAmount": 1250.00,
      "applicationType": "ON_ACCOUNT",
      "memo": "Carry on account"
    }
  ]
}
```

Rules:

- `allocations` is optional for dealer receipts.
- If `allocations` is provided, `appliedAmount` totals must equal `amount`.
- Unapplied rows must not include `invoiceId`; use `applicationType` values
  `ON_ACCOUNT` or `FUTURE_APPLICATION`.

## Partner settlement request

Dealer and supplier settlement routes now share the same request body.

```json
POST /api/v1/accounting/settlements/dealers
{
  "partnerType": "DEALER",
  "partnerId": 101,
  "cashAccountId": 1101,
  "amount": 1650.00,
  "unappliedAmountApplication": "ON_ACCOUNT",
  "settlementDate": "2026-03-31",
  "referenceNumber": "RCPT-2026-0042",
  "memo": "Invoice settlement",
  "allocations": [
    {
      "invoiceId": 1001,
      "appliedAmount": 1650.00,
      "discountAmount": 0,
      "fxAdjustment": 0,
      "writeOffAmount": 0,
      "applicationType": "DOCUMENT",
      "memo": "Full settlement"
    }
  ]
}
```

Use the same DTO on:

- `POST /api/v1/accounting/settlements/dealers`
- `POST /api/v1/accounting/settlements/suppliers`

Change `partnerType`, `partnerId`, and allocation document identifiers to match
the partner flow. Do not send retired `DealerSettlementRequest` or
`SupplierSettlementRequest` payloads.
Do not send a separate `payments` array; settlement cash modeling uses
`cashAccountId` + `amount` within `PartnerSettlementRequest`.

`unappliedAmountApplication` behavior depends on request mode:

- **With explicit `allocations` present**: allocation rows are canonical, and
  `allocations[].applicationType` drives behavior (`DOCUMENT` for
  invoice/purchase rows, unapplied modes only for rows without a document id).
  Header-level `unappliedAmountApplication` is ignored for those explicit rows.
- **Without `allocations` (header/FIFO settlement mode)**: the backend derives
  document allocations automatically and uses header-level
  `unappliedAmountApplication` only for any remaining unapplied balance. In this
  mode, use `ON_ACCOUNT` or `FUTURE_APPLICATION`; `DOCUMENT` is rejected.

## Accounting period create or update request

```json
POST /api/v1/accounting/periods
{
  "year": 2026,
  "month": 4,
  "costingMethod": "FIFO"
}
```

```json
PUT /api/v1/accounting/periods/12
{
  "costingMethod": "WEIGHTED_AVERAGE"
}
```

Both routes use the same `AccountingPeriodRequest` DTO.

## Period close action request

```json
POST /api/v1/accounting/periods/12/request-close
{
  "note": "Bank reconciliation and checklist completed",
  "force": true
}
```

The same `PeriodCloseRequestActionRequest` body applies to:

- `POST /api/v1/accounting/periods/{periodId}/request-close`
- `POST /api/v1/accounting/periods/{periodId}/approve-close`
- `POST /api/v1/accounting/periods/{periodId}/reject-close`

## Period reopen request

```json
POST /api/v1/accounting/periods/12/reopen
{
  "reason": "Late audit adjustment"
}
```

## Journal reversal request

```json
{
  "reversalDate": "2026-03-29",
  "reason": "Wrong inventory adjustment source document",
  "memo": "Reverse and relink to corrected batch",
  "adminOverride": false,
  "relatedEntryIds": [9002, 9003],
  "cascadeRelatedEntries": true
}
```

Public route:

- `POST /api/v1/accounting/journal-entries/{entryId}/reverse`

## Export request create

```json
{
  "reportType": "TRIAL_BALANCE",
  "parameters": "periodId=3&startDate=2026-03-01&endDate=2026-03-31&exportFormat=CSV"
}
```

Public route:

- `POST /api/v1/exports/request`

## Approval inbox row

```json
{
  "id": "approval-114",
  "originType": "PERIOD_CLOSE_REQUEST",
  "ownerType": "ACCOUNTING",
  "status": "PENDING",
  "requestedBy": "controller@acme.test",
  "createdAt": "2026-03-28T10:22:11Z"
}
```

Use this for maker-checker close and export approvals. The inbox list itself is
always `GET /api/v1/admin/approvals`.

## Paginated list response

```json
{
  "success": true,
  "data": {
    "content": [],
    "page": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0
  }
}
```

## Error Response Example (`POST /api/v1/admin/users` validation)

For `@Valid` request-body failures, field-level hints are returned under
`data.errors`.

```json
{
  "success": false,
  "message": "Validation failed: email must be a well-formed email address; displayName must not be blank; roles must not be empty",
  "data": {
    "code": "VAL_001",
    "message": "Validation failed: email must be a well-formed email address; displayName must not be blank; roles must not be empty",
    "reason": "Validation failed: email must be a well-formed email address; displayName must not be blank; roles must not be empty",
    "traceId": "c2608f47-b9bb-4ec8-b725-c8caf7302af5",
    "errors": {
      "email": "must be a well-formed email address",
      "displayName": "must not be blank",
      "roles": "must not be empty"
    }
  },
  "timestamp": "2026-03-31T10:00:00Z"
}
```

## Links

- See [auth-and-company-scope.md](./auth-and-company-scope.md) for auth-specific payloads.
- See [idempotency-and-errors.md](./idempotency-and-errors.md) for error handling patterns.
- See [pagination-and-filters.md](./pagination-and-filters.md) for list query patterns.
