# Dealer Portal Frontend Engineer Handoff (Deep)

Source: `erp-domain/openapi.json` parsed via `.claude/skills/openapi-frontend-endpoint-map/scripts/map_openapi_frontend.py`, then verified against backend controller RBAC and dealer business logic.


This handoff mirrors the Admin + Accounting + Manufacturing + Sales deliverable pattern with 3 tasks:
1. Dealer endpoint expectations (deep)
2. API inventory grouped by domain with cache/debounce/idempotency and inconsistencies
3. Enterprise dealer route map with required APIs, states, schema-driven tables/forms, and exact permission gates

## Assumptions (Explicit)

1. Dealer Portal persona is `ROLE_DEALER` only; all data should be self-scoped to the authenticated dealer.
2. Primary dealer APIs are under `/api/v1/dealer-portal/*`; `/api/v1/dealers/{dealerId}/*` are alias reads and should not be primary client paths.
3. OpenAPI security sections are mostly unspecified; backend `@PreAuthorize` and service-level access checks are authoritative.
4. Dealer portal is mostly read-heavy (dashboard/ledger/invoices/aging/orders) with one write flow for dealer credit-limit requests.
5. Universal profile controls should remain consistent across portals; some controls may be hidden/disabled for dealer role if backend does not expose required APIs.

## Verified Backend RBAC Baseline (Exact, Code-Verified)

- Method security is active via `@EnableMethodSecurity` in `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java`.
- Authorities include roles + permissions via `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/UserPrincipal.java`.
- Default dealer role permission in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/domain/SystemRole.java`:
  - `ROLE_DEALER`: `portal:dealer`

Verified role behavior for dealer scope:
- `DealerPortalController` is class-gated to `ROLE_DEALER` and returns only authenticated dealer data (`/dealer-portal/dashboard|ledger|invoices|aging|orders|invoices/{invoiceId}/pdf`).
- `DealerController` read endpoints (`/{dealerId}/ledger|invoices|aging`) also allow `ROLE_DEALER`, but service-level `verifyDealerAccess()` enforces self-only access.
- `SalesController` allows dealer read access to `GET /api/v1/sales/promotions`; promotion writes are sales/admin only.
- `InvoiceController` is not dealer-accessible; dealer PDF access must use `/api/v1/dealer-portal/invoices/{invoiceId}/pdf`.

## Shared Foundation APIs (Used Across All Dealer Routes)

| Function | Method | Path | Purpose |
|---|---|---|---|
| `authGetMe` | GET | `/api/v1/auth/me` | Session + role/permission claims |
| `profileGet` | GET | `/api/v1/auth/profile` | Load profile |
| `profileUpdate` | PUT | `/api/v1/auth/profile` | Update profile |
| `authChangePassword` | POST | `/api/v1/auth/password/change` | Password update |
| `mfaSetup` | POST | `/api/v1/auth/mfa/setup` | MFA setup |
| `mfaActivate` | POST | `/api/v1/auth/mfa/activate` | MFA activation |
| `mfaDisable` | POST | `/api/v1/auth/mfa/disable` | MFA disable |
| `companiesSwitch` | POST | `/api/v1/multi-company/companies/switch` | Company switch action (if dealer has multi-company membership) |
| `authLogout` | POST | `/api/v1/auth/logout` | Sign out |

## Task 1: Endpoint Expectations

- Full endpoint expectation map: `/home/realnigga/Desktop/orchestrator_erp/docs/dealer-portal-endpoint-map.md`
- Scoped endpoint count: **11**
- Dealer Self-Service Core: **7** endpoints
- Dealer Receivables Alias APIs: **3** endpoints
- Dealer Promotions (Read-Only): **1** endpoints

## Task 2: Frontend API Inventory (Grouped by Domain)

### Dealer Self-Service Core

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `dealer_createCreditRequest` | POST | `/api/v1/dealer-portal/credit-requests` | amountRequested (body) | reason (body) | No | No | No |
| `dealer_getDashboard` | GET | `/api/v1/dealer-portal/dashboard` | - | - | Yes | No | Yes |
| `dealer_getMyAging` | GET | `/api/v1/dealer-portal/aging` | - | - | Yes | No | Yes |
| `dealer_getMyInvoicePdf` | GET | `/api/v1/dealer-portal/invoices/{invoiceId}/pdf` | invoiceId (path) | - | Yes | No | Yes |
| `dealer_getMyInvoices` | GET | `/api/v1/dealer-portal/invoices` | - | - | Yes | No | Yes |
| `dealer_getMyLedger` | GET | `/api/v1/dealer-portal/ledger` | - | - | Yes | No | Yes |
| `dealer_getMyOrders` | GET | `/api/v1/dealer-portal/orders` | - | - | Yes | No | Yes |

### Dealer Receivables Alias APIs

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `dealer_dealerAging` | GET | `/api/v1/dealers/{dealerId}/aging` | dealerId (path) | - | Yes | No | Yes |
| `dealer_dealerInvoices_1` | GET | `/api/v1/dealers/{dealerId}/invoices` | dealerId (path) | - | Yes | No | Yes |
| `dealer_dealerLedger` | GET | `/api/v1/dealers/{dealerId}/ledger` | dealerId (path) | - | Yes | No | Yes |

### Dealer Promotions (Read-Only)

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `dealer_promotions` | GET | `/api/v1/sales/promotions` | - | - | Yes | No | Yes |

### Endpoints That Look Unsafe or Inconsistent

- Dealer data has two API surfaces (`/api/v1/dealer-portal/*` and `/api/v1/dealers/{dealerId}/*`); using both in FE can create duplicate client models and inconsistent cache keys.
- Alias dealer endpoints rely on service-level access check (`verifyDealerAccess`) rather than endpoint path ownership alone; avoid exposing editable dealerId in frontend URLs for normal flow.
- Dealer role can read promotions but cannot create/update/delete them; enforce read-only promo UI for dealer portal.
- `GET /api/v1/dealer-portal/invoices/{invoiceId}/pdf` returns binary PDF inline; wrong fetch mode can break browser preview/download UX.
- Scoped endpoints in OpenAPI mostly document success-only responses; frontend should include robust fallback handling for 401/403/404/500.

## Task 3: Enterprise Dealer Route Map

### Universal Header Controls (Dealer Portal)

| UI control | Target | APIs | Loading/Error expectations | Gate |
|---|---|---|---|---|
| `My Profile` | `/dealer/profile` | `profileGet`, `profileUpdate` | Form skeleton + inline validation | `isAuthenticated()` |
| `Change Password` | `/dealer/profile?tab=password` | `authChangePassword` | Submit spinner + inline validation | `isAuthenticated()` |
| `Security & MFA` | `/dealer/profile?tab=security` | `mfaSetup`, `mfaActivate`, `mfaDisable` | Action spinners + code validation states | `isAuthenticated()` |
| `Switch Company` | Company switch modal | `companiesSwitch` | If company list unavailable for dealer role, show disabled state + help text | `isAuthenticated()` + membership |
| `Sign Out` | Redirect `/auth/login` | `authLogout` | Immediate spinner; fallback local clear on failure | `isAuthenticated()` |

Button naming standard for universal profile controls (use exactly):
- `My Profile`
- `Change Password`
- `Security & MFA`
- `Switch Company`
- `Sign Out`

| Route | Purpose | Backend-enforced gate (exact) |
|---|---|---|
| `/dealer/credit-requests` | Dealer submits credit-limit increase requests for admin/accounting review. | `ROLE_DEALER` (`POST /api/v1/dealer-portal/credit-requests`) |
| `/dealer/dashboard` | Dealer financial snapshot: balance, outstanding, pending order exposure, credit used, available credit, pending invoices. | `ROLE_DEALER` (`/api/v1/dealer-portal/dashboard`) |
| `/dealer/ledger` | Dealer ledger entries and running balance view. | `ROLE_DEALER` |
| `/dealer/invoices` | Invoice listing with status/outstanding and PDF download. | `ROLE_DEALER` |
| `/dealer/aging` | Aging bucket view + overdue invoice list. | `ROLE_DEALER` |
| `/dealer/orders` | Dealer order history and statuses. | `ROLE_DEALER` |
| `/dealer/promotions` | Read-only active promotions visible to dealer users. | `ROLE_DEALER` allowed on `GET /api/v1/sales/promotions` |
| `/dealer/receivables/alias/:dealerId` | Optional support/debug route using dealer alias endpoints. | `ROLE_DEALER` + service-level self-access check (`verifyDealerAccess`) |

### `/dealer/dashboard`
- Required API calls: `dealer_getDashboard`
- Loading state: KPI card skeletons.
- Empty state: no receivable snapshot available.
- Error state: retry panel with support hint.
- Suggested table/tiles: dealerName, currentBalance, creditLimit, totalOutstanding, pendingOrderExposure, pendingOrderCount, creditUsed, availableCredit, pendingInvoices, agingBuckets.
- Suggested form fields: optional date/view filter only if backend later supports it.
- Role gate: `ROLE_DEALER`.

### `/dealer/ledger`
- Required API calls: `dealer_getMyLedger`
- Loading state: table skeleton.
- Empty state: no ledger entries.
- Error state: retry + fallback support message.
- Suggested table columns: transactionDate, referenceNumber, description, debit, credit, runningBalance.
- Suggested form fields: local-only search/filter UI (client-side).
- Role gate: `ROLE_DEALER`.

### `/dealer/invoices`
- Required API calls: `dealer_getMyInvoices`, `dealer_getMyInvoicePdf`
- Loading state: list skeleton + PDF action spinner.
- Empty state: no invoices.
- Error state: per-row download error + global retry.
- Suggested table columns: invoiceNumber, issueDate, dueDate, totalAmount, outstandingAmount, status, currency.
- Suggested form fields: local filter chips (status, overdue).
- Role gate: `ROLE_DEALER`.

### `/dealer/aging`
- Required API calls: `dealer_getMyAging`
- Loading state: bucket cards + overdue list skeleton.
- Empty state: no outstanding invoices.
- Error state: retry with cached snapshot fallback.
- Suggested table columns: invoiceNumber, dueDate, daysOverdue, outstandingAmount.
- Suggested form fields: no backend filters currently; local display toggles only.
- Role gate: `ROLE_DEALER`.

### `/dealer/orders`
- Required API calls: `dealer_getMyOrders`
- Loading state: order table skeleton.
- Empty state: no orders yet.
- Error state: retry + fallback info.
- Suggested table columns: orderNumber, createdAt, status, totalAmount, pendingCreditExposure, notes.
- Suggested form fields: local-only filters (status/date).
- Role gate: `ROLE_DEALER`.

### `/dealer/credit-requests`
- Required API calls: `dealer_createCreditRequest`
- Loading state: submit spinner + disabled submit button.
- Empty state: show create form by default.
- Error state: inline validation + API error banner.
- Suggested form fields: amountRequested (required positive decimal), reason (optional text).
- Role gate: `ROLE_DEALER`.
- Workflow note: successful create always returns `PENDING` and should appear in admin approvals queue (`GET /api/v1/admin/approvals`).

### `/dealer/promotions`
- Required API calls: `dealer_promotions`
- Loading state: promotion cards skeleton.
- Empty state: no active promotions.
- Error state: retry notice.
- Suggested columns/cards: name, discountType, discountValue, startDate, endDate, status, description.
- Suggested form fields: none (read-only).
- Role gate: `ROLE_DEALER` read-only.

### `/dealer/receivables/alias/:dealerId` (Optional/Internal)
- Required API calls: `dealer_dealerLedger`, `dealer_dealerInvoices_1`, `dealer_dealerAging`
- Loading state: tab-level loading.
- Empty state: no data.
- Error state: explicit self-access denial message if path dealerId mismatches authenticated dealer.
- Suggested use: support/debug parity checks only; not primary production path.
- Role gate: `ROLE_DEALER` + backend self-access verification.

## Senior Developer Verification Notes (What To Enforce Before FE Freeze)

1. Canonicalize dealer portal client to `/api/v1/dealer-portal/*` and treat `/api/v1/dealers/{dealerId}/*` as fallback/debug only.
2. Keep promotions view read-only for dealer users and block any mutation intents in UI state/actions.
3. Validate blob/PDF rendering behavior across web/mobile browsers for `/dealer-portal/invoices/{invoiceId}/pdf`.
4. Decide final behavior of universal `Switch Company` button for dealers (hidden vs disabled) based on backend company-list policy.
5. Add E2E tests for dealer self-scope guarantees: accessing another dealerId alias path must fail.

## Delta Update (2026-02-13): Dealer Credit Request Approval Flow (Explicit)

Dealer credit request creation:
- `POST /api/v1/dealer-portal/credit-requests`
- Initial lifecycle status is `PENDING` (frontend should render this deterministically after create).

Admin-queue handoff contract:
- Dealer-created requests are consumed by `GET /api/v1/admin/approvals`.
- Queue rows are rendered as explicit action items with summary/action metadata.

Expected queue semantics for dealer-origin requests:
- `type=CREDIT_REQUEST`
- `actionType=APPROVE_DEALER_CREDIT_REQUEST`
- `sourcePortal=DEALER_PORTAL`
- `approveEndpoint=/api/v1/sales/credit-requests/{id}/approve`
- `rejectEndpoint=/api/v1/sales/credit-requests/{id}/reject`

Frontend note:
- Dealer portal itself does not approve/reject; it only submits.
- If you add status tracking UI later, poll dealer-facing request state from dealer-compatible read APIs and map to admin decisions (`APPROVED`/`REJECTED`) without exposing admin-only endpoints in dealer client.

## Delta Update (2026-02-15): Pending Exposure Semantics + Flyway Baseline (V2)

- Async-loop verification baseline in this slice uses Flyway `V2` (`db/migration_v2`, `flyway_schema_history_v2`).
- Dealer pending exposure fields are contract fields, not UI-derived guesses:
  - dashboard/aging/orders surfaces expose `pendingOrderExposure`, `pendingOrderCount`, and per-order `pendingCreditExposure`.
  - `creditUsed = totalOutstanding + pendingOrderExposure`.
- Pending exposure status set is centralized and includes:
  - `BOOKED`, `RESERVED`, `PENDING_PRODUCTION`, `PENDING_INVENTORY`, `PROCESSING`, `READY_TO_SHIP`, `CONFIRMED`, `ON_HOLD`.
- Invoice-link exclusion rule:
  - if an order has any active sibling invoice (status normalized and not in `DRAFT/VOID/REVERSED`), that order is excluded from pending exposure.
  - `VOID`-only sibling invoice chains continue to count as pending exposure.
