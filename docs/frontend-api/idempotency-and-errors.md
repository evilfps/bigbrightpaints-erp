# Idempotency and Errors

Last reviewed: 2026-03-31

## Idempotency rules

- `POST /api/v1/inventory/opening-stock` requires:
  - `Idempotency-Key` header
  - `openingStockBatchKey` query param
- `POST /api/v1/purchasing/goods-receipts` and
  `POST /api/v1/inventory/raw-materials/adjustments` accept only
  `Idempotency-Key`.
- High-value financial writes must preserve and replay the same idempotency key
  from the originating form state.
- Journal creation should use a stable client reference number that the backend
  treats as the canonical idempotency key.
- Frontend should send `Idempotency-Key`, not `X-Idempotency-Key`. Legacy
  headers are not part of the frontend contract even when reject coverage still
  exists server-side.
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

Frontend should expect `ApiResponse.failure(...)` style payloads with explicit
reason codes or detail maps and preserve:

- request trace identifiers
- field validation hints
- blocking dependency identifiers such as missing account ids or period ids
- tenant/runtime denial reasons

Example error envelope:

```json
{
  "success": false,
  "message": "Opening stock batch already processed for this openingStockBatchKey",
  "reason": "VALIDATION_CONFLICT",
  "details": {
    "openingStockBatchKey": "FY26-OPENING-STOCK-01",
    "canonicalPath": "/api/v1/inventory/opening-stock",
    "traceId": "c41fd8c34b2942f4"
  }
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
  when possible and show the operator the canonical reference.
