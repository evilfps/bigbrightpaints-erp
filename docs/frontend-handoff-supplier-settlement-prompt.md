You are implementing supplier settlement/payment UI integration for ERP accounting.

Follow this backend contract exactly.

## Objective
- Keep AP (supplier payable) flows consistent with centralized accounting invariants.
- Support both:
  - purchase-linked AP clearing
  - on-account AP clearing (when liability exists but no raw-material purchase document is linked)

## Architecture rules (must follow)
1. Supplier settlement/payment allocations **must not** use `invoiceId`.
   - `invoiceId` is AR-side (dealer sales invoice), not AP-side.
2. Supplier settlement (`POST /api/v1/accounting/settlements/suppliers`) supports two valid modes:
   - **Purchase-linked**: `purchaseId` provided.
   - **On-account**: `purchaseId` omitted (`null`), `memo` optional (recommended for audit clarity).
3. Supplier direct payment (`POST /api/v1/accounting/suppliers/payments`) is **purchase-linked only**:
   - `purchaseId` is required in every allocation.
   - For on-account supplier credits, use settlement endpoint, not payment endpoint.
4. For supplier cash/bank selection:
   - `cashAccountId` must be an `ASSET` account.
   - AR/AP control accounts are rejected.
5. Backend error payload now includes structured fields:
   - `data.code`, `data.message`, `data.reason`, `data.path`, `data.traceId`, optional `data.details`.
   - UI must surface `reason` and `traceId`.

## Endpoints used by frontend
- Supplier settlements:
  - `POST /api/v1/accounting/settlements/suppliers`
- Supplier direct payments:
  - `POST /api/v1/accounting/suppliers/payments`
- Supplier purchase documents (for purchase-linked allocations):
  - `GET /api/v1/purchasing/raw-material-purchases?supplierId={supplierId}`

## Allocation builder behavior in UI
1. Load supplier purchases from purchasing endpoint.
2. Show only rows with `outstandingAmount > 0` for document-linked allocation picker.
3. Add explicit “On-account” allocation option for settlement endpoint:
   - no `purchaseId`
   - optional `memo` textbox (recommended)
4. For supplier direct payment endpoint, force purchase selection and block on-account allocations.
5. Prevent any supplier flow from sending `invoiceId`.
6. Validate allocation totals client-side before submit.

## Request payload examples

### A) Supplier settlement with purchase-linked allocation
```json
{
  "supplierId": 1,
  "cashAccountId": 20,
  "settlementDate": "2026-02-10",
  "referenceNumber": "SUP-SETTLE-001",
  "idempotencyKey": "SUP-SETTLE-001",
  "memo": "Supplier settlement",
  "allocations": [
    {
      "purchaseId": 55,
      "appliedAmount": 4000.00,
      "discountAmount": 0,
      "writeOffAmount": 0,
      "fxAdjustment": 0,
      "memo": "PO/GRN linked settlement"
    }
  ]
}
```

### B) Supplier settlement with on-account allocation (no purchase document)
```json
{
  "supplierId": 1,
  "cashAccountId": 20,
  "settlementDate": "2026-02-10",
  "referenceNumber": "SUP-SETTLE-ONACC-001",
  "idempotencyKey": "SUP-SETTLE-ONACC-001",
  "memo": "Supplier settlement (on-account)",
  "allocations": [
    {
      "appliedAmount": 1500.00,
      "discountAmount": 0,
      "writeOffAmount": 0,
      "fxAdjustment": 0,
      "memo": "Advance/AP on-account clearing for SKEINA"
    }
  ]
}
```

### C) Invalid payload patterns (frontend must block)
- `invoiceId` sent in supplier settlement/payment allocation.
- `cashAccountId` mapped to AP/AR or non-asset account.

## Error handling UX requirements
1. On non-2xx response, show:
   - primary message: `data.reason || data.message || message`
   - secondary diagnostic: `Trace ID: {data.traceId}` (if present)
2. For validation details:
   - if `data.details` exists, render key-value list.
3. Keep existing HTTP status handling, but do not reduce all errors to generic “Bad Request”.

## Date/time handling note
- Accounting default date uses company timezone on backend.
- If `settlementDate` is omitted, backend uses company current date.
- Frontend should always send explicit `settlementDate` from the form to avoid ambiguity.

## Acceptance criteria
- Supplier settlement succeeds for:
  - purchase-linked allocations
  - on-account allocations with or without memo
- Supplier direct payment succeeds only for purchase-linked allocations.
- Supplier direct payment fails with clear reason if `purchaseId` is missing.
- Supplier settlement fails with clear reason when:
  - `invoiceId` is sent
  - invalid cash account selected
- UI displays backend reason + traceId for failures.
