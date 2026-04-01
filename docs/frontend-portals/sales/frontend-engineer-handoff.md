# Sales Portal Frontend Engineer Handoff

This document is the backend-to-frontend contract for the **Sales Portal**.

## Portal role

- Primary role: `ROLE_SALES`
- Optional overlapping visibility in some screens:
  - `ROLE_ADMIN`
  - `ROLE_ACCOUNTING`
  - `ROLE_FACTORY`

Role definition in backend:

- `ROLE_SALES`
- description: `Sales operations and dealer management`
- default permissions:
  - `portal:sales`

## Frontend ownership

Frontend owns:

- sidebar
- grouping
- naming
- tabs
- page layout
- empty/loading/error states

Backend defines:

- routes
- request payloads
- response payloads
- access boundaries

---

## Shared auth/session behavior

Use the common auth/self-service stack:

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh-token`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`
- `GET /api/v1/auth/profile`
- `PUT /api/v1/auth/profile`
- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/password/forgot`
- `POST /api/v1/auth/password/reset`
- `POST /api/v1/auth/mfa/setup`
- `POST /api/v1/auth/mfa/activate`
- `POST /api/v1/auth/mfa/disable`

Important:

- refresh token handling should be automatic
- user should not manually manage refresh tokens
- bootstrap role/sidebar from `GET /api/v1/auth/me`

---

## Global response contract

Most endpoints return:

```ts
type ApiResponse<T> = {
  success: boolean
  message: string | null
  data: T | null
  timestamp: string
}
```

Exceptions:

- login and refresh return plain auth payloads
- some delete endpoints return `204 No Content`

---

## Sales portal scope

The sales portal should include:

- company context
- sales dashboard
- dealer management
- sales orders
- promotions
- sales target read view
- credit request creation and monitoring
- dispatch visibility for sales
- catalog visibility
- selected inventory visibility
- orchestrator order approval / trace lookup

It should **not** include:

- admin users/settings/roles
- superadmin control plane
- accounting posting workspaces
- factory dispatch confirmation workflow
- dealer client-facing portal

---

## 1. Company context

### Call

`GET /api/v1/companies`

### Response

```ts
type CompanyDto = {
  id: number
  publicId: string
  name: string
  code: string
  timezone: string
  stateCode: string | null
  defaultGstRate: number
}
```

Use for header/tenant context.

---

## 2. Sales dashboard

### Call

`GET /api/v1/sales/dashboard`

### Response

```ts
type SalesDashboardDto = {
  activeDealers: number
  totalOrders: number
  orderStatusBuckets: Record<string, number>
  pendingCreditRequests: number
}
```

Recommended FE landing cards:

- active dealers
- total orders
- order status distribution
- pending credit requests

---

## 3. Dealer management

There are two route families:

- canonical CRUD routes: `/api/v1/dealers/**`
- list/search aliases: `/api/v1/sales/dealers/**`

Recommended frontend rule:

- use `/api/v1/dealers/**` for full dealer management
- optionally use `/api/v1/sales/dealers/**` only if you want sales-prefixed list/search URLs

### DTOs

```ts
type DealerResponse = {
  id: number
  publicId: string
  code: string
  name: string
  companyName: string
  email: string
  phone: string
  address: string | null
  receivableAccountId: number | null
  receivableAccountCode: string | null
  portalEmail: string | null
  gstNumber: string | null
  stateCode: string | null
  gstRegistrationType: string
  paymentTerms: string
  region: string | null
  creditStatus: string
}

type DealerLookupResponse = {
  id: number
  publicId: string
  name: string
  code: string
  receivableAccountId: number | null
  receivableAccountCode: string | null
  stateCode: string | null
  gstRegistrationType: string
  paymentTerms: string
  region: string | null
  creditStatus: string
}

type CreateDealerRequest = {
  name: string
  companyName: string
  contactEmail: string
  contactPhone: string
  address?: string | null
  creditLimit?: number | null
  gstNumber?: string | null
  stateCode?: string | null
  gstRegistrationType?: string | null
  paymentTerms?: string | null
  region?: string | null
}
```

### Dealer list

- `GET /api/v1/dealers`
- alias: `GET /api/v1/sales/dealers`

### Dealer search

- `GET /api/v1/dealers/search?query=&status=&region=&creditStatus=`
- alias: `GET /api/v1/sales/dealers/search?...`

### Create dealer

- `POST /api/v1/dealers`

### Update dealer

- `PUT /api/v1/dealers/{dealerId}`

### Dunning hold action

- `POST /api/v1/dealers/{dealerId}/dunning/hold?overdueDays=45&minAmount=0`

#### Response

```ts
type DealerDunningHoldResponse = {
  dealerId: number
  placedOnHold: boolean
}
```

Recommended FE screens:

- Dealer Directory
- Dealer Search
- Dealer Detail/Edit

---

## 4. Sales orders

### DTOs

```ts
type SalesOrderItemDto = {
  id: number
  productCode: string
  description: string | null
  quantity: number
  unitPrice: number
  lineSubtotal: number
  gstRate: number | null
  gstAmount: number | null
  lineTotal: number
}

type SalesOrderStatusHistoryDto = {
  id: number
  fromStatus: string | null
  toStatus: string
  reasonCode: string | null
  reason: string | null
  changedBy: string | null
  changedAt: string
}

type SalesOrderDto = {
  id: number
  publicId: string
  orderNumber: string
  status: string
  totalAmount: number
  subtotalAmount: number | null
  gstTotal: number | null
  gstRate: number | null
  gstTreatment: string | null
  gstInclusive: boolean
  gstRoundingAdjustment: number | null
  currency: string | null
  dealerName: string | null
  paymentMode: string | null
  traceId: string | null
  createdAt: string
  items: SalesOrderItemDto[]
  timeline: SalesOrderStatusHistoryDto[]
}

type SalesOrderItemRequest = {
  productCode: string
  description?: string | null
  quantity: number
  unitPrice: number
  gstRate?: number | null
}

type SalesOrderRequest = {
  dealerId?: number | null
  totalAmount: number
  currency?: string | null
  notes?: string | null
  items: SalesOrderItemRequest[]
  gstTreatment?: string | null
  gstRate?: number | null
  gstInclusive?: boolean | null
  idempotencyKey?: string | null
  paymentMode?: string | null
}
```

### List orders

`GET /api/v1/sales/orders?status=&dealerId=&page=0&size=100`

### Search orders

`GET /api/v1/sales/orders/search?status=&dealerId=&orderNumber=&fromDate=&toDate=&page=0&size=50`

Important:

- `fromDate` and `toDate` must be ISO-8601 instants if sent

### Create order

`POST /api/v1/sales/orders`

Headers:

- preferred: `Idempotency-Key`
- legacy `X-Idempotency-Key` is rejected in this controller family

### Update order

`PUT /api/v1/sales/orders/{id}`

### Delete order

`DELETE /api/v1/sales/orders/{id}`

Returns `204 No Content`

### Confirm order

`POST /api/v1/sales/orders/{id}/confirm`

### Cancel order

`POST /api/v1/sales/orders/{id}/cancel`

Request:

```ts
type CancelRequest = {
  reasonCode?: string | null
  reason?: string | null
}
```

### Update status

`PATCH /api/v1/sales/orders/{id}/status`

Request:

```ts
type StatusRequest = {
  status: string
}
```

### Timeline

`GET /api/v1/sales/orders/{id}/timeline`

Recommended FE sections:

- Orders list
- Order detail
- Create/Edit order
- Order timeline

---

## 5. Promotions

### DTOs

```ts
type PromotionDto = {
  id: number
  publicId: string
  name: string
  description: string | null
  imageUrl: string | null
  discountType: string
  discountValue: number
  startDate: string
  endDate: string
  status: string | null
}

type PromotionRequest = {
  name: string
  description?: string | null
  imageUrl?: string | null
  discountType: string
  discountValue: number
  startDate: string
  endDate: string
  status?: string | null
}
```

### Routes

- `GET /api/v1/sales/promotions`
- `POST /api/v1/sales/promotions`
- `PUT /api/v1/sales/promotions/{id}`
- `DELETE /api/v1/sales/promotions/{id}`

Recommended FE:

- Promotions list
- Create/Edit promotion

---

## 6. Sales targets

### DTOs

```ts
type SalesTargetDto = {
  id: number
  publicId: string
  name: string
  periodStart: string
  periodEnd: string
  targetAmount: number
  achievedAmount: number | null
  assignee: string
}

type SalesTargetRequest = {
  name: string
  periodStart: string
  periodEnd: string
  targetAmount: number
  achievedAmount?: number | null
  assignee: string
  changeReason: string
}
```

### Routes

- `GET /api/v1/sales/targets`

### Important boundary

Sales can **list** targets, but:

- create target = admin only
- update target = admin only
- delete target = admin only

Frontend rule:

- sales portal should show targets as read-only unless logged-in role is admin

---

## 7. Credit request workflows

### Credit limit requests

#### DTOs

```ts
type CreditLimitRequestDto = {
  id: number
  publicId: string
  dealerName: string
  amountRequested: number
  status: string
  reason: string | null
  createdAt: string
}

type CreditLimitRequestCreateRequest = {
  dealerId: number
  amountRequested: number
  reason?: string | null
}
```

#### Routes

- `GET /api/v1/credit/limit-requests`
- `POST /api/v1/credit/limit-requests`

### Credit override requests

#### DTOs

```ts
type CreditLimitOverrideRequestDto = {
  id: number
  publicId: string
  dealerId: number | null
  dealerName: string | null
  packagingSlipId: number | null
  salesOrderId: number | null
  dispatchAmount: number
  currentExposure: number
  creditLimit: number
  requiredHeadroom: number
  status: string
  reason: string | null
  requestedBy: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  expiresAt: string | null
  createdAt: string
}
```

#### Routes

- `POST /api/v1/credit/override-requests`

### Important boundary

Sales can:

- create credit limit requests
- create credit override requests

Sales cannot:

- approve/reject credit limit requests
- approve/reject credit override requests

So FE should not render approval buttons in the sales portal.

---

## 8. Dispatch visibility for sales

Sales has visibility into dispatch state but not operational factory confirmation.

### DTOs

```ts
type PackagingSlipDto = {
  id: number
  publicId: string
  salesOrderId: number | null
  orderNumber: string | null
  dealerName: string | null
  slipNumber: string
  status: string
  createdAt: string
  confirmedAt: string | null
  confirmedBy: string | null
  dispatchedAt: string | null
  dispatchNotes: string | null
  journalEntryId: number | null
  cogsJournalEntryId: number | null
  lines: unknown[]
  transporterName: string | null
  driverName: string | null
  vehicleNumber: string | null
  challanReference: string | null
  deliveryChallanNumber: string | null
  deliveryChallanPdfPath: string | null
}
```

### Routes sales can use

- `GET /api/v1/dispatch/pending`
- `GET /api/v1/dispatch/slip/{slipId}`
- `GET /api/v1/dispatch/order/{orderId}`

### Important boundary

Sales cannot use:

- `GET /api/v1/dispatch/preview/{slipId}`
- `POST /api/v1/dispatch/confirm`
- `GET /api/v1/dispatch/slip/{slipId}/challan/pdf`
- `PATCH /api/v1/dispatch/slip/{slipId}/status`

Those belong to factory/admin workflows, not sales portal UX.

---

## 9. Sales-side dispatch reconciliation

### Route

`POST /api/v1/sales/dispatch/reconcile-order-markers?limit=200`

### Response

```ts
type DispatchMarkerReconciliationResponse = {
  scannedOrders: number
  reconciledOrders: number
  reconciledOrderIds: number[]
}
```

This is an accounting/ops-style maintenance surface and may be exposed only if product wants it in sales tooling.

---

## 10. Catalog / product visibility

Sales can view most catalog surfaces.

### Routes

- `GET /api/v1/catalog/brands`
- `GET /api/v1/catalog/brands/{brandId}`
- `GET /api/v1/catalog/items?q=&itemClass=&includeStock=&includeReadiness=&page=&pageSize=`
- `GET /api/v1/catalog/items/{itemId}?includeStock=true&includeReadiness=true`

### Important boundary

Sales cannot:

- import catalog
- create items
- update items

Sales can technically mutate brands because the controller-level class auth allows it and brand routes do not narrow it further.

So from backend reality:

- brand create/update/delete are currently sales-accessible

Routes:

- `POST /api/v1/catalog/brands`
- `PUT /api/v1/catalog/brands/{brandId}`
- `DELETE /api/v1/catalog/brands/{brandId}`

If FE wants to stay conservative, surface brand management only if product wants it.

---

## 11. Inventory visibility

Sales can access selected inventory read surfaces.

### Finished goods

Routes:

- `GET /api/v1/finished-goods`
- `GET /api/v1/finished-goods/{id}`
- `GET /api/v1/finished-goods/{id}/batches`
- `GET /api/v1/finished-goods/stock-summary`
- `GET /api/v1/finished-goods/low-stock`
- `GET /api/v1/finished-goods/{id}/low-stock-threshold`

Important:

- sales cannot update low-stock threshold

### Batch movement history

Route:

- `GET /api/v1/inventory/batches/{id}/movements`

### Raw material stock

Sales can hit:

- `GET /api/v1/raw-materials/stock/inventory`

This is available via broader controller authorization and may be included only if actually useful to the sales portal.

---

## 12. Orchestrator

### Approve order

`POST /api/v1/orchestrator/orders/{orderId}/approve`

Request:

```ts
type ApproveOrderRequest = {
  orderId?: string
  approvedBy?: string | null
  totalAmount?: number | null
}
```

Response:

```ts
type OrchestratorAcceptedResponse = {
  traceId: string
}
```

### Trace lookup

`GET /api/v1/orchestrator/traces/{traceId}`

Response:

```ts
type OrchestratorTraceResponse = {
  traceId: string
  events: unknown[]
}
```

---

## Recommended sales sidebar

- Dashboard
- Dealers
  - Directory
  - Search
- Orders
  - List
  - Search
  - Timeline
- Promotions
- Targets
- Credit Requests
- Dispatch Status
- Catalog
- Inventory Visibility
- Profile

---

## Loading / empty / error expectations

- orders empty: `No sales orders found`
- dealers empty: `No dealers found`
- promotions empty: `No promotions found`
- targets empty: `No targets available`
- dispatch empty: `No pending dispatch slips`

Error handling:

- `401` -> return to login
- `403` -> show access denied
- `204` -> do not JSON parse
