# Admin Approval Action Descriptor Matrix

Purpose: exact contract for what each admin approval queue item means, where it came from, and which backend action endpoint it triggers.

Source of truth:
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/CreditLimitOverrideService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java`

## 1) Queue Payload Contract

`GET /api/v1/admin/approvals` returns:
- `creditRequests`: mixed list containing credit-limit increase requests and dispatch credit override requests
- `payrollRuns`: payroll runs currently awaiting approval

Each queue item is `AdminApprovalItemDto` with:
- `type`, `reference`, `status`, `summary`
- `actionType`, `actionLabel`
- `sourcePortal`
- `approveEndpoint`, `rejectEndpoint`
- `createdAt`

## 2) Action Matrix (What Admin Is Approving)

| Item type (`type`) | Action key (`actionType`) | User-facing action label (`actionLabel`) | Approve endpoint | Reject endpoint | What approval does |
|---|---|---|---|---|---|
| `CREDIT_REQUEST` | `APPROVE_DEALER_CREDIT_REQUEST` | `Approve dealer credit-limit increase` | `/api/v1/sales/credit-requests/{id}/approve` | `/api/v1/sales/credit-requests/{id}/reject` | Sets request status from `PENDING` to `APPROVED`; reject sets to `REJECTED`. |
| `CREDIT_LIMIT_OVERRIDE_REQUEST` | `APPROVE_DISPATCH_CREDIT_OVERRIDE` | `Approve dispatch credit override` | `/api/v1/credit/override-requests/{id}/approve` | `/api/v1/credit/override-requests/{id}/reject` | Approve sets status to `APPROVED`, stores reviewer, review timestamp, and expiry (default +24h if omitted); reject sets `REJECTED` with reviewer metadata. |
| `PAYROLL_RUN` | `APPROVE_PAYROLL_RUN` | `Approve payroll run` | `/api/v1/payroll/runs/{id}/approve` | `null` | Moves payroll run from `CALCULATED` to `APPROVED` (if lines exist). |

## 3) Reference and Portal Attribution Rules

### 3.1 Credit request queue items

- `sourcePortal` is `DEALER_PORTAL`.
- `reference` format is `CR-{id}`.
- `summary` includes:
  - dealer name
  - requested amount
  - optional reason

### 3.2 Credit override queue items

- `sourcePortal` resolution:
  - `FACTORY_PORTAL` if linked `packagingSlip` exists
  - `SALES_PORTAL` if linked `salesOrder` exists
  - default `SALES_PORTAL`
- `reference` resolution:
  - `packagingSlip.slipNumber` if present
  - else `salesOrder.orderNumber` if present
  - else `CLO-{id}`
- `summary` includes:
  - dispatch amount
  - current exposure
  - dealer credit limit
  - required headroom
  - optional requester identity

### 3.3 Payroll queue items

- `sourcePortal` is `HR_PORTAL`.
- `reference` is `runNumber`, fallback `PR-{id}`.
- `summary` includes run type and period range.

## 4) Queue Ordering and Grouping Rules

- Credit queue merges credit requests + override requests and sorts descending by `createdAt`.
- Payroll queue is separate from credit queue (still in the same response payload).
- UI should not flatten payroll items into the credit queue because action semantics and endpoints differ.

## 5) UI Rendering Requirements

Minimum row fields:
- `createdAt`, `type`, `reference`, `summary`, `sourcePortal`, `status`
- Primary action label from backend `actionLabel`

Primary action behavior:
- Action buttons must route by `approveEndpoint` / `rejectEndpoint` from payload, not by hardcoded path maps.
- If `rejectEndpoint` is `null` (payroll), hide the reject action.

Recommended badge mapping:
- `CREDIT_REQUEST` -> "Credit Limit Increase"
- `CREDIT_LIMIT_OVERRIDE_REQUEST` -> "Dispatch Credit Override"
- `PAYROLL_RUN` -> "Payroll Approval"

## 6) Safety and Audit Notes

- Display `summary` verbatim to avoid ambiguous approvals.
- Include `reference` and `sourcePortal` in confirm dialogs.
- After approve/reject, refresh `/api/v1/admin/approvals` to avoid stale action buttons.
