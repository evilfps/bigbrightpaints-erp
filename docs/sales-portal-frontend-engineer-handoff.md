# Sales Portal Frontend Engineer Handoff (Deep)

Source: `erp-domain/openapi.json` parsed via `.claude/skills/openapi-frontend-endpoint-map/scripts/map_openapi_frontend.py`, then verified against backend controller RBAC.


This handoff mirrors the Admin + Accounting + Manufacturing deliverable pattern with 3 tasks:
1. Sales endpoint expectations (deep)
2. API inventory grouped by domain with cache/debounce/idempotency and inconsistencies
3. Enterprise sales route map with required APIs, states, schema-driven tables/forms, and exact permission gates

## Assumptions (Explicit)

1. Primary sales persona is `ROLE_SALES` with `ROLE_ADMIN` acting as super-user.
2. Some sales actions are shared with `ROLE_ACCOUNTING` and `ROLE_FACTORY`; route visibility must follow exact endpoint gates.
3. OpenAPI security declarations are mostly unspecified; frontend route guards must rely on backend `@PreAuthorize` logic and `auth/me` permission claims.
4. Dispatch confirmation is a high-risk financial boundary action and must require explicit permission checks and audit-friendly confirmation UX.
5. Sales portal should hide inaccessible actions by role+permission to prevent avoidable `403` flows.

## Verified Backend RBAC Baseline (Exact, Code-Verified)

- Method security is active via `@EnableMethodSecurity` in `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java`.
- Authorities include both role names and permission codes via `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/UserPrincipal.java`.
- Default role permissions in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/domain/SystemRole.java`:
  - `ROLE_SALES`: `portal:sales` only
  - `ROLE_ADMIN`: all platform permissions including `dispatch.confirm`
  - `ROLE_FACTORY`: `dispatch.confirm`, `factory.dispatch`

Verified role behavior for sales scope:
- `SalesController`:
  - Orders read: `ROLE_ADMIN|ROLE_SALES|ROLE_FACTORY|ROLE_ACCOUNTING`
  - Orders write + promotions/targets write + credit-request write: `ROLE_ADMIN|ROLE_SALES`
  - Dispatch confirm (`/api/v1/sales/dispatch/confirm`): `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING` + `dispatch.confirm`
- `DealerController`:
  - Dealer master CRUD/search + dunning hold: `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING`
  - Dealer ledger/invoices/aging: also allows `ROLE_DEALER` with dealer access check
- `CreditLimitOverrideController`:
  - Create request: `ROLE_ADMIN|ROLE_SALES|ROLE_FACTORY`
  - List/approve/reject: `ROLE_ADMIN|ROLE_ACCOUNTING` only
- `DispatchController`:
  - Pending/slip/order reads: `ROLE_ADMIN|ROLE_FACTORY|ROLE_SALES`
  - Preview/cancel/status update + confirm: factory/admin only (confirm additionally requires `dispatch.confirm`)
- `InvoiceController`: class-level `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING`
- `OrchestratorController`: sales can call `/orchestrator/dispatch*` and `/orchestrator/traces/{traceId}`, but dispatch aliases are deprecated and return `410 GONE`.

## Shared Foundation APIs (Used Across All Sales Routes)

| Function | Method | Path | Purpose |
|---|---|---|---|
| `authGetMe` | GET | `/api/v1/auth/me` | Session + role/permission claims for route and action gating |
| `profileGet` | GET | `/api/v1/auth/profile` | Load profile page/modal |
| `profileUpdate` | PUT | `/api/v1/auth/profile` | Save profile updates |
| `authChangePassword` | POST | `/api/v1/auth/password/change` | Password update |
| `mfaSetup` | POST | `/api/v1/auth/mfa/setup` | MFA setup |
| `mfaActivate` | POST | `/api/v1/auth/mfa/activate` | MFA activation |
| `mfaDisable` | POST | `/api/v1/auth/mfa/disable` | MFA disable |
| `companiesList` | GET | `/api/v1/companies` | Company switch list |
| `companiesSwitch` | POST | `/api/v1/multi-company/companies/switch` | Switch company context |
| `authLogout` | POST | `/api/v1/auth/logout` | Sign out |

## Task 1: Endpoint Expectations

- Full endpoint expectation map: `/home/realnigga/Desktop/orchestrator_erp/docs/sales-portal-endpoint-map.md`
- Scoped endpoint count: **48**
- Sales Orders & Dispatch Confirmation: **8** endpoints
- Dealers & Receivables Touchpoints: **10** endpoints
- Credit Requests & Overrides: **7** endpoints
- Invoices & Delivery: **5** endpoints
- Promotions & Targets: **8** endpoints
- Dispatch Queue & Slip Operations: **7** endpoints
- Orchestrator & Trace (Legacy/Support): **3** endpoints

## Task 2: Frontend API Inventory (Grouped by Domain)

### Sales Orders & Dispatch Confirmation

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `sales_cancelOrder` | POST | `/api/v1/sales/orders/{id}/cancel` | id (path) | reason (body) | No | No | No |
| `sales_confirmDispatch` | POST | `/api/v1/sales/dispatch/confirm` | lines[].shipQty (body) | adminOverrideCreditLimit (body), confirmedBy (body), dispatchNotes (body), lines (body), lines[].batchId (body), lines[].discount (body), lines[].lineId (body), lines[].notes (body), lines[].priceOverride (body), lines[].taxInclusive (body), lines[].taxRate (body), orderId (body), overrideRequestId (body), packingSlipId (body) | No | No | No |
| `sales_confirmOrder` | POST | `/api/v1/sales/orders/{id}/confirm` | id (path) | - | No | No | No |
| `sales_createOrder` | POST | `/api/v1/sales/orders` | items (body), items[].productCode (body), items[].quantity (body), items[].unitPrice (body), totalAmount (body) | currency (body), dealerId (body), gstInclusive (body), gstRate (body), gstTreatment (body), idempotencyKey (body), items[].description (body), items[].gstRate (body), notes (body), paymentMode (body: `CASH|CREDIT|SPLIT`) | No | No | Conditional |
| `sales_deleteOrder` | DELETE | `/api/v1/sales/orders/{id}` | id (path) | - | No | No | Yes |
| `sales_orders` | GET | `/api/v1/sales/orders` | - | dealerId (query), page (query), size (query), status (query) | Yes | Conditional | Yes |
| `sales_updateOrder` | PUT | `/api/v1/sales/orders/{id}` | id (path), items (body), items[].productCode (body), items[].quantity (body), items[].unitPrice (body), totalAmount (body) | currency (body), dealerId (body), gstInclusive (body), gstRate (body), gstTreatment (body), idempotencyKey (body), items[].description (body), items[].gstRate (body), notes (body), paymentMode (body: `CASH|CREDIT|SPLIT`) | No | No | Yes |
| `sales_updateStatus` | PATCH | `/api/v1/sales/orders/{id}/status` | id (path) | status (body) | No | No | Conditional |

### Dealers & Receivables Touchpoints

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `sales_createDealer` | POST | `/api/v1/dealers` | companyName (body), contactEmail (body), contactPhone (body), name (body) | address (body), creditLimit (body) | No | No | No |
| `sales_dealerAging` | GET | `/api/v1/dealers/{dealerId}/aging` | dealerId (path) | - | Yes | No | Yes |
| `sales_dealerInvoices_1` | GET | `/api/v1/dealers/{dealerId}/invoices` | dealerId (path) | - | Yes | No | Yes |
| `sales_dealerLedger` | GET | `/api/v1/dealers/{dealerId}/ledger` | dealerId (path) | - | Yes | No | Yes |
| `sales_holdIfOverdue` | POST | `/api/v1/dealers/{dealerId}/dunning/hold` | dealerId (path) | minAmount (query), overdueDays (query) | No | No | No |
| `sales_listDealers` | GET | `/api/v1/sales/dealers` | - | - | Yes | No | Yes |
| `sales_listDealers_1` | GET | `/api/v1/dealers` | - | - | Yes | No | Yes |
| `sales_searchDealers` | GET | `/api/v1/sales/dealers/search` | - | query (query) | Yes | Yes | Yes |
| `sales_searchDealers_1` | GET | `/api/v1/dealers/search` | - | query (query) | Yes | Yes | Yes |
| `sales_updateDealer` | PUT | `/api/v1/dealers/{dealerId}` | companyName (body), contactEmail (body), contactPhone (body), dealerId (path), name (body) | address (body), creditLimit (body) | No | No | Yes |

### Credit Requests & Overrides

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `sales_approveRequest` | POST | `/api/v1/credit/override-requests/{id}/approve` | id (path) | expiresAt (body), reason (body) | No | No | No |
| `sales_approveCreditRequest` | POST | `/api/v1/sales/credit-requests/{id}/approve` | id (path) | - | No | No | No |
| `sales_createCreditRequest` | POST | `/api/v1/sales/credit-requests` | amountRequested (body) | dealerId (body), reason (body), status (body) | No | No | No |
| `sales_createRequest` | POST | `/api/v1/credit/override-requests` | dispatchAmount (body) | dealerId (body), expiresAt (body), packagingSlipId (body), reason (body), salesOrderId (body) | No | No | No |
| `sales_creditRequests` | GET | `/api/v1/sales/credit-requests` | - | - | Yes | No | Yes |
| `sales_listRequests` | GET | `/api/v1/credit/override-requests` | - | status (query) | Yes | No | Yes |
| `sales_rejectCreditRequest` | POST | `/api/v1/sales/credit-requests/{id}/reject` | id (path) | - | No | No | No |
| `sales_rejectRequest` | POST | `/api/v1/credit/override-requests/{id}/reject` | id (path) | expiresAt (body), reason (body) | No | No | No |
| `sales_updateCreditRequest` | PUT | `/api/v1/sales/credit-requests/{id}` | amountRequested (body), id (path) | dealerId (body), reason (body), status (body, must match current status only) | No | No | Yes |

### Invoices & Delivery

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `sales_dealerInvoices` | GET | `/api/v1/invoices/dealers/{dealerId}` | dealerId (path) | page (query), size (query) | Yes | No | Yes |
| `sales_downloadInvoicePdf` | GET | `/api/v1/invoices/{id}/pdf` | id (path) | - | Yes | No | Yes |
| `sales_getInvoice` | GET | `/api/v1/invoices/{id}` | id (path) | - | Yes | No | Yes |
| `sales_listInvoices` | GET | `/api/v1/invoices` | - | page (query), size (query) | Yes | No | Yes |
| `sales_sendInvoiceEmail` | POST | `/api/v1/invoices/{id}/email` | id (path) | - | No | No | No |

### Promotions & Targets

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `sales_createPromotion` | POST | `/api/v1/sales/promotions` | discountType (body), discountValue (body), endDate (body), name (body), startDate (body) | description (body), status (body) | No | No | No |
| `sales_createTarget` | POST | `/api/v1/sales/targets` | name (body), periodEnd (body), periodStart (body), targetAmount (body) | achievedAmount (body), assignee (body) | No | No | No |
| `sales_deletePromotion` | DELETE | `/api/v1/sales/promotions/{id}` | id (path) | - | No | No | Yes |
| `sales_deleteTarget` | DELETE | `/api/v1/sales/targets/{id}` | id (path) | - | No | No | Yes |
| `sales_promotions` | GET | `/api/v1/sales/promotions` | - | - | Yes | No | Yes |
| `sales_targets` | GET | `/api/v1/sales/targets` | - | - | Yes | No | Yes |
| `sales_updatePromotion` | PUT | `/api/v1/sales/promotions/{id}` | discountType (body), discountValue (body), endDate (body), id (path), name (body), startDate (body) | description (body), status (body) | No | No | Yes |
| `sales_updateTarget` | PUT | `/api/v1/sales/targets/{id}` | id (path), name (body), periodEnd (body), periodStart (body), targetAmount (body) | achievedAmount (body), assignee (body) | No | No | Yes |

### Dispatch Queue & Slip Operations

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `sales_cancelBackorder` | POST | `/api/v1/dispatch/backorder/{slipId}/cancel` | slipId (path) | reason (query) | No | No | No |
| `sales_confirmDispatch_1` | POST | `/api/v1/dispatch/confirm` | lines (body), lines[].lineId (body), lines[].shippedQuantity (body), packagingSlipId (body) | confirmedBy (body), lines[].notes (body), notes (body), overrideRequestId (body) | No | No | No |
| `sales_getDispatchPreview` | GET | `/api/v1/dispatch/preview/{slipId}` | slipId (path) | - | Yes | No | Yes |
| `sales_getPackagingSlip` | GET | `/api/v1/dispatch/slip/{slipId}` | slipId (path) | - | Yes | No | Yes |
| `sales_getPackagingSlipByOrder` | GET | `/api/v1/dispatch/order/{orderId}` | orderId (path) | - | Yes | No | Yes |
| `sales_getPendingSlips` | GET | `/api/v1/dispatch/pending` | - | - | Yes | Conditional | Yes |
| `sales_updateSlipStatus` | PATCH | `/api/v1/dispatch/slip/{slipId}/status` | slipId (path), status (query) | - | No | No | Conditional |

### Orchestrator & Trace (Legacy/Support)

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `sales_dispatchOrder` | POST | `/api/v1/orchestrator/dispatch` | X-Company-Id (header) | - | No | No | No |
| `sales_dispatchOrderAlias` | POST | `/api/v1/orchestrator/dispatch/{orderId}` | X-Company-Id (header), orderId (path) | - | No | No | No |
| `sales_trace` | GET | `/api/v1/orchestrator/traces/{traceId}` | traceId (path) | - | Yes | No | Yes |

### Endpoints That Look Unsafe or Inconsistent

- `POST /api/v1/sales/dispatch/confirm` and `POST /api/v1/dispatch/confirm` overlap in behavior but differ in role gates and request shape.
- `ROLE_SALES` alone is insufficient for dispatch confirmation by default (`dispatch.confirm` permission is not in sales default permissions).
- `POST /api/v1/orchestrator/dispatch` and `POST /api/v1/orchestrator/dispatch/{orderId}` are exposed in OpenAPI but backend returns `410 GONE` and points to `/api/v1/sales/dispatch/confirm`.
- Dealer APIs are duplicated across `/api/v1/dealers/*` and `/api/v1/sales/dealers*` aliases; choose one canonical client namespace.
- `POST /api/v1/invoices/{id}/email` can return `400` from controller when dealer email is missing, but OpenAPI map mostly shows only success path.
- Many mutating endpoints define only `200` in spec and omit typed error contracts (`400/401/403/404/409/500`).
- Generated operation IDs with suffixes (`confirmDispatch_1`, `listDealers_1`, `searchDealers_1`, `dealerInvoices_1`) are unstable for SDK naming.

## Task 3: Enterprise Sales Route Map

### Universal Header Controls (Sales Portal)

| UI control | Target | APIs | Loading/Error expectations | Gate |
|---|---|---|---|---|
| `My Profile` | `/sales/profile` | `profileGet`, `profileUpdate` | Form skeleton + inline validation | `isAuthenticated()` |
| `Change Password` | `/sales/profile?tab=password` | `authChangePassword` | Submit spinner + inline validation | `isAuthenticated()` |
| `Security & MFA` | `/sales/profile?tab=security` | `mfaSetup`, `mfaActivate`, `mfaDisable` | Action spinners + setup QR/recovery UI | `isAuthenticated()` |
| `Switch Company` | Company switch modal | `companiesList`, `companiesSwitch` | Modal loader + cache invalidation after switch | `isAuthenticated()` + company membership |
| `Sign Out` | Redirect `/auth/login` | `authLogout` | Immediate spinner; fallback local clear on failure | `isAuthenticated()` |

Button naming standard for universal profile controls (use exactly):
- `My Profile`
- `Change Password`
- `Security & MFA`
- `Switch Company`
- `Sign Out`

| Route | Purpose | Backend-enforced gate (exact) |
|---|---|---|
| `/sales/dashboard` | Sales KPI snapshot: orders by status, pending dispatch slips, target progress. | `ROLE_ADMIN|ROLE_SALES` for core sales APIs |
| `/sales/orders` | Order list, create/update, confirm/cancel/status transitions. | Read: `ROLE_ADMIN|ROLE_SALES|ROLE_FACTORY|ROLE_ACCOUNTING`; write: `ROLE_ADMIN|ROLE_SALES` |
| `/sales/dealers` | Dealer directory and dealer profile management. | `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING` |
| `/sales/dealers/:dealerId/receivables` | Dealer ledger/invoices/aging + dunning hold action. | View: `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING|ROLE_DEALER`; hold: `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING` |
| `/sales/credit-requests` | Internal credit request creation + updates (sales-owned flow). | `ROLE_ADMIN|ROLE_SALES` |
| `/sales/credit-overrides/request` | Create credit override request tied to dispatch context. | `ROLE_ADMIN|ROLE_SALES|ROLE_FACTORY` |
| `/sales/credit-overrides/review` | Approve/reject override queue. | `ROLE_ADMIN|ROLE_ACCOUNTING` |
| `/sales/dispatch/queue` | Pending slip visibility by order/slip. | Sales can read queue/slip/order; cannot preview/cancel/status update |
| `/sales/dispatch/confirm` | Final dispatch confirmation command. | Preferred: `/sales/dispatch/confirm` requires `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING` + `dispatch.confirm` |
| `/sales/invoices` | Invoice list, detail, PDF, email to dealer. | `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING` |
| `/sales/promotions` | Promotion lifecycle management. | Read: `ROLE_ADMIN|ROLE_SALES|ROLE_DEALER`; write: `ROLE_ADMIN|ROLE_SALES` |
| `/sales/targets` | Sales target planning/tracking. | `ROLE_ADMIN|ROLE_SALES` |
| `/sales/ops/traces/:traceId` | Orchestrator trace support view for cross-module troubleshooting. | `ROLE_ADMIN|ROLE_ACCOUNTING|ROLE_SALES|ROLE_FACTORY` |

### `/sales/dashboard`
- Required API calls: `sales_orders`, `sales_creditRequests`, `sales_targets`, `sales_getPendingSlips`
- Loading state: KPI skeletons + chart placeholders.
- Empty state: no orders/targets/dispatch items for selected period.
- Error state: widget-level retry + page alert fallback.
- Suggested table columns: orderId, dealerName, status, totalAmount, createdAt, pendingDispatchFlag.
- Suggested form fields: date range, dealer filter, status filter.
- Role gate: `ROLE_ADMIN|ROLE_SALES` for actionable dashboard.

### `/sales/orders`
- Required API calls: `sales_orders`, `sales_createOrder`, `sales_updateOrder`, `sales_confirmOrder`, `sales_cancelOrder`, `sales_updateStatus`, `sales_listDealers`.
- Loading state: list/table skeleton + modal/form spinner.
- Empty state: no orders found + create CTA.
- Error state: inline form validation + row action toast + rollback on failed optimistic updates.
- Suggested table columns: orderId, dealer, status, totalAmount, currency, createdAt, confirmedAt.
- Suggested form fields: dealerId, items[], quantity, unitPrice, tax flags, notes, totalAmount, paymentMode (`CASH|CREDIT|SPLIT`).
- Validation note: credit-limit rejection can occur for all three payment modes; do not special-case `CASH` as a credit-policy bypass.
- Role gate: read broad; write sales/admin only.

### `/sales/dealers`
- Required API calls: `sales_listDealers_1` (or `sales_listDealers` alias), `sales_searchDealers_1`, `sales_createDealer`, `sales_updateDealer`.
- Loading state: table skeleton + debounced search state.
- Empty state: no dealers configured.
- Error state: form validation + retryable list fetch.
- Suggested table columns: dealerId, name, companyName, contactPhone, contactEmail, creditLimit.
- Suggested form fields: name, companyName, contactEmail, contactPhone, address, creditLimit.
- Role gate: `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING`.

### `/sales/dealers/:dealerId/receivables`
- Required API calls: `sales_dealerLedger`, `sales_dealerInvoices_1`, `sales_dealerAging`, `sales_holdIfOverdue`.
- Loading state: tab-level loading (ledger/invoices/aging).
- Empty state: dealer has no receivable history.
- Error state: tab retry + action toast.
- Suggested table columns: invoiceNo, invoiceDate, dueDate, outstandingAmount, daysOverdue, holdStatus, pendingOrderExposure, pendingOrderCount, creditUsed.
- Suggested form fields: dunning hold params (`overdueDays`, `minAmount`).
- Role gate: viewing allows dealer role with self-check; hold action excludes dealer role.

### `/sales/credit-requests`
- Required API calls: `sales_creditRequests`, `sales_createCreditRequest`, `sales_updateCreditRequest`.
- Loading state: list + drawer form loading.
- Empty state: no credit requests.
- Error state: validation toast and retry.
- Suggested table columns: id, dealerId, amountRequested, status, reason, createdAt.
- Suggested form fields: dealerId, amountRequested, reason, status.
- Role gate: `ROLE_ADMIN|ROLE_SALES`.

### `/sales/credit-overrides/request`
- Required API calls: `sales_createRequest`, `sales_orders`, `sales_getPendingSlips`.
- Loading state: contextual order/slip lookup + submit spinner.
- Empty state: no eligible dispatch contexts.
- Error state: validation + API failure toast.
- Suggested table columns: requestId, salesOrderId, packagingSlipId, dispatchAmount, status, expiresAt.
- Suggested form fields: dispatchAmount, dealerId, salesOrderId, packagingSlipId, reason, expiresAt.
- Role gate: `ROLE_ADMIN|ROLE_SALES|ROLE_FACTORY`.

### `/sales/credit-overrides/review`
- Required API calls: `sales_listRequests`, `sales_approveRequest`, `sales_rejectRequest`.
- Loading state: queue loader + action-level spinners.
- Empty state: no pending override requests.
- Error state: action toast + row refresh.
- Suggested table columns: id, requestedBy, dispatchAmount, reason, status, expiresAt.
- Suggested form fields: decision reason, expiry override.
- Role gate: `ROLE_ADMIN|ROLE_ACCOUNTING`.

### `/sales/dispatch/queue`
- Required API calls: `sales_getPendingSlips`, `sales_getPackagingSlip`, `sales_getPackagingSlipByOrder`.
- Loading state: list polling + detail drawer loading.
- Empty state: no pending slips.
- Error state: list retry and preserved filters.
- Suggested table columns: slipId, orderId, dealer, status, createdAt, totalLines.
- Suggested form fields: orderId/slipId lookup filters.
- Role gate: sales read-only for this surface.

### `/sales/dispatch/confirm`
- Required API calls: `sales_confirmDispatch` (preferred), optional reference `sales_confirmDispatch_1` for legacy/ops comparison.
- Loading state: pre-submit validation + final confirm modal.
- Empty state: no dispatchable lines from selected context.
- Error state: line-level error + top banner; prevent duplicate submit.
- Suggested table columns: lineId, sku, orderedQty, shipQty, discount, taxRate.
- Suggested form fields: orderId/packingSlipId, lines[], overrideRequestId, confirmedBy, dispatchNotes.
- Role gate: requires both role and `dispatch.confirm` permission.

### `/sales/invoices`
- Required API calls: `sales_listInvoices`, `sales_getInvoice`, `sales_downloadInvoicePdf`, `sales_sendInvoiceEmail`, `sales_dealerInvoices`.
- Loading state: list/detail split loader + PDF/email action spinners.
- Empty state: no invoices for filters.
- Error state: show email-missing failures cleanly (400 path).
- Suggested table columns: invoiceNumber, dealerName, issueDate, dueDate, totalAmount, status.
- Suggested form fields: email action (recipient preview optional), pagination controls.
- Role gate: `ROLE_ADMIN|ROLE_SALES|ROLE_ACCOUNTING`.

### `/sales/promotions`
- Required API calls: `sales_promotions`, `sales_createPromotion`, `sales_updatePromotion`, `sales_deletePromotion`.
- Loading state: table + form modal loading.
- Empty state: no promotions configured.
- Error state: field validation + action toasts.
- Suggested table columns: name, discountType, discountValue, startDate, endDate, status.
- Suggested form fields: name, discountType, discountValue, startDate, endDate, description, status.
- Role gate: read allows dealer; write sales/admin only.

### `/sales/targets`
- Required API calls: `sales_targets`, `sales_createTarget`, `sales_updateTarget`, `sales_deleteTarget`.
- Loading state: list + form spinner.
- Empty state: no targets set.
- Error state: retry and validation fallback.
- Suggested table columns: name, assignee, periodStart, periodEnd, targetAmount, achievedAmount.
- Suggested form fields: name, assignee, periodStart, periodEnd, targetAmount, achievedAmount.
- Role gate: `ROLE_ADMIN|ROLE_SALES`.

### `/sales/ops/traces/:traceId`
- Required API calls: `sales_trace`.
- Loading state: timeline skeleton.
- Empty state: no events for trace.
- Error state: 404/403-specific messaging.
- Suggested table columns: timestamp, eventType, module, status, message.
- Suggested form fields: traceId lookup.
- Role gate: `ROLE_ADMIN|ROLE_ACCOUNTING|ROLE_SALES|ROLE_FACTORY`.

## Senior Developer Verification Notes (What To Enforce Before FE Freeze)

1. Canonicalize dealer API namespace (prefer one of `/dealers` or `/sales/dealers`) in frontend client layer.
2. Gate dispatch-confirm action on both role and permission (`dispatch.confirm`), not role alone.
3. Remove or hide orchestrator dispatch alias calls in UI because backend intentionally returns `410 GONE`.
4. Decide whether sales users should see factory-owned dispatch controls (`preview`, `cancel`, `status update`) and enforce hide/disable behavior.
5. Add permission E2E tests for key mixed-gate paths: credit override review, dispatch confirm, dealer receivables hold, invoice email.

## Delta Update (2026-02-13): Approval Flow + Accounting Audit Trail Handshake

### Credit Request Lifecycle (Sales-Owned)

Use these constraints in UI state machine:
- Create request always starts as `PENDING`.
- Generic update endpoint is edit-only and must not be used to approve/reject:
  - `PUT /api/v1/sales/credit-requests/{id}`
- Final decisions are action endpoints only:
  - `POST /api/v1/sales/credit-requests/{id}/approve`
  - `POST /api/v1/sales/credit-requests/{id}/reject`

### Credit Override Lifecycle (Dispatch-Owned)

- Create override request:
  - `POST /api/v1/credit/override-requests`
- Approve/reject override request:
  - `POST /api/v1/credit/override-requests/{id}/approve`
  - `POST /api/v1/credit/override-requests/{id}/reject`

Admin queue visibility contract:
- Override requests created from sales context appear in `GET /api/v1/admin/approvals`
  with `type=CREDIT_LIMIT_OVERRIDE_REQUEST` and `sourcePortal=SALES_PORTAL`.

### “What Is Being Approved” UX Requirement

When rendering admin-review-friendly sales views, show these fields if available:
- `reference`
- `summary`
- `actionType`
- `actionLabel`
- `sourcePortal`

Do not derive this text locally when queue payload already provides it.

### Audit Trail Handshake For Posted Dispatch Outcomes

For finance/accounting users operating in sales surfaces:
- After dispatch confirmation posts accounting artifacts, support drill-through by reference/journal to:
  - `GET /api/v1/accounting/audit/transactions`
  - `GET /api/v1/accounting/audit/transactions/{journalEntryId}`

Recommended pattern:
- Add “Open in Accounting Audit Trail” link from dispatch confirmation success/result drawer.

## Delta Update (2026-02-15): Payment-Mode + Idempotency + Pending Exposure Contract (Flyway V2)

- Async-loop verification baseline in this slice uses Flyway `V2` (`db/migration_v2`, `flyway_schema_history_v2`).
- Sales order payment mode map:
  - allowed values: `CASH`, `CREDIT`, `SPLIT`.
  - default when omitted: `CREDIT`.
  - create/update credit-limit checks remain active for all three modes.
- Sales order idempotency compatibility:
  - canonical payload signatures/derived keys omit the default `CREDIT` token.
  - legacy default-credit signature/key shapes including `|CREDIT|` are still accepted on replay.
- Orchestrator idempotency header precedence:
  - `Idempotency-Key` header first.
  - fallback to `X-Request-Id`.
  - fallback to deterministic auto-derived key when both headers are absent.
- Dealer pending exposure semantics shown in receivables views:
  - pending statuses are centrally mapped (`BOOKED`, `RESERVED`, `PENDING_PRODUCTION`, `PENDING_INVENTORY`, `PROCESSING`, `READY_TO_SHIP`, `CONFIRMED`, `ON_HOLD`).
  - orders with active sibling invoices are excluded from pending exposure (`DRAFT`/`VOID`/`REVERSED` invoices do not count as active).
