# Idempotency and Errors

Last reviewed: 2026-04-07

## Idempotency rules

- `POST /api/v1/inventory/opening-stock` requires:
  - `Idempotency-Key` header
  - `openingStockBatchKey` query param
- `POST /api/v1/purchasing/goods-receipts` and
  `POST /api/v1/inventory/raw-materials/adjustments` accept only
  `Idempotency-Key`.
- `POST /api/v1/purchasing/raw-material-purchases` and
  `POST /api/v1/purchasing/raw-material-purchases/returns` accept only
  `Idempotency-Key`. Replaying the same key returns the original purchase
  invoice or purchase return result without duplicate stock/AP posting.
- `POST /api/v1/accounting/receipts/dealer`,
  `POST /api/v1/accounting/receipts/dealer/hybrid`,
  `POST /api/v1/accounting/settlements/dealers`,
  `POST /api/v1/accounting/dealers/{dealerId}/auto-settle`,
  `POST /api/v1/accounting/settlements/suppliers`, and
  `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle` also accept only
  `Idempotency-Key`.
- High-value financial writes must preserve and replay the same idempotency key
  from the originating form state.
- Journal creation should use a stable client reference number that the backend
  treats as the canonical idempotency key.
- Debit-note and credit-note retries must reuse the same source document,
  `referenceNumber`, and `idempotencyKey`. Reusing a correction reference for a
  different purchase or invoice is a new action, not a safe retry.
- Frontend should send `Idempotency-Key`, not `X-Idempotency-Key`. Legacy
  headers are not part of the frontend contract. Accounting settlement/receipt
  routes and purchasing invoice/return write routes reject
  `X-Idempotency-Key` with a `400` validation error and details that point
  back to the canonical header and path.
- Reversal requests should never mint a second endpoint for cascade behavior;
  use the canonical reverse path with explicit payload fields.

Recommended client behavior:

- Generate one idempotency key per intentional user action.
- Reuse the same key for safe retries of the same action.
- Mint a new key only when the business intent materially changes.
- Keep the key bound to the draft/form state so a refresh or reconnect does not
  create duplicate financial writes.

## Error handling rules

- Validation failures are user-fixable and should stay inline with field-level
  feedback where possible.
- Auth failures, tenant-scope mismatches, module gating, and lifecycle denials
  are hard stops. Do not auto-retry them with legacy headers or alternate
  endpoints.
- Conflict or duplicate responses on idempotent writes should resolve to a
  deterministic success-or-already-applied UX, not a blind retry loop.
- Accounting readiness blockers are exact blockers. If account mapping, GST
  setup, or onboarding bootstrap is incomplete, the UI must block downstream
  actions instead of allowing partial entry.

## Error payload expectations

Frontend should expect `ApiResponse.failure(...)` style payloads with error
metadata inside `data` (`data.code`, `data.reason`, `data.path`,
`data.traceId`, and optional `data.details`) and preserve:

- request trace identifiers
- field validation hints
- blocking dependency identifiers such as missing account ids or period ids
- tenant/runtime denial reasons

Example error envelope:

```json
{
  "success": false,
  "message": "Opening stock batch already processed for this openingStockBatchKey",
  "data": {
    "code": "VAL_001",
    "message": "Opening stock batch already processed for this openingStockBatchKey",
    "reason": "Opening stock batch already processed for this openingStockBatchKey",
    "path": "/api/v1/inventory/opening-stock",
    "traceId": "d84e75a7-b1c2-44ee-a84d-4ab5fda403b6",
    "details": {
      "openingStockBatchKey": "FY26-OPENING-STOCK-01",
      "canonicalPath": "/api/v1/inventory/opening-stock"
    }
  },
  "timestamp": "2026-04-06T16:00:00Z"
}
```

Legacy-header rejection example:

```json
{
  "success": false,
  "message": "X-Idempotency-Key is not supported for dealer settlements; use Idempotency-Key",
  "data": {
    "code": "VAL_001",
    "message": "X-Idempotency-Key is not supported for dealer settlements; use Idempotency-Key",
    "reason": "X-Idempotency-Key is not supported for dealer settlements; use Idempotency-Key",
    "path": "/api/v1/accounting/settlements/dealers",
    "traceId": "749b4ef0-1aa1-4cc7-b404-5df5bd5d9d50",
    "details": {
      "legacyHeader": "X-Idempotency-Key",
      "canonicalHeader": "Idempotency-Key",
      "canonicalPath": "/api/v1/accounting/settlements/dealers"
    }
  },
  "timestamp": "2026-04-06T16:00:00Z"
}
```

UI handling rules by class:

- `400` or validation failure: keep operator on the form and map field errors
  inline.
- `401` or token failure: send the user through auth recovery.
- `403` with business reason such as `PASSWORD_CHANGE_REQUIRED`,
  tenant-scope mismatch, lifecycle suspension, or module pause: show a blocked
  state, not a retry toast.
- `409` or duplicate/idempotency conflict: resolve to previously applied work
  only when the returned canonical reference chain matches the requested source
  document. If the backend reports another `sourceReference` / provenance
  chain, keep the operator on the draft and require a new reference/key.
