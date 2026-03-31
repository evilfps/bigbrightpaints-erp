# Exports and Approvals

Last reviewed: 2026-03-31

## Export Workflows

Exports are asynchronous operations that return a file (CSV, Excel, PDF) after processing.

### Export Request Flow

1. **Request export** — Submit an export request with parameters.
2. **Check status** — Retry the download endpoint until the file becomes available.
3. **Download** — When ready, download the generated file.

### Create Export Request

> **Note**: The `parameters` field is typed as a `string` in the OpenAPI schema (not a native object). Pass parameters as a stringified JSON object.

```
POST /api/v1/exports/request
```

**Request:**

```json
{
  "reportType": "JOURNAL_ENTRY",
  "parameters": "{\"startDate\": \"2026-01-01\", \"endDate\": \"2026-03-31\", \"accountCodes\": [\"1000\", \"2000\"]}"
}
```

**Response:**

```json
{
  "success": true,
  "data": {
    "requestId": "export-req-001",
    "status": "PROCESSING",
    "createdAt": "2026-03-31T10:00:00Z"
  },
  "message": "Export request created",
  "timestamp": "2026-03-31T10:00:00Z"
}
```

> **Note**: There is no dedicated status polling endpoint. After creating an export request via `POST /api/v1/exports/request`, the client should retry the download endpoint (`GET /api/v1/exports/{requestId}/download`) until the file becomes available. The download endpoint returns the current request status in its response.

### Download Export

```
GET /api/v1/exports/{requestId}/download
```

**Status Values:**

| Status | Description |
|---|---|
| `PROCESSING` | Export is being generated — poll for completion |
| `COMPLETED` | File is ready for download |
| `FAILED` | Export failed — check error details |

### Export Types (reportType values)

| reportType | Module | Description |
|---|---|---|
| `JOURNAL_ENTRY` | accounting | Journal entries for a date range |
| `LEDGER` | accounting | General ledger report |
| `DEALER_AGING` | accounting | Aged receivables by dealer |
| `INVOICE` | invoice | Invoice export |
| `GST_RETURN` | accounting | GST return data |
| `PRODUCT_CATALOG` | catalog | Product/brand catalog |

## Approval Workflows

Approvals are requests that require an authorized user (typically tenant-admin) to approve or reject before the operation proceeds.

### Approval Request Flow

1. **Request approval** — A user triggers an operation that requires approval.
2. **Admin reviews** — Tenant-admin reviews the request and approves or rejects.
3. **Execution** — If approved, the operation executes; if rejected, the requester is notified.

### Submit Approval Request

> **Note**: Sales order approval requests are not currently exposed as a dedicated REST endpoint. Approval requests are created automatically for credit limit requests, export requests, payroll runs, and period close requests. The `GET /api/v1/admin/approvals` endpoint returns pending approvals with `actionType` and `actionLabel` fields that indicate the required action.

### List Pending Approvals

```
GET /api/v1/admin/approvals?filter.status=PENDING
```

**Response:**

```json
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

> **Note**: The approve/reject actions are not currently exposed as separate REST endpoints. The approval workflow is managed internally based on the approval request type (credit requests, export requests, payroll runs, period close requests). The `GET /api/v1/admin/approvals` endpoint returns pending approvals with `actionType` and `actionLabel` fields that indicate the required action, but the actual approve/reject operations are triggered through module-specific endpoints (e.g., `POST /api/v1/credit/limit-requests/{id}/approve`, `POST /api/v1/payroll/runs/{id}/approve`, `PUT /api/v1/admin/exports/{requestId}/approve`, etc.).

## Approval Ownership

**Export approvals** belong to **tenant-admin**, not accounting.

| Approval Type | Owner Portal | Endpoint |
|---|---|---|
| Export requests | tenant-admin | `/api/v1/admin/approvals` |
| Credit requests | tenant-admin | `/api/v1/admin/approvals` |
| Period close | tenant-admin | `/api/v1/admin/approvals` |
| Payroll runs | tenant-admin | `/api/v1/admin/approvals` |

See [`docs/frontend-portals/tenant-admin/README.md`](../frontend-portals/tenant-admin/README.md) for detailed approval ownership.

## Approval Origin Types

| Origin Type | Description |
|---|---|
| `CREDIT_REQUEST` | Dealer credit limit increase request |
| `CREDIT_LIMIT_OVERRIDE_REQUEST` | Override existing credit limit |
| `PAYROLL_RUN` | Payroll execution request |
| `PERIOD_CLOSE_REQUEST` | Accounting period close request |
| `EXPORT_REQUEST` | Large export request requiring approval |

## Links

- See [`docs/frontend-portals/tenant-admin/workflows.md`](../frontend-portals/tenant-admin/workflows.md) for approval workflows specific to tenant-admin.
- See [`idempotency-and-errors.md`](./idempotency-and-errors.md) for idempotency handling on export requests.
