# Accounting Portal Frontend Engineer Handoff

This is the backend-to-frontend contract for the **Accounting Portal**.

It is the source-based handoff for what the frontend should include for `ROLE_ACCOUNTING`.

## Portal role truth

Canonical role source: `SystemRole.ROLE_ACCOUNTING`

- role: `ROLE_ACCOUNTING`
- description: `Accounting, finance, HR, and inventory operator`
- default permissions:
  - `portal:accounting`
  - `dispatch.confirm`
  - `payroll.run`

## Important frontend instruction

Backend truth:

- accounting role **can** access HR/Payroll backend surfaces

Frontend build instruction for this phase:

- **do not build HR/Payroll designs or workflows right now**
- **do include** accounting, reports, audit, suppliers, purchasing, inventory, portal finance, approvals, and related multi-module accounting work

So this handoff does two things at once:

1. preserves backend truth
2. explicitly excludes HR/Payroll frontend design/build for current scope

---

## What frontend owns

Frontend owns:

- layout
- grouping
- naming
- tabs
- page structure
- sidebar
- loading/empty/error states
- session UX polish

Backend defines:

- what routes exist
- what the accounting role can access
- payload shapes
- module gating behavior

---

## Session/auth rule

Provide only basic self-service UX:

- login
- logout
- profile
- change password
- forgot password
- reset password
- MFA setup / enable / disable

Important:

- refresh token handling should be automatic in browser/app logic
- user should not manually manage refresh tokens
- use `GET /api/v1/auth/me` as the source of truth for role/sidebar gating

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

Paginated endpoints return:

```ts
type PageResponse<T> = {
  content: T[]
  totalElements: number
  totalPages: number
  page: number
  size: number
}
```

Exceptions:

- `POST /api/v1/auth/login` returns plain `AuthResponse`
- `POST /api/v1/auth/refresh-token` returns plain `AuthResponse`
- `POST /api/v1/auth/logout` returns `204 No Content`
- `GET /api/v1/orchestrator/dashboard/finance` returns a raw `Map<String,Object>` body, not `ApiResponse`

---

## Auth / self-service contract

## Login

### `POST /api/v1/auth/login`

```ts
type LoginRequest = {
  email: string
  password: string
  companyCode: string
  mfaCode?: string | null
  recoveryCode?: string | null
}

type AuthResponse = {
  tokenType: string
  accessToken: string
  refreshToken: string
  expiresIn: number
  companyCode: string
  displayName: string
  mustChangePassword: boolean
}
```

### Frontend behavior

- save access token
- save refresh token
- call `GET /api/v1/auth/me` immediately after login
- if `mustChangePassword === true`, redirect to password-change flow

## Refresh

### `POST /api/v1/auth/refresh-token`

```ts
type RefreshTokenRequest = {
  refreshToken: string
  companyCode: string
}
```

Returns `AuthResponse`.

## Logout

### `POST /api/v1/auth/logout?refreshToken=<optional>`

Returns `204 No Content`.

## Current user bootstrap

### `GET /api/v1/auth/me`

```ts
type MeResponse = {
  email: string
  displayName: string
  companyCode: string
  mfaEnabled: boolean
  mustChangePassword: boolean
  roles: string[]
  permissions: string[]
}
```

Use for:

- role gating
- sidebar gating
- page redirect rules

## Profile

- `GET /api/v1/auth/profile`
- `PUT /api/v1/auth/profile`

```ts
type ProfileResponse = {
  email: string
  displayName: string
  preferredName: string | null
  jobTitle: string | null
  profilePictureUrl: string | null
  phoneSecondary: string | null
  secondaryEmail: string | null
  mfaEnabled: boolean
  companyCode: string
  createdAt: string
  publicId: string
}

type UpdateProfileRequest = {
  displayName?: string | null
  preferredName?: string | null
  jobTitle?: string | null
  profilePictureUrl?: string | null
  phoneSecondary?: string | null
  secondaryEmail?: string | null
}
```

## Password flows

- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/password/forgot`
- `POST /api/v1/auth/password/reset`

```ts
type ChangePasswordRequest = {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

type ForgotPasswordRequest = {
  email: string
  companyCode: string
}

type ResetPasswordRequest = {
  token: string
  newPassword: string
  confirmPassword: string
}
```

## MFA

- `POST /api/v1/auth/mfa/setup`
- `POST /api/v1/auth/mfa/activate`
- `POST /api/v1/auth/mfa/disable`

```ts
type MfaSetupResponse = {
  secret: string
  qrUri: string
  recoveryCodes: string[]
}

type MfaActivateRequest = {
  code: string
}

type MfaDisableRequest = {
  code?: string | null
  recoveryCode?: string | null
}
```

---

## Module gating truth

Accounting portal spans multiple modules. Frontend should understand the following backend gating truth:

| Module | Paths | Accounting portal note |
|---|---|---|
| `ACCOUNTING` | `/api/v1/accounting/**`, `/api/v1/invoices/**`, `/api/v1/audit/**` | Core accounting, always relevant |
| `REPORTS_ADVANCED` | `/api/v1/reports/**` | Reports pages should be hidden if disabled |
| `PURCHASING` | `/api/v1/purchasing/**`, `/api/v1/suppliers/**` | Supplier/purchasing pages should be hidden if disabled |
| `INVENTORY` | `/api/v1/inventory/**`, `/api/v1/raw-materials/**`, `/api/v1/dispatch/**`, `/api/v1/finished-goods/**` | Inventory/accounting support pages |
| `PORTAL` | `/api/v1/portal/**`, `/api/v1/dealer-portal/**` | Portal finance/support pages should be hidden if disabled |
| `HR_PAYROLL` | `/api/v1/hr/**`, `/api/v1/payroll/**`, `/api/v1/accounting/payroll/**` | Backend-accessible to accounting, but **do not build now** |

## Current frontend rule

- include accounting + reports + purchasing + inventory + approvals + portal finance
- **exclude HR/Payroll screens for this phase**

---

## Accounting portal information architecture

Recommended sidebar:

- Dashboard
- Accounting
  - COA / Accounts
  - Journals
  - Receipts & Settlements
  - GST
  - Period Close
  - Reconciliation
  - Supplier Statements / Aging
  - Costing Adjustments
- Reports
- Suppliers
- Purchasing
  - Purchase Orders
  - Goods Receipts
  - Raw Material Purchases
  - Purchase Returns
- Inventory
  - Raw Material Stock
  - Adjustments
  - Opening Stock
  - Batch Traceability
  - Finished Goods Visibility
- Approvals
- Audit Trail
- Portal Finance
- Support Tickets
- Company Context
- Profile

Do **not** include HR/Payroll in the sidebar for this phase.

---

## 1. Company context

### `GET /api/v1/companies`

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

Use for:

- tenant header
- company context display

---

## 2. Finance dashboard / supporting context

### `GET /api/v1/orchestrator/dashboard/finance`

This returns a raw JSON map, not `ApiResponse`.

Actual keys:

```ts
type FinanceDashboardResponse = {
  cashflow: {
    operating: number
    investing: number
    financing: number
    net: number
  }
  agedDebtors: AgedDebtorDto[]
  ledger: {
    accounts: unknown[]
    ledgerBalance: number
  }
  reconciliation: ReconciliationSummaryDto
}
```

This is a good accounting landing dashboard.

### `GET /api/v1/orchestrator/traces/{traceId}`

```ts
type OrchestratorTraceResponse = {
  traceId: string
  events: unknown[]
}
```

Use as a diagnostic/support tool, not primary nav.

---

## 3. Accounting core

Base path:

`/api/v1/accounting`

## 3.1 COA / accounts

### Routes

- `GET /api/v1/accounting/accounts`
- `POST /api/v1/accounting/accounts`
- `GET /api/v1/accounting/default-accounts`
- `PUT /api/v1/accounting/default-accounts`
- `GET /api/v1/accounting/accounts/tree`
- `GET /api/v1/accounting/accounts/tree/{type}`
- `GET /api/v1/accounting/accounts/{accountId}/balance/as-of?date=`
- `GET /api/v1/accounting/accounts/{accountId}/activity?startDate|from=&endDate|to=`
- `GET /api/v1/accounting/accounts/{accountId}/balance/compare?date1=&date2=`

### DTOs

```ts
type AccountRequest = {
  code: string
  name: string
  type: string
  parentId?: number | null
}

type AccountDto = {
  id: number
  publicId: string
  code: string
  name: string
  type: string
  balance: number
}

type CompanyDefaultAccountsRequest = {
  inventoryAccountId?: number | null
  cogsAccountId?: number | null
  revenueAccountId?: number | null
  discountAccountId?: number | null
  taxAccountId?: number | null
}

type CompanyDefaultAccountsResponse = {
  inventoryAccountId?: number | null
  cogsAccountId?: number | null
  revenueAccountId?: number | null
  discountAccountId?: number | null
  taxAccountId?: number | null
}

type AccountNode = {
  id: number
  code: string
  name: string
  type: string
  balance: number
  level: number
  parentId: number | null
  children: AccountNode[]
}
```

### Frontend screens

- Accounts list
- Create account
- Default accounts setup
- Account tree
- Account activity/balance views

## 3.2 Journals

### Routes

- `GET /api/v1/accounting/journal-entries`
- `GET /api/v1/accounting/journals`
- `POST /api/v1/accounting/journal-entries`
- `POST /api/v1/accounting/journals/manual`
- `POST /api/v1/accounting/journals/{entryId}/reverse`
- `POST /api/v1/accounting/journal-entries/{entryId}/reverse`
- `POST /api/v1/accounting/journal-entries/{entryId}/cascade-reverse`

### DTOs

```ts
type JournalLineRequest = {
  accountId: number
  description?: string | null
  debit: number
  credit: number
  foreignCurrencyAmount?: number | null
}

type JournalEntryRequest = {
  referenceNumber?: string | null
  entryDate: string
  memo?: string | null
  dealerId?: number | null
  supplierId?: number | null
  adminOverride?: boolean | null
  lines: JournalLineRequest[]
  currency?: string | null
  fxRate?: number | null
  sourceModule?: string | null
  sourceReference?: string | null
  journalType?: string | null
  attachmentReferences?: string[] | null
}

type JournalLineDto = {
  accountId: number
  accountCode: string
  description: string | null
  debit: number
  credit: number
}

type JournalEntryDto = {
  id: number
  publicId: string
  referenceNumber: string
  entryDate: string
  memo: string | null
  status: string
  supplierId?: number | null
  supplierName?: string | null
  accountingPeriodId?: number | null
  accountingPeriodLabel?: string | null
  accountingPeriodStatus?: string | null
  lines: JournalLineDto[]
  createdAt?: string | null
  createdBy?: string | null
}

type JournalListItemDto = {
  id: number
  referenceNumber: string
  entryDate: string
  memo: string | null
  status: string
  journalType?: string | null
  sourceModule?: string | null
  sourceReference?: string | null
  totalDebit: number
  totalCredit: number
}
```

### Frontend screens

- Journal list
- Manual journal form
- Journal detail
- Reverse / cascade reverse actions

## 3.3 Receipts / settlements

### Routes

- `POST /api/v1/accounting/receipts/dealer`
- `POST /api/v1/accounting/receipts/dealer/hybrid`
- `POST /api/v1/accounting/settlements/dealers`
- `POST /api/v1/accounting/dealers/{dealerId}/auto-settle`
- `POST /api/v1/accounting/settlements/suppliers`
- `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle`

### DTOs

```ts
type DealerReceiptRequest = {
  dealerId: number
  cashAccountId: number
  amount: number
  referenceNumber?: string | null
  memo?: string | null
  idempotencyKey?: string | null
  allocations?: unknown[] | null
}

type DealerReceiptSplitRequest = {
  dealerId: number
  incomingLines: {
    accountId: number
    amount: number
  }[]
  referenceNumber?: string | null
  memo?: string | null
  idempotencyKey?: string | null
}

type SettlementAllocationRequest = {
  invoiceId?: number | null
  purchaseId?: number | null
  appliedAmount: number
  discountAmount?: number | null
  writeOffAmount?: number | null
  fxAdjustment?: number | null
  applicationType?: string | null
  memo?: string | null
}

type SettlementPaymentRequest = {
  accountId: number
  amount: number
  method?: string | null
}

type DealerSettlementRequest = {
  dealerId: number
  cashAccountId?: number | null
  discountAccountId?: number | null
  writeOffAccountId?: number | null
  fxGainAccountId?: number | null
  fxLossAccountId?: number | null
  amount?: number | null
  unappliedAmountApplication?: string | null
  settlementDate: string
  referenceNumber?: string | null
  memo?: string | null
  idempotencyKey?: string | null
  adminOverride?: boolean | null
  allocations?: SettlementAllocationRequest[] | null
  payments?: SettlementPaymentRequest[] | null
}

type SupplierSettlementRequest = {
  supplierId: number
  cashAccountId?: number | null
  discountAccountId?: number | null
  writeOffAccountId?: number | null
  fxGainAccountId?: number | null
  fxLossAccountId?: number | null
  amount?: number | null
  unappliedAmountApplication?: string | null
  settlementDate: string
  referenceNumber?: string | null
  memo?: string | null
  idempotencyKey?: string | null
  adminOverride?: boolean | null
  allocations?: SettlementAllocationRequest[] | null
}

type AutoSettlementRequest = {
  cashAccountId?: number | null
  amount: number
  referenceNumber?: string | null
  memo?: string | null
  idempotencyKey?: string | null
}

type PartnerSettlementResponse = {
  journalEntry: JournalEntryDto
  totalApplied: number
  cashAmount: number
  totalDiscount: number
  totalWriteOff: number
  totalFxGain: number
  totalFxLoss: number
  allocations: unknown[]
}
```

### Frontend screens

- dealer receipt
- dealer settlement
- supplier settlement
- auto-settle tools

## 3.4 Notes / accruals / bad debts

### Routes

- `POST /api/v1/accounting/credit-notes`
- `POST /api/v1/accounting/debit-notes`
- `POST /api/v1/accounting/accruals`
- `POST /api/v1/accounting/bad-debts/write-off`

### Response

All return `JournalEntryDto`.

## 3.5 GST

### Routes

- `GET /api/v1/accounting/gst/return?period=YYYY-MM`
- `GET /api/v1/accounting/gst/reconciliation?period=YYYY-MM`

### DTOs

```ts
type GstReturnDto = {
  period: string
  periodStart: string
  periodEnd: string
  outputTax: number
  inputTax: number
  netPayable: number
}

type GstReconciliationDto = {
  period: string
  periodStart: string
  periodEnd: string
  collected: unknown
  inputTaxCredit: unknown
  netLiability: unknown
}
```

## 3.6 Sales returns

### Routes

- `GET /api/v1/accounting/sales/returns`
- `POST /api/v1/accounting/sales/returns/preview`
- `POST /api/v1/accounting/sales/returns`

### DTOs

- list/create response: `JournalEntryDto`
- preview response: `SalesReturnPreviewDto`

## 3.7 Periods / month-end / close

### Routes

- `GET /api/v1/accounting/periods`
- `POST /api/v1/accounting/periods`
- `PUT /api/v1/accounting/periods/{periodId}`
- `POST /api/v1/accounting/periods/{periodId}/close`
- `POST /api/v1/accounting/periods/{periodId}/request-close`
- `POST /api/v1/accounting/periods/{periodId}/approve-close`
- `POST /api/v1/accounting/periods/{periodId}/reject-close`
- `POST /api/v1/accounting/periods/{periodId}/lock`
- `POST /api/v1/accounting/periods/{periodId}/reopen`
- `GET /api/v1/accounting/month-end/checklist`
- `POST /api/v1/accounting/month-end/checklist/{periodId}`

### DTOs

```ts
type AccountingPeriodDto = {
  id: number
  year: number
  month: number
  startDate: string
  endDate: string
  label: string
  status: string
  bankReconciled: boolean
  bankReconciledAt: string | null
  bankReconciledBy: string | null
  inventoryCounted: boolean
  inventoryCountedAt: string | null
  inventoryCountedBy: string | null
  closedAt: string | null
  closedBy: string | null
  closedReason: string | null
  lockedAt: string | null
  lockedBy: string | null
  lockReason: string | null
  reopenedAt: string | null
  reopenedBy: string | null
  reopenReason: string | null
  closingJournalEntryId: number | null
  checklistNotes: string | null
  costingMethod: string | null
}

type PeriodCloseRequestActionRequest = {
  note?: string | null
  force?: boolean | null
}

type AccountingPeriodCloseRequest = {
  force?: boolean | null
  note?: string | null
}

type PeriodCloseRequestDto = {
  id: number
  publicId: string
  periodId: number
  periodLabel: string
  periodStatus: string
  status: string
  forceRequested: boolean
  requestedBy: string | null
  requestNote: string | null
  requestedAt: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  reviewNote: string | null
  approvalNote: string | null
}
```

### Important access boundaries

- `request-close` is accounting-accessible
- `approve-close` is **admin-only**
- `reject-close` is **admin-only**
- `reopen` is **superadmin-only**

Frontend rule:

- accounting portal should include period close request flow
- do not surface admin-only approve/reject actions in accounting-role mode

## 3.8 Reconciliation

### Routes

- `POST /api/v1/accounting/reconciliation/bank`
- `POST /api/v1/accounting/reconciliation/bank/sessions`
- `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items`
- `POST /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete`
- `GET /api/v1/accounting/reconciliation/bank/sessions`
- `GET /api/v1/accounting/reconciliation/bank/sessions/{sessionId}`
- `GET /api/v1/accounting/reconciliation/subledger`
- `GET /api/v1/accounting/reconciliation/discrepancies`
- `POST /api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve`
- `GET /api/v1/accounting/reconciliation/inter-company`

### DTOs

Use these as frontend contract names:

- `BankReconciliationRequest`
- `BankReconciliationSummaryDto`
- `BankReconciliationSessionCreateRequest`
- `BankReconciliationSessionItemsUpdateRequest`
- `BankReconciliationSessionCompletionRequest`
- `BankReconciliationSessionSummaryDto`
- `BankReconciliationSessionDetailDto`
- `ReconciliationDiscrepancyListResponse`
- `ReconciliationDiscrepancyDto`
- `ReconciliationDiscrepancyResolveRequest`
- `SubledgerReconciliationReport`
- `InterCompanyReconciliationReport`

Recommended FE structure:

- Bank reconciliation
- Session detail
- Discrepancy queue
- Subledger reconciliation
- Inter-company reconciliation

## 3.9 Supplier statements / aging

### Routes

- `GET /api/v1/accounting/statements/suppliers/{supplierId}`
- `GET /api/v1/accounting/aging/suppliers/{supplierId}`
- `GET /api/v1/accounting/statements/suppliers/{supplierId}/pdf`
- `GET /api/v1/accounting/aging/suppliers/{supplierId}/pdf`

### DTOs

```ts
type PartnerStatementResponse = {
  partnerId: number
  partnerName: string
  fromDate: string
  toDate: string
  openingBalance: number
  closingBalance: number
  transactions: unknown[]
}

type AgingSummaryResponse = {
  partnerId: number
  partnerName: string
  totalOutstanding: number
  buckets: {
    label: string
    fromDays: number
    toDays?: number | null
    amount: number
  }[]
}
```

### Important access boundary

- JSON statement/aging is accounting-accessible
- PDF routes are **admin-only**

## 3.10 Costing adjustments

### Routes

- `POST /api/v1/accounting/inventory/landed-cost`
- `POST /api/v1/accounting/inventory/revaluation`
- `POST /api/v1/accounting/inventory/wip-adjustment`

### Response

All return `JournalEntryDto`.

## 3.11 Diagnostics / context

### Routes

- `GET /api/v1/accounting/audit/digest`
- `GET /api/v1/accounting/audit/digest.csv`
- `GET /api/v1/accounting/audit/transactions`
- `GET /api/v1/accounting/audit/transactions/{journalEntryId}`
- `GET /api/v1/accounting/date-context`
- `GET /api/v1/accounting/configuration/health`
- `GET /api/v1/accounting/trial-balance/as-of`

### DTOs

- `AuditFeedItemDto`
- `AccountingTransactionAuditListItemDto`
- `AccountingTransactionAuditDetailDto`
- `ConfigurationHealthReport`

These belong under accounting diagnostics / tools, not as the main default landing pages.

---

## 4. Reports

Base path:

`/api/v1/reports`

These are accounting-primary pages and should be included.

### Routes

- `GET /api/v1/reports/balance-sheet`
- `GET /api/v1/reports/profit-loss`
- `GET /api/v1/reports/cash-flow`
- `GET /api/v1/reports/inventory-valuation`
- `GET /api/v1/reports/gst-return`
- `GET /api/v1/reports/inventory-reconciliation`
- `GET /api/v1/reports/balance-warnings`
- `GET /api/v1/reports/reconciliation-dashboard`
- `GET /api/v1/reports/trial-balance`
- `GET /api/v1/reports/account-statement`
- `GET /api/v1/reports/aged-debtors`
- `GET /api/v1/reports/balance-sheet/hierarchy`
- `GET /api/v1/reports/income-statement/hierarchy`
- `GET /api/v1/reports/aging/receivables`
- `GET /api/v1/reports/wastage`
- `GET /api/v1/reports/production-logs/{id}/cost-breakdown`
- `GET /api/v1/reports/monthly-production-costs`

### Export routes

- `POST /api/v1/exports/request`
- `GET /api/v1/exports/{requestId}/download`

### DTOs

Core report DTOs:

- `BalanceSheetDto`
- `ProfitLossDto`
- `TrialBalanceDto`
- `CashFlowDto`
- `InventoryValuationDto`
- `GstReturnReportDto`
- `ReconciliationSummaryDto`
- `BalanceWarningDto`
- `ReconciliationDashboardDto`
- `AccountStatementEntryDto`
- `AgedDebtorDto`
- `AgedReceivablesReport`
- `WastageReportDto`
- `CostBreakdownDto`
- `MonthlyProductionCostDto`

Export DTOs:

```ts
type ExportRequestCreateRequest = {
  reportType: string
  parameters?: string | null
}

type ExportRequestDto = {
  id: number
  userId: number
  userEmail: string | null
  reportType: string
  parameters: string | null
  status: string
  rejectionReason: string | null
  createdAt: string
  approvedBy: string | null
  approvedAt: string | null
}

type ExportRequestDownloadResponse = {
  requestId: number
  status: string
  reportType: string
  parameters: string | null
  message: string
}
```

### Frontend behavior

- reports should be a top-level accounting portal section
- export request flow belongs here
- download route should only be called after backend allows it

---

## 5. Suppliers

Base path:

`/api/v1/suppliers`

This is a primary accounting/AP workspace and should be included.

### Routes

- `GET /api/v1/suppliers`
- `GET /api/v1/suppliers/{id}`
- `POST /api/v1/suppliers`
- `PUT /api/v1/suppliers/{id}`
- `POST /api/v1/suppliers/{id}/approve`
- `POST /api/v1/suppliers/{id}/activate`
- `POST /api/v1/suppliers/{id}/suspend`

### DTOs

```ts
type SupplierRequest = {
  name: string
  code?: string | null
  contactEmail: string
  contactPhone: string
  address?: string | null
  creditLimit?: number | null
  gstNumber?: string | null
  stateCode?: string | null
  gstRegistrationType?: string | null
  paymentTerms?: string | null
  bankAccountName?: string | null
  bankAccountNumber?: string | null
  bankIfsc?: string | null
  bankBranch?: string | null
}

type SupplierResponse = {
  id: number
  publicId: string
  code: string
  name: string
  status: string
  email: string | null
  phone: string | null
  address: string | null
  creditLimit: number | null
  balance: number | null
  payableAccountId: number | null
  payableAccountCode: string | null
  gstNumber: string | null
  stateCode: string | null
  gstRegistrationType: string | null
  paymentTerms: string | null
  bankAccountName: string | null
  bankAccountNumber: string | null
  bankIfsc: string | null
  bankBranch: string | null
}
```

### Frontend screens

- Supplier list
- Supplier create/edit
- Supplier lifecycle / approval actions

---

## 6. Purchasing

Base paths:

- `/api/v1/purchasing`
- `/api/v1/purchasing/raw-material-purchases`

This should be included in the accounting portal.

## 6.1 Purchase orders / goods receipts

### Routes

- `GET /api/v1/purchasing/purchase-orders`
- `GET /api/v1/purchasing/purchase-orders/{id}`
- `POST /api/v1/purchasing/purchase-orders`
- `POST /api/v1/purchasing/purchase-orders/{id}/approve`
- `POST /api/v1/purchasing/purchase-orders/{id}/void`
- `POST /api/v1/purchasing/purchase-orders/{id}/close`
- `GET /api/v1/purchasing/purchase-orders/{id}/timeline`
- `GET /api/v1/purchasing/goods-receipts`
- `GET /api/v1/purchasing/goods-receipts/{id}`
- `POST /api/v1/purchasing/goods-receipts`

### DTOs

```ts
type PurchaseOrderLineRequest = {
  rawMaterialId: number
  quantity: number
  unit: string
  costPerUnit: number
  notes?: string | null
}

type PurchaseOrderRequest = {
  supplierId: number
  orderNumber?: string | null
  orderDate: string
  memo?: string | null
  lines: PurchaseOrderLineRequest[]
}

type PurchaseOrderResponse = {
  id: number
  publicId: string
  orderNumber: string
  orderDate: string
  totalAmount: number
  status: string
  memo: string | null
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  createdAt: string
  lines: unknown[]
}

type PurchaseOrderVoidRequest = {
  reasonCode?: string | null
  reason?: string | null
}

type PurchaseOrderStatusHistoryResponse = {
  id: number
  fromStatus: string | null
  toStatus: string
  reasonCode: string | null
  reason: string | null
  changedBy: string | null
  changedAt: string
}

type GoodsReceiptLineRequest = {
  rawMaterialId: number
  batchCode?: string | null
  quantity: number
  unit: string
  costPerUnit: number
  manufacturingDate?: string | null
  expiryDate?: string | null
  notes?: string | null
}

type GoodsReceiptRequest = {
  purchaseOrderId: number
  receiptNumber?: string | null
  receiptDate: string
  memo?: string | null
  idempotencyKey?: string | null
  lines: GoodsReceiptLineRequest[]
}

type GoodsReceiptResponse = {
  id: number
  publicId: string
  receiptNumber: string
  receiptDate: string
  totalAmount: number
  status: string
  memo: string | null
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  purchaseOrderId?: number | null
  purchaseOrderNumber?: string | null
  createdAt: string
  lines: unknown[]
  lifecycle?: unknown
  linkedReferences?: unknown[]
}
```

## 6.2 Raw material purchases / purchase returns

### Routes

- `GET /api/v1/purchasing/raw-material-purchases`
- `GET /api/v1/purchasing/raw-material-purchases/{id}`
- `POST /api/v1/purchasing/raw-material-purchases`
- `POST /api/v1/purchasing/raw-material-purchases/returns`
- `POST /api/v1/purchasing/raw-material-purchases/returns/preview`

### DTOs

```ts
type RawMaterialPurchaseLineRequest = {
  rawMaterialId: number
  batchCode?: string | null
  quantity: number
  unit: string
  costPerUnit: number
  taxRate?: number | null
  taxInclusive?: boolean | null
  notes?: string | null
}

type RawMaterialPurchaseRequest = {
  supplierId: number
  invoiceNumber: string
  invoiceDate: string
  memo?: string | null
  purchaseOrderId?: number | null
  goodsReceiptId?: number | null
  taxAmount?: number | null
  lines: RawMaterialPurchaseLineRequest[]
}

type RawMaterialPurchaseResponse = {
  id: number
  publicId: string
  invoiceNumber: string
  invoiceDate: string
  totalAmount: number
  taxAmount: number | null
  outstandingAmount: number | null
  status: string
  memo: string | null
  supplierId: number
  supplierCode: string | null
  supplierName: string | null
  purchaseOrderId?: number | null
  purchaseOrderNumber?: string | null
  goodsReceiptId?: number | null
  goodsReceiptNumber?: string | null
  journalEntryId?: number | null
  createdAt: string
  lines: unknown[]
  lifecycle?: unknown
  linkedReferences?: unknown[]
}

type PurchaseReturnRequest = {
  supplierId: number
  purchaseId: number
  rawMaterialId: number
  quantity: number
  unitCost: number
  referenceNumber?: string | null
  returnDate: string
  reason?: string | null
}

type PurchaseReturnPreviewDto = {
  purchaseId: number
  purchaseInvoiceNumber: string
  rawMaterialId: number
  rawMaterialName: string
  requestedQuantity: number
  remainingReturnableQuantity: number
  lineAmount: number
  taxAmount: number
  totalAmount: number
  returnDate: string
  referenceNumber: string | null
}
```

### Frontend screens

- Purchase orders
- Purchase order detail / timeline
- Goods receipts
- Raw material purchases
- Purchase return preview + submit

---

## 7. Inventory

This is a real accounting portal section because accounting has inventory valuation, adjustments, opening stock, and stock visibility responsibilities.

## 7.1 Raw material stock

### Routes

- `GET /api/v1/raw-materials/stock`
- `GET /api/v1/raw-materials/stock/inventory`
- `GET /api/v1/raw-materials/stock/low-stock`
- `GET /api/v1/inventory/batches/expiring-soon`

### DTOs

```ts
type StockSummaryDto = {
  id?: number | null
  publicId?: string | null
  code?: string | null
  name?: string | null
  currentStock?: number | null
  reservedStock?: number | null
  availableStock?: number | null
  weightedAverageCost?: number | null
  totalMaterials?: number | null
  lowStockMaterials?: number | null
  criticalStockMaterials?: number | null
  totalBatches?: number | null
}

type InventoryStockSnapshot = {
  name: string
  sku: string
  currentStock: number
  reorderLevel: number
  status: string
}

type InventoryExpiringBatchDto = {
  batchType: string
  batchId: number
  publicId: string
  itemCode: string
  itemName: string
  batchCode: string
  quantityAvailable: number
  unitCost: number
  manufacturedAt?: string | null
  expiryDate?: string | null
  daysUntilExpiry: number
}
```

## 7.2 Inventory adjustments

### Routes

- `GET /api/v1/inventory/adjustments`
- `POST /api/v1/inventory/adjustments`
- `POST /api/v1/inventory/raw-materials/adjustments`

### DTOs

```ts
type InventoryAdjustmentLineRequest = {
  finishedGoodId: number
  quantity: number
  unitCost: number
  note?: string | null
}

type InventoryAdjustmentRequest = {
  adjustmentDate: string
  type: string
  adjustmentAccountId: number
  reason?: string | null
  adminOverride?: boolean | null
  idempotencyKey?: string | null
  lines: InventoryAdjustmentLineRequest[]
}

type InventoryAdjustmentDto = {
  id: number
  publicId: string
  referenceNumber: string
  adjustmentDate: string
  type: string
  status: string
  reason: string | null
  totalAmount: number
  journalEntryId?: number | null
  lines: unknown[]
}

type RawMaterialAdjustmentLineRequest = {
  rawMaterialId: number
  quantity: number
  unitCost: number
  note?: string | null
}

type RawMaterialAdjustmentRequest = {
  adjustmentDate: string
  direction: string
  adjustmentAccountId: number
  reason?: string | null
  adminOverride?: boolean | null
  idempotencyKey?: string | null
  lines: RawMaterialAdjustmentLineRequest[]
}

type RawMaterialAdjustmentDto = {
  id: number
  publicId: string
  referenceNumber: string
  adjustmentDate: string
  status: string
  reason: string | null
  totalAmount: number
  journalEntryId?: number | null
  lines: unknown[]
}
```

## 7.3 Opening stock

### Routes

- `POST /api/v1/inventory/opening-stock`
- `GET /api/v1/inventory/opening-stock`

### Request

Multipart form:

- `file`
- `openingStockBatchKey`
- header `Idempotency-Key`

### DTOs

```ts
type OpeningStockImportResponse = {
  openingStockBatchKey: string
  rowsProcessed: number
  rawMaterialBatchesCreated: number
  finishedGoodBatchesCreated: number
  results: unknown[]
  errors: unknown[]
}

type OpeningStockImportHistoryItem = {
  id: number
  idempotencyKey: string
  openingStockBatchKey: string
  referenceNumber: string
  fileName: string | null
  journalEntryId?: number | null
  rowsProcessed: number
  createdAt: string
}
```

## 7.4 Batch traceability

### Route

- `GET /api/v1/inventory/batches/{id}/movements`

### DTO

```ts
type InventoryBatchTraceabilityDto = {
  batchId: number
  publicId: string
  batchType: string
  itemCode: string
  itemName: string
  batchNumber: string
  manufacturingDate?: string | null
  expiryDate?: string | null
  quantityTotal: number
  quantityAvailable: number
  unitCost: number
  source?: string | null
  movements: unknown[]
}
```

## 7.5 Finished goods visibility

### Routes

- `GET /api/v1/finished-goods`
- `GET /api/v1/finished-goods/{id}`
- `GET /api/v1/finished-goods/stock-summary`
- `GET /api/v1/finished-goods/{id}/low-stock-threshold`
- `PUT /api/v1/finished-goods/{id}/low-stock-threshold`

### DTOs

```ts
type FinishedGoodDto = {
  id: number
  publicId: string
  productCode: string
  name: string
  unit: string | null
  currentStock?: number | null
  reservedStock?: number | null
  costingMethod?: string | null
  valuationAccountId?: number | null
  cogsAccountId?: number | null
  revenueAccountId?: number | null
  discountAccountId?: number | null
  taxAccountId?: number | null
}

type FinishedGoodLowStockThresholdDto = {
  finishedGoodId: number
  productCode: string
  threshold: number
}

type FinishedGoodLowStockThresholdRequest = {
  threshold: number
}
```

### Important boundary

Accounting can view finished goods + threshold configuration, but these are still shared inventory surfaces, not factory execution.

---

## 8. Approvals

Accounting portal should include an approvals area because backend exposes shared approval surfaces to accounting.

### Shared inbox

#### `GET /api/v1/admin/approvals`

```ts
type AdminApprovalsResponse = {
  creditRequests: AdminApprovalItemDto[]
  payrollRuns: AdminApprovalItemDto[]
  periodCloseRequests: AdminApprovalItemDto[]
  exportRequests: AdminApprovalItemDto[]
}

type AdminApprovalItemDto = {
  originType:
    | "CREDIT_REQUEST"
    | "CREDIT_LIMIT_OVERRIDE_REQUEST"
    | "PAYROLL_RUN"
    | "PERIOD_CLOSE_REQUEST"
    | "EXPORT_REQUEST"
  ownerType:
    | "SALES"
    | "FACTORY"
    | "HR"
    | "ACCOUNTING"
    | "REPORTS"
  id: number | null
  publicId: string | null
  reference: string
  status: string
  summary: string
  reportType?: string | null
  parameters?: string | null
  requesterUserId?: number | null
  requesterEmail?: string | null
  actionType?: string | null
  actionLabel?: string | null
  approveEndpoint?: string | null
  rejectEndpoint?: string | null
  createdAt: string | null
}
```

### Important accounting boundary

Accounting can **read** the approvals feed, but:

- export approvals are redacted/non-actionable for accounting
- requester identity can be redacted for accounting view

### Credit request actions accounting can use

- `POST /api/v1/credit/limit-requests/{id}/approve`
- `POST /api/v1/credit/limit-requests/{id}/reject`
- `GET /api/v1/credit/override-requests`
- `POST /api/v1/credit/override-requests/{id}/approve`
- `POST /api/v1/credit/override-requests/{id}/reject`

```ts
type CreditLimitRequestDecisionRequest = {
  reason: string
}

type CreditLimitOverrideDecisionRequest = {
  reason?: string | null
  expiresAt?: string | null
}
```

### Credit DTOs

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

### HR/Payroll note

`payrollRuns[]` exists structurally in the approvals DTO because the backend role can access it.

But for this frontend phase:

- **do not build HR/Payroll approval designs**
- if payroll rows appear from backend, treat them as out-of-scope for this handoff

---

## 9. Portal finance

These are shared accounting/admin finance helper views and should be included.

### Routes

- `GET /api/v1/portal/finance/ledger?dealerId=...`
- `GET /api/v1/portal/finance/invoices?dealerId=...`
- `GET /api/v1/portal/finance/aging?dealerId=...`

### Actual response keys from service

```ts
type PortalDealerLedgerResponse = {
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

type PortalDealerInvoicesResponse = {
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

type PortalDealerAgingResponse = {
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

---

## 10. Portal support tickets

Accounting can access support tickets via portal routes.

### Routes

- `POST /api/v1/portal/support/tickets`
- `GET /api/v1/portal/support/tickets`
- `GET /api/v1/portal/support/tickets/{ticketId}`

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

This is a secondary/support section, not a main accounting landing page.

---

## 11. Audit trail / compliance

Accounting portal should include a real audit/compliance section.

### Routes

- `GET /api/v1/audit/business-events`
- `GET /api/v1/accounting/audit/events`
- `GET /api/v1/accounting/audit/transactions`
- `GET /api/v1/accounting/audit/transactions/{journalEntryId}`

### Query params

`/api/v1/audit/business-events`

- `from`
- `to`
- `module`
- `action`
- `status`
- `actorUserId`
- `referenceNumber`
- `page`
- `size`

### DTOs

```ts
type BusinessAuditEventResponse = {
  id: number
  occurredAt: string
  source: string
  module: string
  action: string
  entityType: string
  entityId: string
  referenceNumber: string | null
  status: string
  failureReason: string | null
  amount: number | null
  currency: string | null
  correlationId: string | null
  requestId: string | null
  traceId: string | null
  actorUserId: number | null
  actorIdentifier: string | null
  metadata: Record<string, string>
}
```

Additional accounting audit DTOs:

- `AuditFeedItemDto`
- `AccountingTransactionAuditListItemDto`
- `AccountingTransactionAuditDetailDto`

Recommended FE structure:

- Business Events
- Accounting Audit Feed
- Transaction Audit Detail

---

## 12. Catalog / brands / items

Accounting can access catalog surfaces across modules.

These should be included if product wants accounting to manage accounting-sensitive product master data.

### Routes

- `GET /api/v1/catalog/brands`
- `GET /api/v1/catalog/brands/{brandId}`
- `POST /api/v1/catalog/brands`
- `PUT /api/v1/catalog/brands/{brandId}`
- `DELETE /api/v1/catalog/brands/{brandId}`
- `GET /api/v1/catalog/items`
- `GET /api/v1/catalog/items/{itemId}`
- `POST /api/v1/catalog/items`
- `PUT /api/v1/catalog/items/{itemId}`
- `POST /api/v1/catalog/import`

### DTOs

- `CatalogBrandDto`
- `CatalogBrandRequest`
- `CatalogItemDto`
- `CatalogItemRequest`
- `CatalogImportResponse`

### Frontend guidance

- include only if accounting portal is expected to own product master data
- otherwise keep as secondary section, not primary landing

---

## 13. Explicit exclusions for this phase

Even though backend truth includes them, do **not** design/build these now:

## HR/Payroll

Do not build UI/workflows for:

- `/api/v1/hr/**`
- `/api/v1/payroll/**`
- `/api/v1/accounting/payroll/**`

This includes:

- payroll runs
- employee management
- attendance
- leave
- salary structures
- payroll posting/batch payment screens

## Admin-only / superadmin-only surfaces

Do not include as accounting portal sections:

- admin users
- admin settings
- admin roles
- admin notify
- superadmin tenant control plane

## Factory execution / dispatch execution

Do not include:

- factory dispatch confirmation workflow
- dispatch preview
- challan generation / dispatch status mutation
- factory packing / production execution

---

## Recommended page-by-page FE call sequence

## App bootstrap

1. `POST /api/v1/auth/login`
2. `GET /api/v1/auth/me`
3. `GET /api/v1/companies`
4. `GET /api/v1/orchestrator/dashboard/finance`

## Accounting > COA

1. `GET /api/v1/accounting/accounts`
2. `GET /api/v1/accounting/accounts/tree`
3. `GET /api/v1/accounting/default-accounts`

Mutations:

- `POST /api/v1/accounting/accounts`
- `PUT /api/v1/accounting/default-accounts`

## Accounting > Journals

1. `GET /api/v1/accounting/journals`
2. `GET /api/v1/accounting/journal-entries`

Mutations:

- `POST /api/v1/accounting/journals/manual`
- `POST /api/v1/accounting/journal-entries`
- reverse routes

## Accounting > Receipts & Settlements

- dealer receipts routes
- dealer settlements routes
- supplier settlements routes
- auto-settle routes

## Accounting > GST

- `GET /api/v1/accounting/gst/return`
- `GET /api/v1/accounting/gst/reconciliation`

## Accounting > Period Close

1. `GET /api/v1/accounting/periods`
2. `GET /api/v1/accounting/month-end/checklist`

Mutations:

- period create/update
- request-close
- checklist update

Do not show:

- approve-close / reject-close for accounting-only mode
- reopen unless explicit superadmin mode exists

## Reports

Call whichever report page the user opens under `/api/v1/reports/**`.

## Suppliers

1. `GET /api/v1/suppliers`
2. detail -> `GET /api/v1/suppliers/{id}`

Mutations:

- `POST /api/v1/suppliers`
- `PUT /api/v1/suppliers/{id}`
- approve/activate/suspend

## Purchasing

- purchase orders list/detail/timeline
- goods receipts list/detail
- raw material purchases list/detail
- returns preview + submit

## Inventory

- raw material stock
- inventory adjustments
- opening stock history/import
- batch movement history
- finished goods visibility

## Approvals

1. `GET /api/v1/admin/approvals`
2. call credit approval/rejection routes where applicable
3. refetch approvals

## Audit

1. `GET /api/v1/audit/business-events?...`
2. `GET /api/v1/accounting/audit/events?...`
3. `GET /api/v1/accounting/audit/transactions?...`
4. `GET /api/v1/accounting/audit/transactions/{journalEntryId}`

## Portal Finance

- ledger
- invoices
- aging

## Support

- `GET /api/v1/portal/support/tickets`
- create -> `POST /api/v1/portal/support/tickets`
- detail -> `GET /api/v1/portal/support/tickets/{ticketId}`

---

## Loading / empty / error states

Recommended empty states:

- accounts: `No accounts found`
- journals: `No journal entries found`
- suppliers: `No suppliers found`
- purchase orders: `No purchase orders found`
- goods receipts: `No goods receipts found`
- raw material purchases: `No purchase records found`
- reports: `No report data available`
- approvals: `No pending approvals`
- support tickets: `No support tickets yet`

Error handling:

- `401` -> redirect to login
- `403` -> access denied
- `204` -> do not JSON parse

For module-disabled routes:

- backend may return `403 MODULE_DISABLED`
- FE should hide module sections where known, but backend response remains final source of truth
