# DTO Examples

Last reviewed: 2026-04-02

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
  "password": "TempPassword123!",
  "roles": ["ROLE_SALES"]
}
```

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

> **Note**: There is no `GET /api/v1/dealers/{dealerId}` endpoint for fetching a single dealer by ID. Dealer details are available through the list endpoint with filters, or via search endpoints.

## Invoices

> **Note**: Invoice endpoints are **read-only** in the current API. There is no `POST /api/v1/invoices` endpoint for creating invoices. Invoice creation happens through other workflows (e.g., sales order fulfillment).

### List Invoices

```json
GET /api/v1/invoices
```

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

## Error Response Example

```json
{
  "success": false,
  "message": "Validation failed",
  "errorCode": "VALIDATION_ERROR",
  "errors": [
    { "field": "email", "message": "Invalid email format" },
    { "field": "password", "message": "Password must be at least 8 characters" }
  ],
  "timestamp": "2026-03-31T10:00:00Z"
}
```

## Links

- See [auth-and-company-scope.md](./auth-and-company-scope.md) for auth-specific payloads.
- See [idempotency-and-errors.md](./idempotency-and-errors.md) for error handling patterns.
- See [pagination-and-filters.md](./pagination-and-filters.md) for list query patterns.
