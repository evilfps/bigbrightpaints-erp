# Idempotency and Errors

Last reviewed: 2026-03-31

## Idempotency Keys

For mutating operations that may be retried, include an `idempotencyKey` in the request header:

```
Idempotency-Key: {{uuid}}
```

### When to Use Idempotency Keys

- `POST` requests that create or modify state
- Long-running operations where network timeouts may cause duplicate submissions
- Export request creation
- Journal entry posting
- Invoice creation
- Payroll run initiation

### Supported Operations

| Endpoint | Idempotency Support |
|---|---|
| `POST /api/v1/accounting/journal-entries` | Yes — use `idempotencyKey` in request body |
| `POST /api/v1/payroll/run` | Yes — use `idempotencyKey` in request body |
| `POST /api/v1/exports/request` | Yes — use `idempotencyKey` in request body |

> **Note**: The invoice endpoints (`GET /api/v1/invoices`, `GET /api/v1/invoices/{id}`) are read-only. There is no `POST /api/v1/invoices` endpoint for creating invoices in the current API.

### Request Example

```json
POST /api/v1/accounting/journal-entries
{
  "entryDate": "2026-03-31",
  "memo": "March rent payment",
  "lines": [
    { "accountCode": "2000", "debit": 5000, "credit": 0 },
    { "accountCode": "1000", "debit": 0, "credit": 5000 }
  ]
}
```

### Response for Duplicate Key

If the same `idempotencyKey` is submitted within the idempotency window (typically 24-48 hours), the backend returns the original response without re-processing:

```json
{
  "success": true,
  "message": "Duplicate request — returning original response",
  "data": { /* original response payload */ }
}
```

## Retry Behavior

### Client-Side Retry Guidance

1. **Immediate retry** — Only for network errors (connection refused, timeout).
2. **Delayed retry** — For 5xx errors, wait 2-5 seconds before retrying.
3. **No retry** — For 4xx errors (except 429 Too Many Requests), the request must be fixed before resubmitting.
4. **Idempotency key** — Always include an idempotency key for stateful retries.

### Idempotency Key Generation

Generate a UUID for each unique operation:

```javascript
const idempotencyKey = crypto.randomUUID();
```

Do not reuse the same key across different operations.

## Error Response Contract

All API errors follow a consistent response structure:

```json
{
  "success": false,
  "message": "Human-readable error message",
  "errorCode": "ERROR_CODE_STRING",
  "timestamp": "2026-03-31T10:00:00Z"
}
```

### Common Error Codes

| Error Code | HTTP Status | Description | Resolution |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | Invalid request payload | Fix field values per validation message |
| `AUTHENTICATION_FAILED` | 401 | Invalid or missing JWT | Re-authenticate and retry |
| `AUTHORIZATION_DENIED` | 403 | Insufficient permissions | Check user roles for this operation |
| `RESOURCE_NOT_FOUND` | 404 | Requested entity not found | Verify the resource ID exists |
| `CONFLICT` | 409 | State conflict (e.g., already processed) | Check current state and retry |
| `IDEMPOTENCY_CONFLICT` | 409 | Duplicate idempotency key | Use original response or new key |
| `PERIOD_CLOSED` | 409 | Accounting period is closed | Open period before retrying |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests | Wait and retry with backoff |
| `INTERNAL_ERROR` | 500 | Unexpected server error | Retry with idempotency key |

### Validation Error Details

For `VALIDATION_ERROR`, additional details may be included:

```json
{
  "success": false,
  "message": "Validation failed",
  "errorCode": "VALIDATION_ERROR",
  "errors": [
    { "field": "amount", "message": "must be greater than 0" },
    { "field": "accountCode", "message": "invalid account code" }
  ],
  "timestamp": "2026-03-31T10:00:00Z"
}
```

## Failure Handling

### Partial Failure in Batch Operations

For operations that process multiple items (e.g., bulk journal entry), the response may indicate partial success:

```json
{
  "success": false,
  "message": "3 of 10 entries failed",
  "errorCode": "PARTIAL_FAILURE",
  "data": {
    "succeeded": ["JE-001", "JE-002", "JE-007"],
    "failed": [
      { "index": 3, "error": "Invalid account code" },
      { "index": 5, "error": "Period closed" }
    ]
  },
  "timestamp": "2026-03-31T10:00:00Z"
}
```

### Timeout Handling

If an operation times out:

1. Check idempotency key status before retrying.
2. Use the status endpoint (if available) to verify whether the operation completed.
3. If the operation was not processed, retry with the same idempotency key.

## Links

- See [`docs/modules/core-idempotency.md`](../modules/core-idempotency.md) for backend idempotency implementation details.
- See [accounting-reference-chains.md](./accounting-reference-chains.md) for cross-document reference handling.
