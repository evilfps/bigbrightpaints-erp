# Accounting Portal Frontend Engineer Handoff (Deep)

Source: `openapi.json` parsed via `map_openapi_frontend.py`.

This handoff mirrors the Admin deliverable pattern with 3 tasks:
1. Accounting endpoint expectations (deep)
2. API inventory grouped by domain with cache/debounce/idempotency and inconsistencies
3. Enterprise accounting route map with required APIs, states, schema-driven tables/forms, and permission gates

## Portal Scope Invariant (Do Not Remove)

HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.

Guardrail reference: `docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md`

## Assumptions (Explicit)

1. Permission claims are available via `GET /api/v1/auth/me` (`data.permissions`).
2. Permission code taxonomy is not fully documented in OpenAPI, so gate names below are proposed and must be aligned with backend RBAC.
3. Error schema is not standardized in many endpoints; frontend must implement resilient generic error handling with fallback parsing.
4. Accountant-critical controls (period lock, journal reversals, payroll posting, inventory valuation) are treated as high-risk actions with confirmation dialogs + audit-friendly UX.
5. Enterprise readiness requires server-side enforcement of period lock and posting immutability; frontend only reflects/guards UX.
6. Scoped endpoint counts in Task 1 track a curated accounting-portal parity baseline; a few cross-domain support endpoints (for dealer lookup) are listed in Task 2/3 for practical frontend dependencies.

## Verified Backend RBAC Baseline (Exact, Code-Verified)

- Method security is active via `@EnableMethodSecurity` in `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java`.
- Authorities include both role names and permission codes (from role permissions) via `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/UserPrincipal.java`.
- Default system role permissions are defined in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/domain/SystemRole.java`.
- `ROLE_ACCOUNTING` default permission codes: `portal:accounting`, `payroll.run`.

Verified role behavior for accounting portal scope:
- Most accounting, reports, purchasing, HR/payroll, and inventory-adjustment endpoints require `ROLE_ADMIN` or `ROLE_ACCOUNTING`.
- Exceptions you must respect in UI:
  - Supplier `GET` endpoints allow `ROLE_FACTORY` too.
  - Raw-material endpoints allow `ROLE_FACTORY` too, but `POST /api/v1/raw-materials/intake` excludes `ROLE_FACTORY`.
  - Opening stock import `POST /api/v1/inventory/opening-stock` allows `ROLE_FACTORY` along with `ROLE_ADMIN|ROLE_ACCOUNTING`.
  - Finished-goods write endpoints (`POST/PUT /api/v1/finished-goods`, `POST /api/v1/finished-goods/{id}/batches`) exclude `ROLE_ACCOUNTING` and require `ROLE_ADMIN` or `ROLE_FACTORY`.
  - Finished-goods low-stock and batch-list reads exclude `ROLE_ACCOUNTING` (`ROLE_ADMIN|ROLE_FACTORY|ROLE_SALES` only).
  - `GET /api/v1/accounting/sales/returns` allows `ROLE_SALES` in addition to accounting/admin.

## Shared Foundation APIs (Used Across All Accounting Routes)

These are cross-portal APIs reused in Accounting Portal for auth/session/profile/company context.

| Function | Method | Path | Purpose |
|---|---|---|---|
| `authGetMe` | GET | `/api/v1/auth/me` | Session + permission claims for route gating |
| `profileGet` | GET | `/api/v1/auth/profile` | Load profile drawer/page |
| `profileUpdate` | PUT | `/api/v1/auth/profile` | Save profile updates |
| `authChangePassword` | POST | `/api/v1/auth/password/change` | Password update |
| `companiesList` | GET | `/api/v1/companies` | Company switch list |
| `companiesSwitch` | POST | `/api/v1/multi-company/companies/switch` | Switch accounting company context |
| `authLogout` | POST | `/api/v1/auth/logout` | Sign out |

## Task 1: Endpoint Expectations

- Full endpoint expectation map: `docs/accounting-portal-endpoint-map.md`
- Scoped endpoint count: **143**
- Accounting Core (GL, Periods, Journals, Controls): **58** endpoints
- Invoice & Receivables: **5** endpoints
- Purchasing & Payables: **14** endpoints
- Inventory & Costing: **21** endpoints
- HR & Payroll: **32** endpoints
- Reports & Reconciliation: **13** endpoints

### M18-S9A Parity Closure (Do Not Drift)

- M17-S1 canonical API contract source-of-truth is `openapi.json`; parity checks are non-mutating and fail on drift instead of rewriting docs.
- M17-S2 handoff parity expectation is the curated **143**-row baseline from `docs/accounting-portal-endpoint-map.md`, with only the documented `+9` dependency rows and `+4` code-verified period-close workflow supplement rows allowed beyond that set.
- Endpoint-map parity lock in this handoff is the same curated **143** baseline (it does not claim full accounting-portal OpenAPI coverage).
- Current handoff inventory total is **156** unique `METHOD /api/v1/...` rows = `143` portal-owned parity rows + `9` intentional dependencies + `4` code-verified period-close workflow supplement rows.
- Intentional dependency-only rows (`+9` vs endpoint map):
  - Shared foundation APIs (7): `GET /api/v1/auth/me`, `GET /api/v1/auth/profile`, `PUT /api/v1/auth/profile`, `POST /api/v1/auth/password/change`, `GET /api/v1/companies`, `POST /api/v1/multi-company/companies/switch`, `POST /api/v1/auth/logout`
  - Dealer support APIs (2): `GET /api/v1/sales/dealers`, `GET /api/v1/sales/dealers/search`
- Code-verified period-close workflow supplement rows (`+4` vs endpoint map parity lock):
  - `GET /api/v1/admin/approvals`
  - `POST /api/v1/accounting/periods/{periodId}/request-close`
  - `POST /api/v1/accounting/periods/{periodId}/approve-close`
  - `POST /api/v1/accounting/periods/{periodId}/reject-close`
- Explicit outside-lock ledger (present in `docs/endpoint-inventory.md` and `openapi.json`):
  - `GET /api/v1/accounting/audit/transactions`
  - `GET /api/v1/accounting/audit/transactions/{journalEntryId}`
  - `GET /api/v1/accounting/date-context`
- Legacy digest endpoints (`GET /api/v1/accounting/audit/digest*`) remain in snapshot as admin-only deprecated exports and must not be treated as required APIs for new accountant-owned UI flows.

## Task 2: Frontend API Inventory (Grouped by Domain)

### Accounting Core (GL, Periods, Journals, Controls)

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `acctCatalogImportCatalog` | POST | `/api/v1/accounting/catalog/import` | file (multipart body; if file-part `Content-Type` is absent, filename must end with `.csv`) | Idempotency-Key (header) | No | No | No |
| `acctCatalogListProducts` | GET | `/api/v1/accounting/catalog/products` | - | - | Yes | No | Yes |
| `acctCatalogCreateProduct` | POST | `/api/v1/accounting/catalog/products` | category (body), productName (body) | basePrice (body), brandCode (body), brandId (body), brandName (body), customSkuCode (body), defaultColour (body), gstRate (body), metadata (body), minDiscountPercent (body), minSellingPrice (body), sizeLabel (body), unitOfMeasure (body) | No | No | No |
| `acctCatalogCreateVariants` | POST | `/api/v1/accounting/catalog/products/bulk-variants` | baseProductName (body), category (body), colors (body), sizes (body) | basePrice (body), brandCode (body), brandId (body), brandName (body), gstRate (body), metadata (body), minDiscountPercent (body), minSellingPrice (body), skuPrefix (body), unitOfMeasure (body) | No | No | No |
| `acctCatalogUpdateProduct` | PUT | `/api/v1/accounting/catalog/products/{id}` | id (path) | basePrice (body), category (body), defaultColour (body), gstRate (body), metadata (body), minDiscountPercent (body), minSellingPrice (body), productName (body), sizeLabel (body), unitOfMeasure (body) | No | No | Yes |
| `acctConfigHealth` | GET | `/api/v1/accounting/configuration/health` | - | - | Yes | No | Yes |
| `acctAccounts` | GET | `/api/v1/accounting/accounts` | - | - | Yes | No | Yes |
| `acctCreateAccount` | POST | `/api/v1/accounting/accounts` | code (body), name (body), type (body) | parentId (body) | No | No | No |
| `acctGetChartOfAccountsTree` | GET | `/api/v1/accounting/accounts/tree` | - | - | Yes | No | Yes |
| `acctGetAccountTreeByType` | GET | `/api/v1/accounting/accounts/tree/{type}` | type (path) | - | Yes | No | Yes |
| `acctGetAccountActivity` | GET | `/api/v1/accounting/accounts/{accountId}/activity` | accountId (path), endDate (query), startDate (query) | - | Yes | No | Yes |
| `acctGetBalanceAsOf` | GET | `/api/v1/accounting/accounts/{accountId}/balance/as-of` | accountId (path), date (query) | - | Yes | No | Yes |
| `acctCompareBalances` | GET | `/api/v1/accounting/accounts/{accountId}/balance/compare` | accountId (path), date1 (query), date2 (query) | - | Yes | No | Yes |
| `acctPostAccrual` | POST | `/api/v1/accounting/accruals` | amount (body), creditAccountId (body), debitAccountId (body) | adminOverride (body), autoReverseDate (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctDealerAging` | GET | `/api/v1/accounting/aging/dealers/{dealerId}` | dealerId (path) | asOf (query), buckets (query) | Yes | No | Yes |
| `acctDealerAgingPdf` | GET | `/api/v1/accounting/aging/dealers/{dealerId}/pdf` | dealerId (path) | asOf (query), buckets (query) | Yes | No | Yes |
| `acctSupplierAging` | GET | `/api/v1/accounting/aging/suppliers/{supplierId}` | supplierId (path) | asOf (query), buckets (query) | Yes | No | Yes |
| `acctSupplierAgingPdf` | GET | `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | supplierId (path) | asOf (query), buckets (query) | Yes | No | Yes |
| `acctAuditDigest` | GET | `/api/v1/accounting/audit/digest` | - | from (query), to (query) | Yes | No | Yes |
| `acctAuditDigestCsv` | GET | `/api/v1/accounting/audit/digest.csv` | - | from (query), to (query) | Yes | No | Yes |
| `acctWriteOffBadDebt` | POST | `/api/v1/accounting/bad-debts/write-off` | amount (body), expenseAccountId (body), invoiceId (body) | adminOverride (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctPostCreditNote` | POST | `/api/v1/accounting/credit-notes` | invoiceId (body) | adminOverride (body), amount (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctPostDebitNote` | POST | `/api/v1/accounting/debit-notes` | purchaseId (body) | adminOverride (body), amount (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctDefaultAccounts` | GET | `/api/v1/accounting/default-accounts` | - | - | Yes | No | Yes |
| `acctUpdateDefaultAccounts` | PUT | `/api/v1/accounting/default-accounts` | - | cogsAccountId (body), discountAccountId (body), inventoryAccountId (body), revenueAccountId (body), taxAccountId (body) | No | No | Yes |
| `acctGenerateGstReturn` | GET | `/api/v1/accounting/gst/return` | - | period (query) | Yes | No | Yes |
| `acctRecordLandedCost` | POST | `/api/v1/accounting/inventory/landed-cost` | amount (body), inventoryAccountId (body), offsetAccountId (body), rawMaterialPurchaseId (body) | adminOverride (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctRevalueInventory` | POST | `/api/v1/accounting/inventory/revaluation` | deltaAmount (body), inventoryAccountId (body), revaluationAccountId (body) | adminOverride (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctAdjustWip` | POST | `/api/v1/accounting/inventory/wip-adjustment` | amount (body), direction (body), inventoryAccountId (body), productionLogId (body), wipAccountId (body) | adminOverride (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctJournalEntries` | GET | `/api/v1/accounting/journal-entries` | - | dealerId (query), page (query), size (query), supplierId (query) | Yes | No | Yes |
| `acctCreateJournalEntry` | POST | `/api/v1/accounting/journal-entries` | entryDate (body), lines (body), lines[].accountId (body), lines[].credit (body), lines[].debit (body) | adminOverride (body), currency (body), dealerId (body), fxRate (body), lines[].description (body), lines[].foreignCurrencyAmount (body), memo (body), referenceNumber (body), supplierId (body) | No | No | No |
| `acctCascadeReverseJournalEntry` | POST | `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse` | entryId (path) | adminOverride (body), approvedBy (body), cascadeRelatedEntries (body), effectivePercentage (body), memo (body), partialReversal (body), reason (body), reasonCode (body), relatedEntryIds (body), reversalDate (body), reversalPercentage (body), supportingDocumentRef (body), voidOnly (body) | No | No | No |
| `acctReverseJournalEntry` | POST | `/api/v1/accounting/journal-entries/{entryId}/reverse` | entryId (path) | adminOverride (body), approvedBy (body), cascadeRelatedEntries (body), effectivePercentage (body), memo (body), partialReversal (body), reason (body), reasonCode (body), relatedEntryIds (body), reversalDate (body), reversalPercentage (body), supportingDocumentRef (body), voidOnly (body) | No | No | No |
| `acctChecklist` | GET | `/api/v1/accounting/month-end/checklist` | - | periodId (query) | Yes | No | Yes |
| `acctUpdateChecklist` | POST | `/api/v1/accounting/month-end/checklist/{periodId}` | periodId (path) | bankReconciled (body), inventoryCounted (body), note (body) | No | No | No |
| `acctRecordPayrollPayment` | POST | `/api/v1/accounting/payroll/payments` | amount (body), cashAccountId (body), expenseAccountId (body), payrollRunId (body) | memo (body), referenceNumber (body) | No | No | No |
| `acctListPeriods` | GET | `/api/v1/accounting/periods` | - | - | Yes | No | Yes |
| `acctClosePeriod` | POST | `/api/v1/accounting/periods/{periodId}/close` | periodId (path) | force (body), note (body) | No | No | Conditional |
| `acctLockPeriod` | POST | `/api/v1/accounting/periods/{periodId}/lock` | periodId (path) | reason (body) | No | No | Conditional |
| `acctReopenPeriod` | POST | `/api/v1/accounting/periods/{periodId}/reopen` | periodId (path) | reason (body) | No | No | Conditional |
| `acctRecordDealerReceipt` | POST | `/api/v1/accounting/receipts/dealer` | allocations (body), allocations[].appliedAmount (body), amount (body), cashAccountId (body), dealerId (body) | Idempotency-Key (header), allocations[].discountAmount (body), allocations[].fxAdjustment (body), allocations[].invoiceId (body), allocations[].memo (body), allocations[].purchaseId (body), allocations[].writeOffAmount (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctRecordDealerHybridReceipt` | POST | `/api/v1/accounting/receipts/dealer/hybrid` | dealerId (body), incomingLines (body), incomingLines[].accountId (body), incomingLines[].amount (body) | Idempotency-Key (header), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctGetDealerAging` | GET | `/api/v1/reports/aging/dealer/{dealerId}` | dealerId (path) | - | Yes | No | Yes |
| `acctGetDealerAgingDetailed` | GET | `/api/v1/reports/aging/dealer/{dealerId}/detailed` | dealerId (path) | - | Yes | No | Yes |
| `acctGetAgedReceivables` | GET | `/api/v1/reports/aging/receivables` | - | asOfDate (query) | Yes | No | Yes |
| `acctGetBalanceSheetHierarchy` | GET | `/api/v1/reports/balance-sheet/hierarchy` | - | - | Yes | No | Yes |
| `acctGetDealerDSO` | GET | `/api/v1/reports/dso/dealer/{dealerId}` | dealerId (path) | - | Yes | No | Yes |
| `acctGetIncomeStatementHierarchy` | GET | `/api/v1/reports/income-statement/hierarchy` | - | - | Yes | No | Yes |
| `acctListSalesReturns` | GET | `/api/v1/accounting/sales/returns` | - | - | Yes | No | Yes |
| `acctRecordSalesReturn` | POST | `/api/v1/accounting/sales/returns` | invoiceId (body), lines (body), lines[].invoiceLineId (body), lines[].quantity (body), reason (body) | - | No | No | No |
| `acctSettleDealer` | POST | `/api/v1/accounting/settlements/dealers` | allocations (body), allocations[].appliedAmount (body), dealerId (body), payments[].accountId (body), payments[].amount (body) | adminOverride (body), allocations[].discountAmount (body), allocations[].fxAdjustment (body), allocations[].invoiceId (body), allocations[].memo (body), allocations[].purchaseId (body), allocations[].writeOffAmount (body), cashAccountId (body), discountAccountId (body), fxGainAccountId (body), fxLossAccountId (body), idempotencyKey (body), memo (body), payments (body), payments[].method (body), referenceNumber (body), settlementDate (body), writeOffAccountId (body) | No | No | No |
| `acctSettleSupplier` | POST | `/api/v1/accounting/settlements/suppliers` | allocations (body), allocations[].appliedAmount (body), cashAccountId (body), supplierId (body) | Idempotency-Key (header), adminOverride (body), allocations[].discountAmount (body), allocations[].fxAdjustment (body), allocations[].invoiceId (body), allocations[].memo (body), allocations[].purchaseId (body), allocations[].writeOffAmount (body), discountAccountId (body), fxGainAccountId (body), fxLossAccountId (body), idempotencyKey (body), memo (body), referenceNumber (body), settlementDate (body), writeOffAccountId (body) | No | No | No |
| `acctDealerStatement` | GET | `/api/v1/accounting/statements/dealers/{dealerId}` | dealerId (path) | from (query), to (query) | Yes | No | Yes |
| `acctDealerStatementPdf` | GET | `/api/v1/accounting/statements/dealers/{dealerId}/pdf` | dealerId (path) | from (query), to (query) | Yes | No | Yes |
| `acctSupplierStatement` | GET | `/api/v1/accounting/statements/suppliers/{supplierId}` | supplierId (path) | from (query), to (query) | Yes | No | Yes |
| `acctSupplierStatementPdf` | GET | `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | supplierId (path) | from (query), to (query) | Yes | No | Yes |
| `acctRecordSupplierPayment` | POST | `/api/v1/accounting/suppliers/payments` | allocations (body), allocations[].appliedAmount (body), amount (body), cashAccountId (body), supplierId (body) | Idempotency-Key (header), allocations[].discountAmount (body), allocations[].fxAdjustment (body), allocations[].invoiceId (body), allocations[].memo (body), allocations[].purchaseId (body), allocations[].writeOffAmount (body), idempotencyKey (body), memo (body), referenceNumber (body) | No | No | No |
| `acctGetTrialBalanceAsOf` | GET | `/api/v1/accounting/trial-balance/as-of` | date (query) | - | Yes | No | Yes |

Canonical `/api/v1/reports/**` accounting endpoints in the table above run through `ReportController` and the shared `GlobalExceptionHandler`, not `AccountingController`'s retired always-400 handler. Frontend callers must branch on HTTP status plus `data.code` and `data.reason` instead of assuming every application failure is `400`. Current mapping for these routes is: validation `400`, `MODULE_DISABLED` `403`, `BUSINESS_ENTITY_NOT_FOUND` `404`, and business/concurrency/data conflicts `409`.

### Accounting Core Workflow Supplements (Code-Verified, Outside Parity Lock)

These rows are required for the period-close maker-checker UX, but they live outside the curated 143-row endpoint-map parity lock.

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `approvals` | GET | `/api/v1/admin/approvals` | - | - | Yes | No | Yes |
| `requestPeriodClose` | POST | `/api/v1/accounting/periods/{periodId}/request-close` | periodId (path), note (body) | force (body) | No | No | No |
| `approvePeriodClose` | POST | `/api/v1/accounting/periods/{periodId}/approve-close` | periodId (path), note (body) | force (body) | No | No | No |
| `rejectPeriodClose` | POST | `/api/v1/accounting/periods/{periodId}/reject-close` | periodId (path), note (body) | - | No | No | No |

### Invoice & Receivables

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `salesListDealersForAccounting` | GET | `/api/v1/sales/dealers` | - | - | Yes | No | Yes |
| `salesSearchDealersForAccounting` | GET | `/api/v1/sales/dealers/search` | - | query (query) | Yes | Yes | Yes |
| `invoiceListInvoices` | GET | `/api/v1/invoices` | - | page (query), size (query) | Yes | No | Yes |
| `invoiceDealerInvoices` | GET | `/api/v1/invoices/dealers/{dealerId}` | dealerId (path) | page (query), size (query) | Yes | No | Yes |
| `invoiceGetInvoice` | GET | `/api/v1/invoices/{id}` | id (path) | - | Yes | No | Yes |
| `invoiceSendInvoiceEmail` | POST | `/api/v1/invoices/{id}/email` | id (path) | - | No | No | No |
| `invoiceDownloadInvoicePdf` | GET | `/api/v1/invoices/{id}/pdf` | id (path) | - | Yes | No | Yes |

### Purchasing & Payables

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `poListGoodsReceipts` | GET | `/api/v1/purchasing/goods-receipts` | - | supplierId (query) | Yes | No | Yes |
| `poCreateGoodsReceipt` | POST | `/api/v1/purchasing/goods-receipts` | lines (body), lines[].costPerUnit (body), lines[].quantity (body), lines[].rawMaterialId (body), purchaseOrderId (body), receiptDate (body), receiptNumber (body) | Idempotency-Key (header), idempotencyKey (body), lines[].batchCode (body), lines[].notes (body), lines[].unit (body), memo (body) | No | No | No |
| `poGetGoodsReceipt` | GET | `/api/v1/purchasing/goods-receipts/{id}` | id (path) | - | Yes | No | Yes |
| `poListPurchaseOrders` | GET | `/api/v1/purchasing/purchase-orders` | - | supplierId (query) | Yes | No | Yes |
| `poCreatePurchaseOrder` | POST | `/api/v1/purchasing/purchase-orders` | lines (body), lines[].costPerUnit (body), lines[].quantity (body), lines[].rawMaterialId (body), orderDate (body), orderNumber (body), supplierId (body) | lines[].notes (body), lines[].unit (body), memo (body) | No | No | No |
| `poGetPurchaseOrder` | GET | `/api/v1/purchasing/purchase-orders/{id}` | id (path) | - | Yes | No | Yes |
| `rmPurchaseListPurchases` | GET | `/api/v1/purchasing/raw-material-purchases` | - | supplierId (query) | Yes | No | Yes |
| `rmPurchaseCreatePurchase` | POST | `/api/v1/purchasing/raw-material-purchases` | goodsReceiptId (body), invoiceDate (body), invoiceNumber (body), lines (body), lines[].costPerUnit (body), lines[].quantity (body), lines[].rawMaterialId (body), supplierId (body) | lines[].batchCode (body), lines[].notes (body), lines[].taxInclusive (body), lines[].taxRate (body), lines[].unit (body), memo (body), purchaseOrderId (body), taxAmount (body) | No | No | No |
| `rmPurchaseRecordPurchaseReturn` | POST | `/api/v1/purchasing/raw-material-purchases/returns` | purchaseId (body), quantity (body), rawMaterialId (body), supplierId (body), unitCost (body) | reason (body), referenceNumber (body), returnDate (body) | No | No | No |
| `rmPurchaseGetPurchase` | GET | `/api/v1/purchasing/raw-material-purchases/{id}` | id (path) | - | Yes | No | Yes |
| `supplierListSuppliers` | GET | `/api/v1/suppliers` | - | - | Yes | No | Yes |
| `supplierCreateSupplier` | POST | `/api/v1/suppliers` | name (body) | address (body), code (body), contactEmail (body), contactPhone (body), creditLimit (body) | No | No | No |
| `supplierGetSupplier` | GET | `/api/v1/suppliers/{id}` | id (path) | - | Yes | No | Yes |
| `supplierUpdateSupplier` | PUT | `/api/v1/suppliers/{id}` | id (path), name (body) | address (body), code (body), contactEmail (body), contactPhone (body), creditLimit (body) | No | No | Yes |

### Inventory & Costing

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `finishedGoodListFinishedGoods` | GET | `/api/v1/finished-goods` | - | - | Yes | No | Yes |
| `finishedGoodCreateFinishedGood` | POST | `/api/v1/finished-goods` | name (body), productCode (body) | cogsAccountId (body), costingMethod (body), discountAccountId (body), revenueAccountId (body), taxAccountId (body), unit (body), valuationAccountId (body) | No | No | No |
| `finishedGoodGetLowStockItems` | GET | `/api/v1/finished-goods/low-stock` | - | threshold (query) | Yes | No | Yes |
| `finishedGoodGetStockSummary` | GET | `/api/v1/finished-goods/stock-summary` | - | - | Yes | No | Yes |
| `finishedGoodGetFinishedGood` | GET | `/api/v1/finished-goods/{id}` | id (path) | - | Yes | No | Yes |
| `finishedGoodUpdateFinishedGood` | PUT | `/api/v1/finished-goods/{id}` | id (path), name (body), productCode (body) | cogsAccountId (body), costingMethod (body), discountAccountId (body), revenueAccountId (body), taxAccountId (body), unit (body), valuationAccountId (body) | No | No | Yes |
| `finishedGoodListBatches` | GET | `/api/v1/finished-goods/{id}/batches` | id (path) | - | Yes | No | Yes |
| `finishedGoodRegisterBatch` | POST | `/api/v1/finished-goods/{id}/batches` | finishedGoodId (body), id (path), quantity (body), unitCost (body) | batchCode (body), expiryDate (body), manufacturedAt (body) | No | No | No |
| `inventoryAdjustmentListAdjustments` | GET | `/api/v1/inventory/adjustments` | - | - | Yes | No | Yes |
| `inventoryAdjustmentCreateAdjustment` | POST | `/api/v1/inventory/adjustments` | adjustmentAccountId (body), lines (body), lines[].finishedGoodId (body), lines[].quantity (body), lines[].unitCost (body), type (body) | Idempotency-Key (header), adjustmentDate (body), adminOverride (body), idempotencyKey (body), lines[].note (body), reason (body) | No | No | No |
| `inventoryImportOpeningStock` | POST | `/api/v1/inventory/opening-stock` | file (multipart body) | Idempotency-Key (header) | No | No | Conditional |
| `rawMaterialListRawMaterials` | GET | `/api/v1/accounting/raw-materials` | - | - | Yes | No | Yes |
| `rawMaterialCreateRawMaterial` | POST | `/api/v1/accounting/raw-materials` | maxStock (body), minStock (body), name (body), reorderLevel (body), unitType (body) | costingMethod (body), inventoryAccountId (body), sku (body) | No | No | No |
| `rawMaterialDeleteRawMaterial` | DELETE | `/api/v1/accounting/raw-materials/{id}` | id (path) | - | No | No | Yes |
| `rawMaterialUpdateRawMaterial` | PUT | `/api/v1/accounting/raw-materials/{id}` | id (path), maxStock (body), minStock (body), name (body), reorderLevel (body), unitType (body) | costingMethod (body), inventoryAccountId (body), sku (body) | No | No | Yes |
| `rawMaterialBatches` | GET | `/api/v1/raw-material-batches/{rawMaterialId}` | rawMaterialId (path) | - | Yes | No | Yes |
| `rawMaterialCreateBatch` | POST | `/api/v1/raw-material-batches/{rawMaterialId}` | costPerUnit (body), quantity (body), rawMaterialId (path), supplierId (body), unit (body) | Idempotency-Key (header), batchCode (body), notes (body) | No | No | No |
| `rawMaterialIntake` | POST | `/api/v1/raw-materials/intake` | costPerUnit (body), quantity (body), rawMaterialId (body), supplierId (body), unit (body) | Idempotency-Key (header), batchCode (body), notes (body) | No | No | No |
| `rawMaterialStockSummary` | GET | `/api/v1/raw-materials/stock` | - | - | Yes | No | Yes |
| `rawMaterialInventory` | GET | `/api/v1/raw-materials/stock/inventory` | - | - | Yes | No | Yes |
| `rawMaterialLowStock` | GET | `/api/v1/raw-materials/stock/low-stock` | - | - | Yes | No | Yes |

### HR & Payroll

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `hrBulkMarkAttendance` | POST | `/api/v1/hr/attendance/bulk-mark` | date (body), employeeIds (body), status (body) | checkInTime (body), checkInTime.hour (body), checkInTime.minute (body), checkInTime.nano (body), checkInTime.second (body), checkOutTime (body), checkOutTime.hour (body), checkOutTime.minute (body), checkOutTime.nano (body), checkOutTime.second (body), overtimeHours (body), regularHours (body), remarks (body) | No | No | No |
| `hrAttendanceByDate` | GET | `/api/v1/hr/attendance/date/{date}` | date (path) | - | Yes | No | Yes |
| `hrEmployeeAttendance` | GET | `/api/v1/hr/attendance/employee/{employeeId}` | employeeId (path), endDate (query), startDate (query) | - | Yes | No | Yes |
| `hrMarkAttendance` | POST | `/api/v1/hr/attendance/mark/{employeeId}` | employeeId (path), status (body) | checkInTime (body), checkInTime.hour (body), checkInTime.minute (body), checkInTime.nano (body), checkInTime.second (body), checkOutTime (body), checkOutTime.hour (body), checkOutTime.minute (body), checkOutTime.nano (body), checkOutTime.second (body), date (body), doubleOvertimeHours (body), holiday (body), overtimeHours (body), regularHours (body), remarks (body), weekend (body) | No | No | No |
| `hrAttendanceSummary` | GET | `/api/v1/hr/attendance/summary` | - | - | Yes | No | Yes |
| `hrAttendanceToday` | GET | `/api/v1/hr/attendance/today` | - | - | Yes | No | Yes |
| `hrEmployees` | GET | `/api/v1/hr/employees` | - | - | Yes | No | Yes |
| `hrCreateEmployee` | POST | `/api/v1/hr/employees` | email (body), firstName (body), lastName (body) | bankAccountNumber (body), bankName (body), dailyWage (body), doubleOtRateMultiplier (body), employeeType (body), hiredDate (body), ifscCode (body), monthlySalary (body), overtimeRateMultiplier (body), paymentSchedule (body), phone (body), role (body), standardHoursPerDay (body), weeklyOffDays (body), workingDaysPerMonth (body) | No | No | No |
| `hrDeleteEmployee` | DELETE | `/api/v1/hr/employees/{id}` | id (path) | - | No | No | Yes |
| `hrUpdateEmployee` | PUT | `/api/v1/hr/employees/{id}` | email (body), firstName (body), id (path), lastName (body) | bankAccountNumber (body), bankName (body), dailyWage (body), doubleOtRateMultiplier (body), employeeType (body), hiredDate (body), ifscCode (body), monthlySalary (body), overtimeRateMultiplier (body), paymentSchedule (body), phone (body), role (body), standardHoursPerDay (body), weeklyOffDays (body), workingDaysPerMonth (body) | No | No | Yes |
| `hrLeaveRequests` | GET | `/api/v1/hr/leave-requests` | - | - | Yes | No | Yes |
| `hrCreateLeaveRequest` | POST | `/api/v1/hr/leave-requests` | endDate (body), leaveType (body), startDate (body) | employeeId (body), reason (body), status (body) | No | No | No |
| `hrUpdateLeaveStatus` | PATCH | `/api/v1/hr/leave-requests/{id}/status` | id (path) | status (body) | No | No | Conditional |
| `hrPayrollRuns` | GET | `/api/v1/hr/payroll-runs` | - | - | Yes | No | Yes |
| `hrCreatePayrollRun` | POST | `/api/v1/hr/payroll-runs` | creditAccountId (body), debitAccountId (body), initiatedBy (body), payrollDate (body), postingAmount (body) | - | No | No | No |
| `payrollListPayrollRuns` | GET | `/api/v1/payroll/runs` | - | - | Yes | No | Yes |
| `payrollCreatePayrollRun` | POST | `/api/v1/payroll/runs` | - | periodEnd (body), periodStart (body), remarks (body), runType (body) | No | No | No |
| `payrollListMonthlyPayrollRuns` | GET | `/api/v1/payroll/runs/monthly` | - | - | Yes | No | Yes |
| `payrollCreateMonthlyPayrollRun` | POST | `/api/v1/payroll/runs/monthly` | month (query), year (query) | - | No | No | No |
| `payrollListWeeklyPayrollRuns` | GET | `/api/v1/payroll/runs/weekly` | - | - | Yes | No | Yes |
| `payrollCreateWeeklyPayrollRun` | POST | `/api/v1/payroll/runs/weekly` | weekEndingDate (query) | - | No | No | No |
| `payrollGetPayrollRun` | GET | `/api/v1/payroll/runs/{id}` | id (path) | - | Yes | No | Yes |
| `payrollApprovePayroll` | POST | `/api/v1/payroll/runs/{id}/approve` | id (path) | - | No | No | Conditional |
| `payrollCalculatePayroll` | POST | `/api/v1/payroll/runs/{id}/calculate` | id (path) | - | No | No | Conditional |
| `payrollGetPayrollRunLines` | GET | `/api/v1/payroll/runs/{id}/lines` | id (path) | - | Yes | No | Yes |
| `payrollMarkAsPaid` | POST | `/api/v1/payroll/runs/{id}/mark-paid` | id (path) | - | No | No | Conditional |
| `payrollPostPayroll` | POST | `/api/v1/payroll/runs/{id}/post` | id (path) | - | No | No | No |
| `payrollGetCurrentMonthPaySummary` | GET | `/api/v1/payroll/summary/current-month` | - | - | Yes | No | Yes |
| `payrollGetCurrentWeekPaySummary` | GET | `/api/v1/payroll/summary/current-week` | - | - | Yes | No | Yes |
| `payrollGetMonthlyPaySummary` | GET | `/api/v1/payroll/summary/monthly` | month (query), year (query) | - | Yes | No | Yes |
| `payrollGetWeeklyPaySummary` | GET | `/api/v1/payroll/summary/weekly` | weekEndingDate (query) | - | Yes | No | Yes |
| `payrollBatchProcessBatchPayment` | POST | `/api/v1/accounting/payroll/payments/batch` | cashAccountId (body), expenseAccountId (body), lines (body), lines[].dailyWage (body), lines[].days (body), lines[].name (body), runDate (body) | defaultPfRate (body), defaultTaxRate (body), employerPfExpenseAccountId (body), employerPfRate (body), employerTaxExpenseAccountId (body), employerTaxRate (body), lines[].advances (body), lines[].notes (body), lines[].pfWithholding (body), lines[].taxWithholding (body), memo (body), pfPayableAccountId (body), referenceNumber (body), taxPayableAccountId (body) | No | No | No |

### Reports & Reconciliation

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `reportAgedDebtors` | GET | `/api/v1/reports/aged-debtors` | - | - | Yes | No | Yes |
| `reportAccountStatement` | GET | `/api/v1/reports/account-statement` | - | - | Yes | No | Yes |
| `reportBalanceSheet` | GET | `/api/v1/reports/balance-sheet` | - | date (query) | Yes | No | Yes |
| `reportBalanceWarnings` | GET | `/api/v1/reports/balance-warnings` | - | - | Yes | No | Yes |
| `reportCashFlow` | GET | `/api/v1/reports/cash-flow` | - | - | Yes | No | Yes |
| `reportInventoryReconciliation` | GET | `/api/v1/reports/inventory-reconciliation` | - | - | Yes | No | Yes |
| `reportInventoryValuation` | GET | `/api/v1/reports/inventory-valuation` | - | date (query) | Yes | No | Yes |
| `reportMonthlyProductionCosts` | GET | `/api/v1/reports/monthly-production-costs` | month (query), year (query) | - | Yes | No | Yes |
| `reportCostBreakdown` | GET | `/api/v1/reports/production-logs/{id}/cost-breakdown` | id (path) | - | Yes | No | Yes |
| `reportProfitLoss` | GET | `/api/v1/reports/profit-loss` | - | date (query) | Yes | No | Yes |
| `reportReconciliationDashboard` | GET | `/api/v1/reports/reconciliation-dashboard` | bankAccountId (query) | statementBalance (query) | Yes | No | Yes |
| `reportTrialBalance` | GET | `/api/v1/reports/trial-balance` | - | date (query) | Yes | No | Yes |
| `reportWastageReport` | GET | `/api/v1/reports/wastage` | - | - | Yes | No | Yes |

### Endpoints That Look Unsafe or Inconsistent

- `GET /api/v1/suppliers/{id}` documents no explicit error responses (only: 200).
- `PUT /api/v1/suppliers/{id}` documents no explicit error responses (only: 200).
- `PUT /api/v1/suppliers/{id}` is mutating but defines only `200` (missing richer status semantics).
- `PUT /api/v1/hr/employees/{id}` documents no explicit error responses (only: 200).
- `PUT /api/v1/hr/employees/{id}` is mutating but defines only `200` (missing richer status semantics).
- `DELETE /api/v1/hr/employees/{id}` documents no explicit error responses (only: 200).
- `DELETE /api/v1/hr/employees/{id}` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/finished-goods/{id}` documents no explicit error responses (only: 200).
- `PUT /api/v1/finished-goods/{id}` documents no explicit error responses (only: 200).
- `PUT /api/v1/finished-goods/{id}` is mutating but defines only `200` (missing richer status semantics).
- `PUT /api/v1/accounting/raw-materials/{id}` documents no explicit error responses (only: 200).
- `PUT /api/v1/accounting/raw-materials/{id}` is mutating but defines only `200` (missing richer status semantics).
- `DELETE /api/v1/accounting/raw-materials/{id}` documents no explicit error responses (only: 200).
- `DELETE /api/v1/accounting/raw-materials/{id}` is mutating but defines only `200` (missing richer status semantics).
- `PUT /api/v1/accounting/catalog/products/{id}` documents no explicit error responses (only: 200).
- `PUT /api/v1/accounting/catalog/products/{id}` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/suppliers` documents no explicit error responses (only: 200).
- `POST /api/v1/suppliers` documents no explicit error responses (only: 200).
- `POST /api/v1/suppliers` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/raw-materials/intake` documents no explicit error responses (only: 200).
- `POST /api/v1/raw-materials/intake` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/raw-material-batches/{rawMaterialId}` documents no explicit error responses (only: 200).
- `POST /api/v1/raw-material-batches/{rawMaterialId}` documents no explicit error responses (only: 200).
- `POST /api/v1/raw-material-batches/{rawMaterialId}` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/purchasing/raw-material-purchases` documents no explicit error responses (only: 200).
- `POST /api/v1/purchasing/raw-material-purchases` documents no explicit error responses (only: 200).
- `POST /api/v1/purchasing/raw-material-purchases` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/purchasing/raw-material-purchases/returns` documents no explicit error responses (only: 200).
- `POST /api/v1/purchasing/raw-material-purchases/returns` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/purchasing/purchase-orders` documents no explicit error responses (only: 200).
- `POST /api/v1/purchasing/purchase-orders` documents no explicit error responses (only: 200).
- `POST /api/v1/purchasing/purchase-orders` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/purchasing/goods-receipts` documents no explicit error responses (only: 200).
- `POST /api/v1/purchasing/goods-receipts` documents no explicit error responses (only: 200).
- `POST /api/v1/purchasing/goods-receipts` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/payroll/runs` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/payroll/runs/{id}/post` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs/{id}/post` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/payroll/runs/{id}/mark-paid` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs/{id}/mark-paid` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/payroll/runs/{id}/calculate` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs/{id}/calculate` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/payroll/runs/{id}/approve` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs/{id}/approve` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/payroll/runs/weekly` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs/weekly` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs/weekly` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/payroll/runs/monthly` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs/monthly` documents no explicit error responses (only: 200).
- `POST /api/v1/payroll/runs/monthly` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/invoices/{id}/email` documents no explicit error responses (only: 200).
- `POST /api/v1/invoices/{id}/email` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/inventory/adjustments` documents no explicit error responses (only: 200).
- `POST /api/v1/inventory/adjustments` documents no explicit error responses (only: 200).
- `POST /api/v1/inventory/adjustments` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/hr/payroll-runs` documents no explicit error responses (only: 200).
- `POST /api/v1/hr/payroll-runs` has generated operationId `createPayrollRun_1` (unstable client naming).
- `POST /api/v1/hr/payroll-runs` documents no explicit error responses (only: 200).
- `POST /api/v1/hr/payroll-runs` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/hr/leave-requests` documents no explicit error responses (only: 200).
- `POST /api/v1/hr/leave-requests` documents no explicit error responses (only: 200).
- `POST /api/v1/hr/leave-requests` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/hr/employees` documents no explicit error responses (only: 200).
- `POST /api/v1/hr/employees` documents no explicit error responses (only: 200).
- `POST /api/v1/hr/employees` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/hr/attendance/mark/{employeeId}` documents no explicit error responses (only: 200).
- `POST /api/v1/hr/attendance/mark/{employeeId}` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/hr/attendance/bulk-mark` documents no explicit error responses (only: 200).
- `POST /api/v1/hr/attendance/bulk-mark` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/finished-goods` documents no explicit error responses (only: 200).
- `POST /api/v1/finished-goods` documents no explicit error responses (only: 200).
- `POST /api/v1/finished-goods` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/finished-goods/{id}/batches` documents no explicit error responses (only: 200).
- `POST /api/v1/finished-goods/{id}/batches` documents no explicit error responses (only: 200).
- `POST /api/v1/finished-goods/{id}/batches` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/accounting/raw-materials` documents no explicit error responses (only: 200).
- `POST /api/v1/accounting/raw-materials` documents no explicit error responses (only: 200).
- `POST /api/v1/accounting/raw-materials` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/accounting/payroll/payments/batch` documents no explicit error responses (only: 200).
- `POST /api/v1/accounting/payroll/payments/batch` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/accounting/catalog/products` documents no explicit error responses (only: 200).
- `POST /api/v1/accounting/catalog/products` documents no explicit error responses (only: 200).
- `POST /api/v1/accounting/catalog/products` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/accounting/catalog/products/bulk-variants` documents no explicit error responses (only: 200).
- `POST /api/v1/accounting/catalog/products/bulk-variants` is mutating but defines only `200` (missing richer status semantics).
- `POST /api/v1/accounting/catalog/import` now documents multipart guard semantics: missing/empty file -> 400, explicit disallowed MIME -> 422 (`FILE_003`), idempotency mismatch -> 409 (`CONC_001`).
- `PATCH /api/v1/hr/leave-requests/{id}/status` documents no explicit error responses (only: 200).
- `PATCH /api/v1/hr/leave-requests/{id}/status` is mutating but defines only `200` (missing richer status semantics).
- `GET /api/v1/reports/wastage` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/trial-balance` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/reconciliation-dashboard` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/profit-loss` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/production-logs/{id}/cost-breakdown` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/monthly-production-costs` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/inventory-valuation` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/inventory-reconciliation` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/cash-flow` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/balance-warnings` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/balance-sheet` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/account-statement` documents no explicit error responses (only: 200).
- `GET /api/v1/raw-materials/stock` documents no explicit error responses (only: 200).
- `GET /api/v1/raw-materials/stock/low-stock` documents no explicit error responses (only: 200).
- `GET /api/v1/raw-materials/stock/inventory` documents no explicit error responses (only: 200).
- `GET /api/v1/purchasing/raw-material-purchases/{id}` documents no explicit error responses (only: 200).
- `GET /api/v1/purchasing/purchase-orders/{id}` documents no explicit error responses (only: 200).
- `GET /api/v1/purchasing/goods-receipts/{id}` documents no explicit error responses (only: 200).
- `GET /api/v1/payroll/summary/weekly` documents no explicit error responses (only: 200).
- `GET /api/v1/payroll/summary/monthly` documents no explicit error responses (only: 200).
- `GET /api/v1/payroll/summary/current-week` documents no explicit error responses (only: 200).
- `GET /api/v1/payroll/summary/current-month` documents no explicit error responses (only: 200).
- `GET /api/v1/payroll/runs/{id}` documents no explicit error responses (only: 200).
- `GET /api/v1/payroll/runs/{id}/lines` documents no explicit error responses (only: 200).
- `GET /api/v1/invoices` documents no explicit error responses (only: 200).
- `GET /api/v1/invoices/{id}` documents no explicit error responses (only: 200).
- `GET /api/v1/invoices/{id}/pdf` documents no explicit error responses (only: 200).
- `GET /api/v1/invoices/dealers/{dealerId}` documents no explicit error responses (only: 200).
- `GET /api/v1/hr/attendance/today` documents no explicit error responses (only: 200).
- `GET /api/v1/hr/attendance/summary` documents no explicit error responses (only: 200).
- `GET /api/v1/hr/attendance/employee/{employeeId}` documents no explicit error responses (only: 200).
- `GET /api/v1/hr/attendance/date/{date}` documents no explicit error responses (only: 200).
- `GET /api/v1/finished-goods/stock-summary` documents no explicit error responses (only: 200).
- `GET /api/v1/finished-goods/low-stock` documents no explicit error responses (only: 200).
- `GET /api/v1/reports/aged-debtors` documents no explicit error responses (only: 200).
- `GET /api/v1/accounting/configuration/health` documents no explicit error responses (only: 200).
- `GET /api/v1/accounting/aging/dealers/{dealerId}` has generated operationId `dealerAging_1` (unstable client naming).
- Reporting APIs are canonical under `/api/v1/reports/*`; remove any client routing that still targets `/api/v1/accounting/reports/*`.
- HR payroll appears in both `/api/v1/hr/payroll-runs` and `/api/v1/payroll/runs`; treat `/api/v1/payroll/runs` as canonical run-processing surface unless backend clarifies otherwise.
- `POST /api/v1/inventory/opening-stock` defines only `200`; import-row failures and idempotency conflicts are business/runtime errors not strongly typed in OpenAPI.
- `POST /api/v1/inventory/opening-stock` accepts optional `Idempotency-Key`, but backend normalizes to file-hash fallback and enforces conflict on key reuse with different payload; frontend should always send a stable key.

## Task 3: Enterprise Accounting Route Map

### Universal Header Controls (Accounting Portal)

| UI control | Target | APIs | Loading/Error expectations | Gate |
|---|---|---|---|---|
| `My Profile` | `/accounting/profile` | `profileGet`, `profileUpdate` | Form skeleton; inline validation + toast | `isAuthenticated()` |
| `Change Password` | `/accounting/profile?tab=password` | `authChangePassword` | Submit spinner; inline validation | `isAuthenticated()` |
| `Switch Company` | Company switch modal | `companiesList`, `companiesSwitch` | Modal loader; on success hard refresh accounting data caches | `isAuthenticated()` + company membership check. `companiesList` additionally needs one of `ROLE_ADMIN|ROLE_ACCOUNTING|ROLE_SALES` |
| `Sign Out` | Redirect `/auth/login` | `authLogout` | Immediate spinner; fallback local sign-out on failure | `isAuthenticated()` |

| Route | Purpose | Backend-enforced gate (exact) |
|---|---|---|
| `/accounting/dashboard` | Accountant cockpit (trial balance, P&L/BS summary, reconciliation warnings, period status). | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/accounting/gl/chart-of-accounts` | COA browsing, account creation, default account mapping. | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/accounting/gl/journals` | Journal listing, posting, reversing, cascade reversing. | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/accounting/period-close` | Period lifecycle controls (checklist, close, lock, reopen). | Mixed: checklist/request-close/queue read use `ROLE_ADMIN or ROLE_ACCOUNTING`; `approvePeriodClose`/`rejectPeriodClose` are `ROLE_ADMIN` only; `acctReopenPeriod` is `ROLE_SUPER_ADMIN` only |
| `/accounting/ar/invoices` | Invoice tracking, invoice PDF/email delivery, dealer invoice views. | `ROLE_ADMIN or ROLE_ACCOUNTING or ROLE_SALES` |
| `/accounting/ar/collections-settlements` | Receipts, settlements, sales returns, aging/statements for receivables. | `ROLE_ADMIN or ROLE_ACCOUNTING` (plus `GET /accounting/sales/returns` also allows `ROLE_SALES`) |
| `/accounting/ap/suppliers-purchases` | Supplier master, PO/GRN, raw-material purchase lifecycle, AP settlement/payment. | `ROLE_ADMIN or ROLE_ACCOUNTING` (supplier list/get additionally allow `ROLE_FACTORY`) |
| `/accounting/inventory/sku-catalog` | SKU/product catalog management for accounting-grade product governance. | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/accounting/inventory/raw-materials` | Raw material masters, intake, batches, stock and low-stock monitoring. | Base `ROLE_ADMIN or ROLE_ACCOUNTING or ROLE_FACTORY`; intake action requires `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/accounting/inventory/opening-stock` | Controlled opening stock import (migration-grade) with accounting posting impact. | `ROLE_ADMIN or ROLE_ACCOUNTING or ROLE_FACTORY` |
| `/accounting/inventory/finished-goods` | Finished goods stock, batches, master edits, low-stock alerts. | Mixed: read summary/list allows accounting; create/update/register batch require `ROLE_ADMIN or ROLE_FACTORY`; low-stock + batch-list read exclude `ROLE_ACCOUNTING` |
| `/accounting/inventory/adjustments-costing` | Inventory adjustments, landed cost, revaluation, WIP adjustments with accounting impact. | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/accounting/hr/employees-attendance` | Employee master, attendance capture, leave approvals for payroll readiness. | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/accounting/hr/payroll-runs` | Payroll run lifecycle: create, calculate, approve, post, mark paid, batch payments. | `ROLE_ADMIN or ROLE_ACCOUNTING` |
| `/accounting/reports/financial` | Trial balance, P&L, balance sheet, cash flow, inventory valuation/reconciliation, aged debtors, wastage. | `ROLE_ADMIN or ROLE_ACCOUNTING` |

### `/accounting/dashboard`
- Purpose: Accountant cockpit (trial balance, P&L/BS summary, reconciliation warnings, period status).
- Required API calls: `reportReconciliationDashboard`, `reportTrialBalance`, `reportProfitLoss`, `reportBalanceSheet`, `acctListPeriods`, `reportBalanceWarnings`
- Loading state: loading widgets; empty-state cards for no-data periods; per-widget error fallback + retry.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: KPI cards: current period, total assets, total liabilities, net profit, unmatched balances, open checklist items.
- Suggested form fields: Filters: period range, company, report basis.
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING` (exact backend)

### `/accounting/gl/chart-of-accounts`
- Purpose: COA browsing, account creation, default account mapping.
- Required API calls: `acctGetChartOfAccountsTree`, `acctAccounts`, `acctCreateAccount`, `acctDefaultAccounts`, `acctUpdateDefaultAccounts`
- Loading state: tree skeleton; empty tree onboarding; save-state feedback for default mappings.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: Account list columns: code, name, type, normal balance, active flag, parent account.
- Suggested form fields: Create account form from `AccountRequest`: code, name, type, parent/ref attributes; default account mapping form from `CompanyDefaultAccountsRequest`.
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING` (exact backend)

### `/accounting/gl/journals`
- Purpose: Journal listing, posting, reversing, cascade reversing.
- Required API calls: `acctJournalEntries`, `acctCreateJournalEntry`, `acctReverseJournalEntry`, `acctCascadeReverseJournalEntry`
- Loading state: table loading; empty no-journal state; high-risk action confirm modals; optimistic lock while posting/reversing.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: From `JournalEntryDto`: entryId/reference, date, source module, status, debit total, credit total, createdBy.
- Suggested form fields: From `JournalEntryRequest`: date, narration, sourceRef, lines[] (accountId, debit/credit, cost center, tax fields).
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING` (exact backend)

### `/accounting/period-close`
- Purpose: Period lifecycle controls with maker-checker close approval (checklist, request-close, approve/reject, lock, reopen).
- Required API calls: `acctListPeriods`, `acctChecklist`, `acctUpdateChecklist`, `acctLockPeriod`, `requestPeriodClose`, `approvePeriodClose`, `rejectPeriodClose`, `acctReopenPeriod`
- Backend workflow note: do not wire `acctClosePeriod` as a frontend action. `AccountingPeriodService.closePeriod(...)` hard-fails direct close with `Direct close is disabled; submit /request-close and approve via maker-checker workflow`.
- Approval queue dependency: `approvals` (`GET /api/v1/admin/approvals`) for pending close-request visibility. Queue visibility is `ROLE_ADMIN or ROLE_ACCOUNTING`; platform `ROLE_SUPER_ADMIN` is blocked from this queue by `CompanyContextFilter`.
- Loading state: timeline loader; lock/request-close actions blocked until checklist passes; derived pending-review state after maker submission from `PeriodCloseRequestDto.status` / approval queue payload; warning banners for reject/reopen flows.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: Period grid from `AccountingPeriodDto`: periodId, startDate, endDate, status(OPEN/CLOSED/LOCKED), closedAt, lockedAt. If product needs a pending-review badge or requester label, derive it by joining `PeriodCloseRequestDto` / `approvals` data instead of waiting for extra fields on `acctListPeriods`.
- Suggested form fields: Checklist form items, maker note for `request-close`, checker approval/rejection note, reopen reason.
- Role/permission gate: Mixed by endpoint. `acctListPeriods`, `acctChecklist`, `acctUpdateChecklist`, `acctLockPeriod`, and `requestPeriodClose` allow `ROLE_ADMIN or ROLE_ACCOUNTING`; `approvePeriodClose` and `rejectPeriodClose` are `ROLE_ADMIN` only; `approvals` visibility is `ROLE_ADMIN or ROLE_ACCOUNTING`; `acctReopenPeriod` is `ROLE_SUPER_ADMIN` only. Do not surface checker actions to accounting-only users, and do not surface reopen outside superadmin UX.

### `/accounting/ar/invoices`
- Purpose: Invoice tracking, invoice PDF/email delivery, dealer invoice views.
- Required API calls (shared accountant/sales/admin views): `salesListDealersForAccounting`, `salesSearchDealersForAccounting`, `invoiceListInvoices`, `invoiceGetInvoice`, `invoiceSendInvoiceEmail`, `invoiceDealerInvoices`
- Admin-only APIs (do not expose to accounting/sales roles): `invoiceDownloadInvoicePdf`
- Backend expectation: invoice creation/issuance is not exposed in this controller; accounting portal handles invoice visibility/distribution while issuance is upstream in sales/dispatch workflows.
- Loading state: list/detail loading; empty no-invoices state; PDF download progress; email send toast.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: From `InvoiceDto`: invoiceNo, invoiceDate, dealerName, grossAmount, taxAmount, netAmount, status, dueDate.
- Suggested form fields: Email invoice form (recipient, subject/body template controls).
- Role/permission gate: Mixed by endpoint: list/detail/email/dealer views inherit `ROLE_ADMIN|ROLE_ACCOUNTING|ROLE_SALES`; `invoiceDownloadInvoicePdf` is `ROLE_ADMIN` only.

### `/accounting/ar/collections-settlements`
- Purpose: Receipts, settlements, sales returns, aging/statements for receivables.
- Required API calls (shared accountant-owned path): `acctRecordDealerReceipt`, `acctRecordDealerHybridReceipt`, `acctSettleDealer`, `acctGetDealerAging`, `acctGetDealerAgingDetailed`, `acctDealerStatement`, `acctListSalesReturns`, `acctRecordSalesReturn`, `acctPostCreditNote`, `acctWriteOffBadDebt`
- Admin-only exports (keep off accounting/sales action menus): `acctDealerStatementPdf`
- Loading state: aging panel loaders; no-open-items empty states; settlement action queues; document generation progress.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: Aging columns: bucket(0-30/31-60/61-90/90+), outstanding, overdueDays, latestReceiptDate.
- Suggested form fields: Receipt/settlement forms from `DealerReceiptRequest`/`DealerSettlementRequest` (amount, mode, allocation lines, reference).
- Role/permission gate: Mixed by endpoint: receipts/settlements/aging/statement reads use `ROLE_ADMIN|ROLE_ACCOUNTING`; `GET /api/v1/accounting/sales/returns` also permits `ROLE_SALES`; `acctDealerStatementPdf` is `ROLE_ADMIN` only.

### `/accounting/ap/suppliers-purchases`
- Purpose: Supplier master, PO/GRN, raw-material purchase lifecycle, AP settlement/payment.
- Required API calls: `supplierListSuppliers`, `supplierCreateSupplier`, `supplierUpdateSupplier`, `poListPurchaseOrders`, `poCreatePurchaseOrder`, `poListGoodsReceipts`, `poCreateGoodsReceipt`, `rmPurchaseListPurchases`, `rmPurchaseCreatePurchase`, `rmPurchaseRecordPurchaseReturn`, `acctRecordSupplierPayment`, `acctSettleSupplier`, `acctSupplierAging`, `acctSupplierStatement`
- Loading state: multi-step workflow steppers; document header/line skeletons; AP aging empty states; payment action confirmations.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: PO/GRN/Purchase columns: docNo, supplier, docDate, qty, taxableValue, tax, total, postingStatus.
- Suggested form fields: From `PurchaseOrderRequest`, `RawMaterialPurchaseRequest`, `SupplierPaymentRequest`, `SupplierSettlementRequest`.
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING` for AP workflows; supplier `GET` endpoints also permit `ROLE_FACTORY`.

### `/accounting/inventory/sku-catalog`
- Purpose: Maintain enterprise SKU catalog (product definitions, variant matrixes, pricing/tax metadata) used by downstream inventory and invoicing flows.
- Required API calls: `acctCatalogListProducts`, `acctCatalogCreateProduct`, `acctCatalogCreateVariants`, `acctCatalogUpdateProduct`, `acctCatalogImportCatalog`
- Loading state: catalog grid skeleton, import-job progress indicator, optimistic row update feedback after create/update.
- Empty state: no products created yet for selected company.
- Error state: row-level import error grid (from catalog import response), inline form errors for SKU collisions and invalid account defaults.
- Suggested table columns: SKU, productName, brand, category, defaultColour, sizeLabel, unitOfMeasure, basePrice, gstRate, minDiscountPercent, minSellingPrice, active.
- Suggested form fields: From `ProductCreateRequest`/`ProductUpdateRequest` and `BulkVariantRequest` (brand, category, productName/baseProductName, color/size matrix, skuPrefix/customSkuCode, price/tax controls, metadata).
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING` (exact backend).

### `/accounting/inventory/raw-materials`
- Purpose: Raw material masters, intake, batches, stock and low-stock monitoring.
- Required API calls: `rawMaterialListRawMaterials`, `rawMaterialCreateRawMaterial`, `rawMaterialUpdateRawMaterial`, `rawMaterialDeleteRawMaterial`, `rawMaterialIntake`, `rawMaterialStockSummary`, `rawMaterialInventory`, `rawMaterialLowStock`, `rawMaterialBatches`, `rawMaterialCreateBatch`
- Loading state: inventory grid loader; low-stock alert empty/non-empty states; intake posting spinner with stock refresh.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: From `RawMaterialDto`/`StockSummaryDto`: sku, materialName, uom, onHandQty, reservedQty, reorderLevel, avgCost.
- Suggested form fields: From `RawMaterialRequest`, `RawMaterialIntakeRequest`, `RawMaterialBatchRequest`.
- Role/permission gate: Base `ROLE_ADMIN|ROLE_ACCOUNTING|ROLE_FACTORY`; intake action restricted to `ROLE_ADMIN|ROLE_ACCOUNTING`.

### `/accounting/inventory/opening-stock`
- Purpose: One-time or controlled migration import of opening balances for raw materials and finished goods with linked accounting journal impact.
- Required API calls: `inventoryImportOpeningStock`, `acctJournalEntries`, `reportInventoryValuation`, `reportInventoryReconciliation`
- Accounting posting expectation: backend posts a journal (reference pattern `OPEN-STOCK-*`) that debits inventory valuation accounts and credits opening balance equity account `OPEN-BAL`.
- Loading state: upload progress, import-processing spinner, post-success reconciliation prompt.
- Empty state: no prior imports or no current upload selection.
- Error state: row-level error table from `errors[]`; explicit conflict message for idempotency key reuse with different file payload; preserve selected file metadata and idempotency key input.
- Suggested table columns: Import result summary from `OpeningStockImportResponse`: rowsProcessed, rawMaterialsCreated, rawMaterialBatchesCreated, finishedGoodsCreated, finishedGoodBatchesCreated; error grid: rowNumber, message.
- Suggested form fields: required `file` (CSV), recommended `Idempotency-Key`; show parsed file hash/readiness, and a mandatory confirmation checkbox noting accounting impact.
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING or ROLE_FACTORY` (exact backend). In production, backend can block this flow unless `erp.inventory.opening-stock.enabled=true`.

### `/accounting/inventory/finished-goods`
- Purpose: Finished goods stock, batches, master edits, low-stock alerts.
- Required API calls: `finishedGoodListFinishedGoods`, `finishedGoodCreateFinishedGood`, `finishedGoodGetFinishedGood`, `finishedGoodUpdateFinishedGood`, `finishedGoodListBatches`, `finishedGoodRegisterBatch`, `finishedGoodGetStockSummary`, `finishedGoodGetLowStockItems`
- Loading state: master/detail skeletons; no-stock empty views; batch registration action feedback.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: FG columns: productCode, productName, category, batchCount, onHandQty, availableQty, valuation.
- Suggested form fields: From finished-good create/update requests and batch registration request schemas.
- Role/permission gate: Mixed by endpoint: read list/stock-summary includes accounting; create/update/register-batch require `ROLE_ADMIN|ROLE_FACTORY`; low-stock + batch-list reads exclude accounting.

### `/accounting/inventory/adjustments-costing`
- Purpose: Inventory adjustments, landed cost, revaluation, WIP adjustments with accounting impact.
- Required API calls: `inventoryAdjustmentListAdjustments`, `inventoryAdjustmentCreateAdjustment`, `acctRecordLandedCost`, `acctRevalueInventory`, `acctAdjustWip`
- Loading state: high-risk action forms with simulation preview; posting-lock loader; strict confirm modal.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: Adjustment columns: adjustmentNo, date, reason, totalValueImpact, postedBy, postedAt.
- Suggested form fields: From `InventoryAdjustmentRequest`, `InventoryRevaluationRequest`, `WipAdjustmentRequest`.
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING` (exact backend)

### `/accounting/hr/employees-attendance`
- Purpose: Employee master, attendance capture, leave approvals for payroll readiness.
- Required API calls: `hrEmployees`, `hrCreateEmployee`, `hrUpdateEmployee`, `hrDeleteEmployee`, `hrAttendanceToday`, `hrAttendanceByDate`, `hrMarkAttendance`, `hrBulkMarkAttendance`, `hrLeaveRequests`, `hrCreateLeaveRequest`, `hrUpdateLeaveStatus`
- Loading state: attendance day-sheet loading; empty-day state; bulk update progress + partial-failure report.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: Employee columns from `EmployeeDto`: employeeCode, name, department, status, joiningDate, payBasis.
- Suggested form fields: From `EmployeeRequest`, attendance mark request, leave request/update status payloads.
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING` (exact backend)

### `/accounting/hr/payroll-runs`
- Purpose: Payroll run lifecycle: create, calculate, approve, post, mark paid, batch payments.
- Required API calls: `payrollListPayrollRuns`, `payrollCreatePayrollRun`, `payrollGetPayrollRun`, `payrollGetPayrollRunLines`, `payrollCalculatePayroll`, `payrollApprovePayroll`, `payrollPostPayroll`, `payrollMarkAsPaid`, `payrollGetCurrentMonthPaySummary`, `payrollGetCurrentWeekPaySummary`, `acctRecordPayrollPayment`, `payrollBatchProcessBatchPayment`
- Loading state: run pipeline stepper with state badges; lock UI during calculation/posting; reconciliation banner on payment mismatch.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: Run columns from `PayrollRunDto`: runId, period, frequency, status, gross, deductions, net, postedFlag, paidFlag.
- Suggested form fields: From `PayrollRunRequest`, `PayrollPaymentRequest`, `PayrollBatchPaymentRequest`.
- Role/permission gate: `ROLE_ADMIN or ROLE_ACCOUNTING` (exact backend)

### `/accounting/reports/financial`
- Purpose: Trial balance, P&L, balance sheet, cash flow, inventory valuation/reconciliation, aged debtors, wastage.
- Required API calls: `reportTrialBalance`, `acctGetTrialBalanceAsOf`, `reportProfitLoss`, `reportBalanceSheet`, `reportCashFlow`, `reportInventoryValuation`, `reportInventoryReconciliation`, `reportAgedDebtors`, `reportWastageReport`, `reportReconciliationDashboard`, `acctGenerateGstReturn`
- Admin-only legacy exports (do not treat as required for this route): `acctAuditDigest`, `acctAuditDigestCsv`
- Audit-trail route dependency: use `/accounting/audit-trail` with `acctAuditTransactions` and `acctAuditTransactionDetail` for new transaction-audit UX.
- Loading state: report loader with parameter panel; no-data period state; export/download progress + failure details.
- Empty state: no rows / no open items / no period data for selected filters.
- Error state: inline widget errors + page-level retry + action-level toast; preserve user filters and unsaved inputs.
- Suggested table columns: Report dependent; enforce standard grid controls: sticky totals row, drill-through links, export columns metadata.
- Suggested form fields: Filter controls: fromDate, toDate, asOfDate, dealer/supplier/account selectors, grouping granularity.
- Role/permission gate: Mixed by endpoint: financial reports and GST return use `ROLE_ADMIN|ROLE_ACCOUNTING`; deprecated digest exports are `ROLE_ADMIN` only.

## Accountant-Grade UX/Logic Controls (Must-Have)

- Enforce posting invariants in UI: show debit total, credit total, and block submit unless balanced before creating journals.
- Treat period status as first-class state: disable posting/edit actions when period is closed/locked, with explicit reason banners.
- Use irreversible action confirmations for: period close/lock/reopen, journal reverse/cascade reverse, payroll post/mark-paid, inventory revaluation, WIP adjustment.
- Keep auditability visible: show source document reference, posting user, posting timestamp, and reversal links in detail drawers.
- For settlement/payment screens, show unapplied balance and allocation residuals before submit.
- For reports, preserve filter context and export exactly the filtered dataset to avoid reconciliation mismatches.
- Treat opening stock import as migration-grade: require explicit import confirmation, force stable idempotency key entry, surface row-level error outcomes, and prompt immediate post-import reconciliation (inventory valuation vs journal postings).

## Delta Update (2026-02-13): Costing + Transaction Audit Trail + Approval Flows

### New Accounting Audit Trail APIs (Must Add In FE)

- `GET /api/v1/accounting/audit/transactions`
  - Query params: `from`, `to`, `module`, `status`, `reference`, `page` (default `0`), `size` (default `50`).
  - Returns paged `AccountingTransactionAuditListItemDto` rows.
- `GET /api/v1/accounting/audit/transactions/{journalEntryId}`
  - Returns `AccountingTransactionAuditDetailDto` with linked documents, settlement allocations, and event trail.
- Legacy digest endpoints remain admin-only and are deprecated; keep them out of new accountant-owned routes:
  - `GET /api/v1/accounting/audit/digest`
  - `GET /api/v1/accounting/audit/digest.csv`

Implementation note:
- `/api/v1/accounting/audit/transactions*` is code-verified from `AccountingController`; if OpenAPI snapshot is stale, treat backend controller contract as authoritative until snapshot refresh.

Frontend function-name additions:
- `acctAuditTransactions` -> `GET /api/v1/accounting/audit/transactions`
- `acctAuditTransactionDetail` -> `GET /api/v1/accounting/audit/transactions/{journalEntryId}`

Suggested route:
- `/accounting/audit-trail`

List columns (from `AccountingTransactionAuditListItemDto`):
- `journalEntryId`, `referenceNumber`, `entryDate`, `status`, `module`, `transactionType`,
- `dealerName`, `supplierName`, `totalDebit`, `totalCredit`, `consistencyStatus`, `postedAt`.

Detail sections (from `AccountingTransactionAuditDetailDto`):
- Header: journal/period/reversal/correction metadata.
- `lines[]`: account code, debit/credit, description.
- `linkedDocuments[]`: invoice/purchase/settlement-linked docs.
- `settlementAllocations[]`: allocation + discount/writeoff/fx + idempotency key.
- `eventTrail[]`: accounting-event timeline (event type, sequence, account, amounts, correlation id).

### Costing Visibility Checklist (Keep These Prominent In FE)

Costing views/actions must stay visible in accounting portal:
- `POST /api/v1/accounting/inventory/landed-cost`
- `POST /api/v1/accounting/inventory/revaluation`
- `POST /api/v1/accounting/inventory/wip-adjustment`
- `GET /api/v1/reports/monthly-production-costs`
- `GET /api/v1/reports/production-logs/{id}/cost-breakdown`
- `GET /api/v1/reports/inventory-valuation`
- `GET /api/v1/reports/inventory-reconciliation`

UX rule:
- Any costing report/action with resulting journal reference must support drill-through into `/accounting/audit-trail`.

### Approval Flow Contract For Accounting Approvers

Tenant-scoped accounting and admin users can read the approval queue via:
- Queue: `GET /api/v1/admin/approvals`
- Action endpoints only when the queue row actually emits them:
  - `approveEndpoint`
  - `rejectEndpoint` (nullable for payroll)

Queue payload fields to render directly:
- `originType`, `ownerType`, `reference`, `status`, `summary`,
- `actionType`, `actionLabel`,
- `approveEndpoint`, `rejectEndpoint`, `createdAt`.

Export approval rows additionally expose:
- `reportType` for all accounting inbox viewers
- `actionType`, `actionLabel`, `approveEndpoint`, and `rejectEndpoint` as `null` for accounting-only viewers
- redacted `parameters`, `requesterUserId`, and `requesterEmail` for accounting-only viewers
- full `parameters`, `requesterUserId`, and `requesterEmail` only when the same queue is rendered for tenant admin users

Frontend rule:
- For export approval rows shown to accounting users, treat the row as inbox-only. Do not synthesize approve/reject controls when the action fields are `null`.

Action semantics:
- Credit request approvals: `/api/v1/sales/credit-requests/{id}/approve|reject`
- Dispatch override approvals: `/api/v1/credit/override-requests/{id}/approve|reject`
- Payroll approvals: `/api/v1/payroll/runs/{id}/approve` (no reject endpoint in queue payload)
- Period close approvals: `/api/v1/accounting/periods/{id}/approve-close|reject-close`; this tenant-scoped queue is readable by `ROLE_ADMIN|ROLE_ACCOUNTING`, the controller currently allows `ROLE_ADMIN` only for approve/reject, and platform `ROLE_SUPER_ADMIN` remains blocked from `/api/v1/admin/approvals` by `CompanyContextFilter`. Keep the UX tied to the approval-queue payload and maker-checker boundary, and do not surface checker actions to accounting-only users.
