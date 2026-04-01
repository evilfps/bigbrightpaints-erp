# Dealer Portal Frontend Engineer Handoff

This document is the backend-to-frontend contract for the **Dealer Portal**.

## Portal role

- Primary role: `ROLE_DEALER`

Role definition in backend:

- `ROLE_DEALER`
- description: `Dealer workspace user`
- default permissions:
  - `portal:dealer`

## Core rule

All dealer portal endpoints are automatically scoped to the authenticated dealer.

Frontend should **never** ask the dealer user to pick an arbitrary dealer record.

The backend already resolves the logged-in dealer identity and scopes all dealer-facing data.

---

## Frontend ownership

Frontend owns:

- page structure
- dashboard layout
- sidebar
- grouping
- labels
- empty/loading/error UX

Backend defines:

- accessible routes
- self-scoped data contract
- request/response payloads

---

## Shared auth/session behavior

Use the shared auth/self-service endpoints:

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

- refresh should be automatic in browser/app behavior
- do not expose manual refresh-token UX

---

## Global response contract

Most dealer endpoints return:

```ts
type ApiResponse<T> = {
  success: boolean
  message: string | null
  data: T | null
  timestamp: string
}
```

---

## Dealer portal scope

The dealer portal should include:

- dashboard
- ledger
- invoices
- aging / outstanding
- orders
- credit limit request submission
- invoice PDF download
- support tickets
- basic profile/self-service

It should **not** include:

- dealer management
- sales order creation
- sales promotions
- admin/accounting/factory workspaces
- arbitrary dealer selection

---

## 1. Dealer dashboard

### Call

`GET /api/v1/dealer-portal/dashboard`

### Response payload

```ts
type DealerDashboardResponse = {
  dealerId: number
  dealerName: string
  dealerCode: string
  currentBalance: number
  creditLimit: number | null
  availableCredit: number
  totalOutstanding: number
  pendingInvoices: number
  pendingOrderCount: number
  pendingOrderExposure: number
  creditUsed: number
  creditStatus: string
  agingBuckets: {
    current: number
    "1-30 days": number
    "31-60 days": number
    "61-90 days": number
    "90+ days": number
  }
}
```

Recommended dashboard cards:

- current balance
- total outstanding
- credit limit
- available credit
- pending invoices
- pending order exposure
- credit status
- aging buckets

---

## 2. Ledger

### Call

`GET /api/v1/dealer-portal/ledger`

### Response payload

```ts
type DealerLedgerResponse = {
  dealerId: number
  dealerName: string
  currentBalance: number
  entries: {
    date: string
    reference: string | null
    memo: string | null
    debit: number
    credit: number
    runningBalance: number
  }[]
}
```

Recommended FE:

- ledger table
- current balance summary

---

## 3. Invoices

### 3.1 Invoice list

#### Call

`GET /api/v1/dealer-portal/invoices`

#### Response payload

```ts
type DealerInvoicesResponse = {
  dealerId: number
  dealerName: string
  totalOutstanding: number
  invoiceCount: number
  invoices: {
    id: number
    invoiceNumber: string
    issueDate: string
    dueDate: string | null
    totalAmount: number
    outstandingAmount: number
    status: string
    currency: string | null
  }[]
}
```

### 3.2 Invoice PDF

#### Call

`GET /api/v1/dealer-portal/invoices/{invoiceId}/pdf`

#### Response

Binary PDF

Frontend behavior:

- open in browser tab or download
- no extra dealer scoping needed in FE

---

## 4. Aging / outstanding

### Call

`GET /api/v1/dealer-portal/aging`

### Response payload

```ts
type DealerAgingResponse = {
  dealerId: number
  dealerName: string
  creditLimit: number
  totalOutstanding: number
  pendingOrderCount: number
  pendingOrderExposure: number
  creditUsed: number
  availableCredit: number
  agingBuckets: {
    current: number
    "1-30 days": number
    "31-60 days": number
    "61-90 days": number
    "90+ days": number
  }
  overdueInvoices: {
    invoiceNumber: string
    issueDate: string
    dueDate: string | null
    daysOverdue: number
    outstandingAmount: number
  }[]
}
```

Recommended FE:

- aging summary cards
- overdue invoice list

---

## 5. Orders

### Call

`GET /api/v1/dealer-portal/orders`

### Response payload

```ts
type DealerOrdersResponse = {
  dealerId: number
  dealerName: string
  orderCount: number
  pendingOrderCount: number
  pendingOrderExposure: number
  orders: {
    id: number
    orderNumber: string
    status: string
    totalAmount: number
    createdAt: string
    notes: string | null
    pendingCreditExposure: boolean
  }[]
}
```

Frontend should present:

- orders list
- order status
- amount
- pending credit exposure marker

Important:

- dealer portal is read-only for orders in this controller set
- dealer cannot create/update/cancel sales orders from these routes

---

## 6. Credit limit request submission

### Call

`POST /api/v1/dealer-portal/credit-limit-requests`

### Request

```ts
type DealerPortalCreditLimitRequestCreateRequest = {
  amountRequested: number
  reason?: string | null
}
```

### Response

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
```

Frontend behavior:

- dealer should only submit for themselves
- no dealer picker
- show submission success and resulting request status

---

## 7. Support tickets

Base path:

`/api/v1/dealer-portal/support/tickets`

### DTOs

```ts
type SupportTicketCreateRequest = {
  category: string
  subject: string
  description: string
}

type SupportTicketResponse = {
  id: number
  publicId: string
  companyCode: string
  userId: number
  requesterEmail: string
  category: "BUG" | "FEATURE_REQUEST" | "SUPPORT"
  subject: string
  description: string
  status: "OPEN" | "IN_PROGRESS" | "RESOLVED" | "CLOSED"
  githubIssueNumber: number | null
  githubIssueUrl: string | null
  githubIssueState: string | null
  githubSyncedAt: string | null
  githubLastError: string | null
  resolvedAt: string | null
  resolvedNotificationSentAt: string | null
  createdAt: string
  updatedAt: string
}

type SupportTicketListResponse = {
  tickets: SupportTicketResponse[]
}
```

### Routes

- `POST /api/v1/dealer-portal/support/tickets`
- `GET /api/v1/dealer-portal/support/tickets`
- `GET /api/v1/dealer-portal/support/tickets/{ticketId}`

Recommended FE:

- My Tickets
- Ticket detail
- Create ticket form

---

## 8. Recommended dealer sidebar

- Dashboard
- Ledger
- Invoices
- Aging
- Orders
- Credit Limit Request
- Support Tickets
- Profile

---

## 9. Loading / empty / error states

Recommended empty states:

- invoices empty: `No invoices found`
- orders empty: `No orders found`
- ledger empty: `No ledger entries available`
- tickets empty: `No support tickets yet`
- overdue empty: `No overdue invoices`

Error handling:

- `401` -> redirect to login
- `403` -> access denied
- PDF endpoint should be treated as file response, not JSON

---

## 10. Concrete FE call sequence

## App init

1. `POST /api/v1/auth/login`
2. `GET /api/v1/auth/me`
3. `GET /api/v1/dealer-portal/dashboard`

## Dashboard page

1. `GET /api/v1/dealer-portal/dashboard`

## Ledger page

1. `GET /api/v1/dealer-portal/ledger`

## Invoices page

1. `GET /api/v1/dealer-portal/invoices`
2. invoice PDF action -> `GET /api/v1/dealer-portal/invoices/{invoiceId}/pdf`

## Aging page

1. `GET /api/v1/dealer-portal/aging`

## Orders page

1. `GET /api/v1/dealer-portal/orders`

## Credit limit request page

1. `POST /api/v1/dealer-portal/credit-limit-requests`

## Support page

1. `GET /api/v1/dealer-portal/support/tickets`
2. create -> `POST /api/v1/dealer-portal/support/tickets`
3. detail -> `GET /api/v1/dealer-portal/support/tickets/{ticketId}`

---

## Important boundaries

- dealer portal is self-scoped
- dealer cannot see other dealers' data
- dealer should not be shown admin/sales/accounting/factory actions
- dealer should not have arbitrary dealer lookup/search pages
