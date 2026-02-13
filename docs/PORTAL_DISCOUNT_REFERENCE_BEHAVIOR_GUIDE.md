# Portal Discount and Reference Behavior Guide

Purpose: user-facing and frontend-facing contract for how discount, write-off, FX adjustment, reference numbers, and approval actions behave across Admin, Sales, and Dealer experiences.

Source of truth in backend:
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/CreditLimitOverrideService.java`

## 1) Shared Terms

- `referenceNumber`: human-visible journal/transaction reference shown in UI and reports.
- `idempotencyKey`: request replay key. Same key + same payload must return same accounting outcome.
- `allocation`: one invoice/purchase application row with `appliedAmount`, optional `discountAmount`, `writeOffAmount`, `fxAdjustment`.
- `netCashContribution`: allocation-level cash effect used to block abusive payloads.

## 2) Accounting-Critical Discount and Reference Rules

These rules affect what users can submit from settlement/payment surfaces and what error copy they see.

### 2.1 Dealer settlement cash formula

Dealer settlement cash uses:

`cashAmount = totalApplied + totalFxGain - totalFxLoss - totalDiscount - totalWriteOff`

Expected UX behavior:
- If computed `cashAmount < 0`, block submit with corrective guidance.
- If `totalDiscount > 0`, require `discountAccountId`.
- If `totalWriteOff > 0`, require `writeOffAccountId`.
- If `totalFxGain > 0`, require `fxGainAccountId`.
- If `totalFxLoss > 0`, require `fxLossAccountId`.
- If explicit payment lines are provided, `sum(payment.amount)` must exactly equal computed `cashAmount`.

### 2.2 Supplier settlement cash formula

Supplier settlement cash uses:

`cashAmount = totalApplied + totalFxLoss - totalFxGain - totalDiscount - totalWriteOff`

Expected UX behavior:
- Same account-selection and non-negative cash checks as dealer settlement.
- `cashAccountId` is mandatory for supplier settlement requests.

### 2.3 Allocation abuse guards (discount/write-off/FX)

- Allocation-level negative net cash is rejected (tolerance: `0.01`).
- Supplier on-account allocations (`purchaseId = null`) cannot include discount/write-off/FX adjustments.
- Allocation cannot exceed current outstanding amount on the linked invoice/purchase beyond tolerance (`0.01`).

Recommended validation copy:
- "Settlement allocation has negative net cash contribution for dealer settlement."
- "Settlement allocation has negative net cash contribution for supplier settlement."
- "On-account supplier settlement allocations cannot include discount/write-off/FX adjustments."
- "Settlement allocation exceeds invoice outstanding amount."
- "Settlement allocation exceeds purchase outstanding amount."

### 2.4 Replay and reference behavior

- Replayed payloads with same `idempotencyKey` must match saved partner + allocation signatures, memo value, and journal-line signature.
- Mismatch is rejected as concurrency conflict; backend does not silently rewrite payload intent.
- If mapping points to one journal and allocations point to another, request is rejected.
- For dealer settlement without explicit `referenceNumber`, backend may use a temporary reserved reference first and then resolve to canonical dealer receipt reference; UI should always display the journal reference returned in response.

## 3) Sales Portal Lifecycle Rules (Admin-Visible Outcomes)

### 3.1 Credit request lifecycle

- New credit request must start at `PENDING`.
- `PUT /api/v1/sales/credit-requests/{id}` cannot transition status; status transitions must use dedicated actions.
- Only pending requests can be approved/rejected.

Admin approval implication:
- A pending credit request appears in admin approval queue with action:
  - `actionType=APPROVE_DEALER_CREDIT_REQUEST`
  - approve endpoint `/api/v1/sales/credit-requests/{id}/approve`
  - reject endpoint `/api/v1/sales/credit-requests/{id}/reject`

### 3.2 Credit override lifecycle

- Override request captures dispatch amount, current exposure, credit limit, and required headroom snapshot.
- Only pending override requests can be approved/rejected.
- Approved override expires at explicit `expiresAt` or defaults to 24 hours if absent.
- Dispatch checks approved override against both dispatch amount tolerance and live headroom tolerance.

Admin approval implication:
- A pending override appears with action:
  - `actionType=APPROVE_DISPATCH_CREDIT_OVERRIDE`
  - approve endpoint `/api/v1/credit/override-requests/{id}/approve`
  - reject endpoint `/api/v1/credit/override-requests/{id}/reject`

## 4) Admin Portal: "What Exactly Is Being Approved"

Detailed descriptor matrix:
- `docs/ADMIN_APPROVAL_ACTION_DESCRIPTOR_MATRIX.md`

`GET /api/v1/admin/approvals` returns two arrays:
- `creditRequests`: credit-limit increase + dispatch credit override approvals
- `payrollRuns`: payroll approvals in `CALCULATED` state

Each item includes:
- `summary`: explicit plain-language explanation of requested action and financial context
- `actionType`: machine-readable action contract
- `actionLabel`: UI-ready button label
- `sourcePortal`: origin context (`DEALER_PORTAL`, `SALES_PORTAL`, `FACTORY_PORTAL`, `HR_PORTAL`)
- `approveEndpoint` and optional `rejectEndpoint`

Minimum admin UI columns:
- `createdAt`, `type`, `reference`, `summary`, `sourcePortal`, `actionLabel`, `status`

## 5) Dealer Portal User Expectations

- Dealer users are read-only for promotions (`GET /api/v1/sales/promotions`); no dealer-side promotion writes.
- Dealer-facing receivable/invoice views should display backend `referenceNumber` as the canonical support and dispute reference.
- Where credit-limit or override outcomes are shown in dealer-facing flows, use lifecycle statuses (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`) instead of inferred booleans.

## 6) UX/QA Checklist Before Staging

- Show returned reference number in success toasts and detail drawers for settlement/payment workflows.
- Keep request payload and replay key stable on retries; do not regenerate key during "retry same intent".
- For admin approvals, render action labels exactly from `actionLabel` and avoid hardcoded generic "Approve" text.
- In approval list cards, include the queue item's `summary` verbatim so approvers can see financial scope before action.
