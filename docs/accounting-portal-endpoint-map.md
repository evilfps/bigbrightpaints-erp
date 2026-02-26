# Accounting Portal Endpoint Map (OpenAPI-Driven)

Scope includes accounting core, invoices, purchasing/payables, inventory/costing, HR/payroll, and reports.

## Scope Guardrail (Do Not Remove)

HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.

Reference guardrail: `docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md`

Total scoped endpoints: **143**
Count lock for parity checks: **143**

## M18-S9A Parity Closure (Endpoint Map vs Handoff)

- M17-S1 canonical API contract source-of-truth is `openapi.json`; guard behavior is non-mutating (parity checks validate and fail on drift, but do not rewrite docs).
- M17-S2 parity baseline for this slice is a curated lock of **143** unique `METHOD /api/v1/...` rows.
- The `143` lock is a curated frontend parity baseline and does not claim full accounting-portal OpenAPI coverage.
- The handoff inventory may only add **9** non-owned dependencies on top of these 143 rows:
  - Shared foundation APIs (7): `GET /api/v1/auth/me`, `GET /api/v1/auth/profile`, `PUT /api/v1/auth/profile`, `POST /api/v1/auth/password/change`, `GET /api/v1/companies`, `POST /api/v1/multi-company/companies/switch`, `POST /api/v1/auth/logout`
  - Dealer support APIs (2): `GET /api/v1/sales/dealers`, `GET /api/v1/sales/dealers/search`
- Explicit outside-lock ledger (present in `docs/endpoint-inventory.md` and `openapi.json`):
  - `GET /api/v1/accounting/audit/transactions`
  - `GET /api/v1/accounting/audit/transactions/{journalEntryId}`
  - `GET /api/v1/accounting/date-context`
- Legacy `GET /api/v1/accounting/audit/digest*` endpoints are still present in this OpenAPI snapshot and should be treated as deprecated in new frontend flows.

## TKT-ERP-STAGE-111 Superadmin/Auth Addendum (Outside 143 Lock)

- This addendum is an explicit contract supplement for superadmin UX and control-plane flows. It is outside the curated 143 accounting-portal parity lock.
- RBAC hierarchy in backend method security is explicit: `ROLE_SUPER_ADMIN > ROLE_ADMIN` (superadmin inherits admin-guarded endpoints; superadmin-only guards still require `ROLE_SUPER_ADMIN`).

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `POST /api/v1/auth/password/forgot/superadmin` | forgot-password form; req: one identity field in body; opt: correlation id headers; states: loading, success, generic-success | body accepts aliases `email`, `userid`, `userId`; no tenant header required; anti-enumeration semantics (generic outcome for unknown/non-superadmin users and masked dispatch failures) | ok 200; message=`If the email exists, a reset link has been sent` |
| `POST /api/v1/auth/password/reset` | reset-form; req: token (body), newPassword (body), confirmPassword (body); states: loading, error, success | token scope follows `GLOBAL_IDENTITY` policy (one account identity across memberships in multi-company model, not tenant-scoped) | ok 200; err 400/401-style validation/auth failures via API error contract |
| `POST /api/v1/companies` | tenant-bootstrap form; req: name (body), code (body), timezone (body); opt: defaultGstRate (body), firstAdminEmail (body), firstAdminDisplayName (body), quota fields | `ROLE_SUPER_ADMIN` only; minimal payload allowed; omitted `defaultGstRate` falls back to `18`; explicit `defaultGstRate: 0` is preserved | ok 200 (`Company created`); err 403 when caller lacks superadmin authority |
| `POST /api/v1/companies/{id}/lifecycle-state` | tenant-lifecycle action; req: id (path), state (body), reason (body); states: loading, error, success | `ROLE_SUPER_ADMIN` only; lifecycle state change is audit-evidenced | ok 200; err 400/403 |
| `PUT /api/v1/companies/{id}/tenant-runtime/policy` | tenant-runtime policy form; req: id (path); opt: holdState/reasonCode/max* controls; states: loading, error, success | `ROLE_SUPER_ADMIN` only; applies runtime hold/limits contract for target tenant | ok 200; err 400/403 |
| `GET /api/v1/companies/{id}/tenant-metrics` | tenant-metrics dashboard read; req: id (path); states: loading, error, success | `ROLE_SUPER_ADMIN` only; telemetry is tenant-targeted control-plane data | ok 200; err 403 |
| `POST /api/v1/companies/{id}/support/admin-password-reset` | support action form; req: id (path), adminEmail (body); states: loading, error, success | `ROLE_SUPER_ADMIN` only; resets tenant-admin credentials and enforces must-change-password on next login | ok 200 (`Admin credentials reset and emailed`); err 400/403 |

## Accounting Core (GL, Periods, Journals, Controls)

### accounting-controller (52)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/accounting/accounts` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/accounts` | create-form; req: code (body), name (body), type (body); opt: parentId (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/accounts/tree` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/accounts/tree/{type}` | detail-view; req: type (path); opt: -; states: loading, error, success, empty | path=type; query=-; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/accounts/{accountId}/activity` | detail-view; req: accountId (path), endDate (query), startDate (query); opt: -; states: loading, error, success, empty | path=accountId; query=startDate, endDate; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/accounts/{accountId}/balance/as-of` | detail-view; req: accountId (path), date (query); opt: -; states: loading, error, success, empty | path=accountId; query=date; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/accounts/{accountId}/balance/compare` | detail-view; req: accountId (path), date1 (query), date2 (query); opt: -; states: loading, error, success, empty | path=accountId; query=date1, date2; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/accruals` | create-form; req: amount (body), creditAccountId (body), debitAccountId (body); opt: adminOverride (body), autoReverseDate (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/aging/dealers/{dealerId}` | detail-view; req: dealerId (path); opt: asOf (query), buckets (query); states: loading, error, success, empty | path=dealerId; query=asOf, buckets; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/aging/dealers/{dealerId}/pdf` | detail-view; req: dealerId (path); opt: asOf (query), buckets (query); states: loading, error, success, empty | path=dealerId; query=asOf, buckets; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/aging/suppliers/{supplierId}` | detail-view; req: supplierId (path); opt: asOf (query), buckets (query); states: loading, error, success, empty | path=supplierId; query=asOf, buckets; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/aging/suppliers/{supplierId}/pdf` | detail-view; req: supplierId (path); opt: asOf (query), buckets (query); states: loading, error, success, empty | path=supplierId; query=asOf, buckets; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/audit/digest` | list-view; req: -; opt: from (query), to (query); states: loading, error, success, empty | path=-; query=from, to; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/audit/digest.csv` | list-view; req: -; opt: from (query), to (query); states: loading, error, success, empty | path=-; query=from, to; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/bad-debts/write-off` | create-form; req: amount (body), expenseAccountId (body), invoiceId (body); opt: adminOverride (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/credit-notes` | create-form; req: invoiceId (body); opt: adminOverride (body), amount (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/debit-notes` | create-form; req: purchaseId (body); opt: adminOverride (body), amount (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/default-accounts` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err 400 |
| `PUT /api/v1/accounting/default-accounts` | edit-form; req: -; opt: cogsAccountId (body), discountAccountId (body), inventoryAccountId (body), revenueAccountId (body), taxAccountId (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/gst/return` | list-view; req: -; opt: period (query); states: loading, error, success, empty | path=-; query=period; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/inventory/landed-cost` | create-form; req: amount (body), inventoryAccountId (body), offsetAccountId (body), rawMaterialPurchaseId (body); opt: adminOverride (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/inventory/revaluation` | create-form; req: deltaAmount (body), inventoryAccountId (body), revaluationAccountId (body); opt: adminOverride (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/inventory/wip-adjustment` | create-form; req: amount (body), direction (body), inventoryAccountId (body), productionLogId (body), wipAccountId (body); opt: adminOverride (body), entryDate (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/journal-entries` | list-view; req: -; opt: dealerId (query), page (query), size (query), supplierId (query); states: loading, error, success, empty | path=-; query=dealerId, supplierId, page, size; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/journal-entries` | create-form; req: entryDate (body), lines (body), lines[].accountId (body), lines[].credit (body), lines[].debit (body); opt: adminOverride (body), currency (body), dealerId (body), fxRate (body), lines[].description (body), lines[].foreignCurrencyAmount (body), memo (body), referenceNumber (body), supplierId (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/journal-entries/{entryId}/cascade-reverse` | create-form; req: entryId (path); opt: adminOverride (body), approvedBy (body), cascadeRelatedEntries (body), effectivePercentage (body), memo (body), partialReversal (body), reason (body), reasonCode (body), relatedEntryIds (body), reversalDate (body), reversalPercentage (body), supportingDocumentRef (body), voidOnly (body); states: loading, error, success | path=entryId; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/journal-entries/{entryId}/reverse` | create-form; req: entryId (path); opt: adminOverride (body), approvedBy (body), cascadeRelatedEntries (body), effectivePercentage (body), memo (body), partialReversal (body), reason (body), reasonCode (body), relatedEntryIds (body), reversalDate (body), reversalPercentage (body), supportingDocumentRef (body), voidOnly (body); states: loading, error, success | path=entryId; query=-; body=optional; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/month-end/checklist` | list-view; req: -; opt: periodId (query); states: loading, error, success, empty | path=-; query=periodId; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/month-end/checklist/{periodId}` | create-form; req: periodId (path); opt: bankReconciled (body), inventoryCounted (body), note (body); states: loading, error, success | path=periodId; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/payroll/payments` | create-form; req: amount (body), cashAccountId (body), expenseAccountId (body), payrollRunId (body); opt: memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/periods` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/periods/{periodId}/close` | create-form; req: periodId (path); opt: force (body), note (body); states: loading, error, success | path=periodId; query=-; body=optional; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/periods/{periodId}/lock` | create-form; req: periodId (path); opt: reason (body); states: loading, error, success | path=periodId; query=-; body=optional; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/periods/{periodId}/reopen` | create-form; req: periodId (path); opt: reason (body); states: loading, error, success | path=periodId; query=-; body=optional; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/receipts/dealer` | create-form; req: allocations (body), allocations[].appliedAmount (body), amount (body), cashAccountId (body), dealerId (body); opt: Idempotency-Key (header), allocations[].discountAmount (body), allocations[].fxAdjustment (body), allocations[].invoiceId (body), allocations[].memo (body), allocations[].purchaseId (body), allocations[].writeOffAmount (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/receipts/dealer/hybrid` | create-form; req: dealerId (body), incomingLines (body), incomingLines[].accountId (body), incomingLines[].amount (body); opt: Idempotency-Key (header), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/reports/aging/dealer/{dealerId}` | detail-view; req: dealerId (path); opt: -; states: loading, error, success, empty | path=dealerId; query=-; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/reports/aging/dealer/{dealerId}/detailed` | detail-view; req: dealerId (path); opt: -; states: loading, error, success, empty | path=dealerId; query=-; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/reports/aging/receivables` | list-view; req: -; opt: asOfDate (query); states: loading, error, success, empty | path=-; query=asOfDate; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/reports/balance-sheet/hierarchy` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/reports/dso/dealer/{dealerId}` | detail-view; req: dealerId (path); opt: -; states: loading, error, success, empty | path=dealerId; query=-; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/reports/income-statement/hierarchy` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/sales/returns` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/sales/returns` | create-form; req: invoiceId (body), lines (body), lines[].invoiceLineId (body), lines[].quantity (body), reason (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/settlements/dealers` | create-form; req: allocations (body), allocations[].appliedAmount (body), dealerId (body), payments[].accountId (body), payments[].amount (body); opt: adminOverride (body), allocations[].discountAmount (body), allocations[].fxAdjustment (body), allocations[].invoiceId (body), allocations[].memo (body), allocations[].purchaseId (body), allocations[].writeOffAmount (body), cashAccountId (body), discountAccountId (body), fxGainAccountId (body), fxLossAccountId (body), idempotencyKey (body), memo (body), payments (body), payments[].method (body), referenceNumber (body), settlementDate (body), writeOffAccountId (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `POST /api/v1/accounting/settlements/suppliers` | create-form; req: allocations (body), allocations[].appliedAmount (body), cashAccountId (body), supplierId (body); opt: Idempotency-Key (header), adminOverride (body), allocations[].discountAmount (body), allocations[].fxAdjustment (body), allocations[].invoiceId (body), allocations[].memo (body), allocations[].purchaseId (body), allocations[].writeOffAmount (body), discountAccountId (body), fxGainAccountId (body), fxLossAccountId (body), idempotencyKey (body), memo (body), referenceNumber (body), settlementDate (body), writeOffAccountId (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/statements/dealers/{dealerId}` | detail-view; req: dealerId (path); opt: from (query), to (query); states: loading, error, success, empty | path=dealerId; query=from, to; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/statements/dealers/{dealerId}/pdf` | detail-view; req: dealerId (path); opt: from (query), to (query); states: loading, error, success, empty | path=dealerId; query=from, to; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/statements/suppliers/{supplierId}` | detail-view; req: supplierId (path); opt: from (query), to (query); states: loading, error, success, empty | path=supplierId; query=from, to; body=none; ct=- | ok 200; err 400 |
| `GET /api/v1/accounting/statements/suppliers/{supplierId}/pdf` | detail-view; req: supplierId (path); opt: from (query), to (query); states: loading, error, success, empty | path=supplierId; query=from, to; body=none; ct=- | ok 200; err 400 |
| `POST /api/v1/accounting/suppliers/payments` | create-form; req: allocations (body), allocations[].appliedAmount (body), amount (body), cashAccountId (body), supplierId (body); opt: Idempotency-Key (header), allocations[].discountAmount (body), allocations[].fxAdjustment (body), allocations[].invoiceId (body), allocations[].memo (body), allocations[].purchaseId (body), allocations[].writeOffAmount (body), idempotencyKey (body), memo (body), referenceNumber (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err 400 |
| `GET /api/v1/accounting/trial-balance/as-of` | list-view; req: date (query); opt: -; states: loading, error, success, empty | path=-; query=date; body=none; ct=- | ok 200; err 400 |

### accounting-catalog-controller (5)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `POST /api/v1/accounting/catalog/import` | create-form; req: file (multipart body); opt: Idempotency-Key (header); states: loading, error, success | path=-; query=-; body=required (multipart/form-data with `file` part); ct=multipart/form-data; file-part content-type allowlist=`text/csv`,`application/csv`,`application/vnd.ms-excel`; fallback=missing file-part content-type accepted only when filename ends with `.csv` | ok 200; err 400, 409 (`CONC_001`), 422 (`FILE_003`) |
| `GET /api/v1/accounting/catalog/products` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/accounting/catalog/products` | create-form; req: category (body), productName (body); opt: basePrice (body), brandCode (body), brandId (body), brandName (body), customSkuCode (body), defaultColour (body), gstRate (body), metadata (body), minDiscountPercent (body), minSellingPrice (body), sizeLabel (body), unitOfMeasure (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/accounting/catalog/products/bulk-variants` | create-form; req: baseProductName (body), category (body), colors (body), sizes (body); opt: basePrice (body), brandCode (body), brandId (body), brandName (body), dryRun (query), gstRate (body), metadata (body), minDiscountPercent (body), minSellingPrice (body), skuPrefix (body), unitOfMeasure (body); states: loading, error, success | path=-; query=dryRun; body=required; ct=application/json | ok 200 (`generated`,`conflicts`,`wouldCreate`,`created`); err 409 (`CONC_001`) |
| `PUT /api/v1/accounting/catalog/products/{id}` | edit-form; req: id (path); opt: basePrice (body), category (body), defaultColour (body), gstRate (body), metadata (body), minDiscountPercent (body), minSellingPrice (body), productName (body), sizeLabel (body), unitOfMeasure (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |

### accounting-configuration-controller (1)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/accounting/configuration/health` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |

## Invoice & Receivables

### invoice-controller (5)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/invoices` | list-view; req: -; opt: page (query), size (query); states: loading, error, success, empty | path=-; query=page, size; body=none; ct=- | ok 200; err - |
| `GET /api/v1/invoices/dealers/{dealerId}` | detail-view; req: dealerId (path); opt: page (query), size (query); states: loading, error, success, empty | path=dealerId; query=page, size; body=none; ct=- | ok 200; err - |
| `GET /api/v1/invoices/{id}` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/invoices/{id}/email` | create-form; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/invoices/{id}/pdf` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |

## Purchasing & Payables

### purchasing-workflow-controller (6)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/purchasing/goods-receipts` | list-view; req: -; opt: supplierId (query); states: loading, error, success, empty | path=-; query=supplierId; body=none; ct=- | ok 200; err - |
| `POST /api/v1/purchasing/goods-receipts` | create-form; req: lines (body), lines[].costPerUnit (body), lines[].quantity (body), lines[].rawMaterialId (body), purchaseOrderId (body), receiptDate (body), receiptNumber (body); opt: Idempotency-Key (header), idempotencyKey (body), lines[].batchCode (body), lines[].notes (body), lines[].unit (body), memo (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/purchasing/goods-receipts/{id}` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/purchasing/purchase-orders` | list-view; req: -; opt: supplierId (query); states: loading, error, success, empty | path=-; query=supplierId; body=none; ct=- | ok 200; err - |
| `POST /api/v1/purchasing/purchase-orders` | create-form; req: lines (body), lines[].costPerUnit (body), lines[].quantity (body), lines[].rawMaterialId (body), orderDate (body), orderNumber (body), supplierId (body); opt: lines[].notes (body), lines[].unit (body), memo (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/purchasing/purchase-orders/{id}` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |

### raw-material-purchase-controller (4)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/purchasing/raw-material-purchases` | list-view; req: -; opt: supplierId (query); states: loading, error, success, empty | path=-; query=supplierId; body=none; ct=- | ok 200; err - |
| `POST /api/v1/purchasing/raw-material-purchases` | create-form; req: goodsReceiptId (body), invoiceDate (body), invoiceNumber (body), lines (body), lines[].costPerUnit (body), lines[].quantity (body), lines[].rawMaterialId (body), supplierId (body); opt: lines[].batchCode (body), lines[].notes (body), lines[].taxInclusive (body), lines[].taxRate (body), lines[].unit (body), memo (body), purchaseOrderId (body), taxAmount (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/purchasing/raw-material-purchases/returns` | create-form; req: purchaseId (body), quantity (body), rawMaterialId (body), supplierId (body), unitCost (body); opt: reason (body), referenceNumber (body), returnDate (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/purchasing/raw-material-purchases/{id}` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |

### supplier-controller (4)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/suppliers` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/suppliers` | create-form; req: name (body); opt: address (body), code (body), contactEmail (body), contactPhone (body), creditLimit (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/suppliers/{id}` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |
| `PUT /api/v1/suppliers/{id}` | edit-form; req: id (path), name (body); opt: address (body), code (body), contactEmail (body), contactPhone (body), creditLimit (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |

## Inventory & Costing

### raw-material-controller (10)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/accounting/raw-materials` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/accounting/raw-materials` | create-form; req: maxStock (body), minStock (body), name (body), reorderLevel (body), unitType (body); opt: costingMethod (body), inventoryAccountId (body), sku (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `DELETE /api/v1/accounting/raw-materials/{id}` | delete-action; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `PUT /api/v1/accounting/raw-materials/{id}` | edit-form; req: id (path), maxStock (body), minStock (body), name (body), reorderLevel (body), unitType (body); opt: costingMethod (body), inventoryAccountId (body), sku (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/raw-material-batches/{rawMaterialId}` | detail-view; req: rawMaterialId (path); opt: -; states: loading, error, success, empty | path=rawMaterialId; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/raw-material-batches/{rawMaterialId}` | create-form; req: costPerUnit (body), quantity (body), rawMaterialId (path), supplierId (body), unit (body); opt: Idempotency-Key (header), batchCode (body), notes (body); states: loading, error, success | path=rawMaterialId; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/raw-materials/intake` | create-form; req: costPerUnit (body), quantity (body), rawMaterialId (body), supplierId (body), unit (body); opt: Idempotency-Key (header), batchCode (body), notes (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/raw-materials/stock` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/raw-materials/stock/inventory` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/raw-materials/stock/low-stock` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |

### finished-good-controller (8)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/finished-goods` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/finished-goods` | create-form; req: name (body), productCode (body); opt: cogsAccountId (body), costingMethod (body), discountAccountId (body), revenueAccountId (body), taxAccountId (body), unit (body), valuationAccountId (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/finished-goods/low-stock` | list-view; req: -; opt: threshold (query); states: loading, error, success, empty | path=-; query=threshold; body=none; ct=- | ok 200; err - |
| `GET /api/v1/finished-goods/stock-summary` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/finished-goods/{id}` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |
| `PUT /api/v1/finished-goods/{id}` | edit-form; req: id (path), name (body), productCode (body); opt: cogsAccountId (body), costingMethod (body), discountAccountId (body), revenueAccountId (body), taxAccountId (body), unit (body), valuationAccountId (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/finished-goods/{id}/batches` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/finished-goods/{id}/batches` | create-form; req: finishedGoodId (body), id (path), quantity (body), unitCost (body); opt: batchCode (body), expiryDate (body), manufacturedAt (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |

### inventory-adjustment-controller (2)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/inventory/adjustments` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/inventory/adjustments` | create-form; req: adjustmentAccountId (body), lines (body), lines[].finishedGoodId (body), lines[].quantity (body), lines[].unitCost (body), type (body); opt: Idempotency-Key (header), adjustmentDate (body), adminOverride (body), idempotencyKey (body), lines[].note (body), reason (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |

### opening-stock-import-controller (1)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `POST /api/v1/inventory/opening-stock` | create-form; req: file (multipart body); opt: Idempotency-Key (header); states: loading, error, success, partial-success | path=-; query=-; body=required (multipart/form-data with `file`); ct=multipart/form-data; accounting side-effect=posts opening-stock journal (inventory Dr / `OPEN-BAL` Cr) | ok 200; err - |

## HR & Payroll

### hr-controller (15)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `POST /api/v1/hr/attendance/bulk-mark` | create-form; req: date (body), employeeIds (body), status (body); opt: checkInTime (body), checkInTime.hour (body), checkInTime.minute (body), checkInTime.nano (body), checkInTime.second (body), checkOutTime (body), checkOutTime.hour (body), checkOutTime.minute (body), checkOutTime.nano (body), checkOutTime.second (body), overtimeHours (body), regularHours (body), remarks (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/hr/attendance/date/{date}` | detail-view; req: date (path); opt: -; states: loading, error, success, empty | path=date; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/hr/attendance/employee/{employeeId}` | detail-view; req: employeeId (path), endDate (query), startDate (query); opt: -; states: loading, error, success, empty | path=employeeId; query=startDate, endDate; body=none; ct=- | ok 200; err - |
| `POST /api/v1/hr/attendance/mark/{employeeId}` | create-form; req: employeeId (path), status (body); opt: checkInTime (body), checkInTime.hour (body), checkInTime.minute (body), checkInTime.nano (body), checkInTime.second (body), checkOutTime (body), checkOutTime.hour (body), checkOutTime.minute (body), checkOutTime.nano (body), checkOutTime.second (body), date (body), doubleOvertimeHours (body), holiday (body), overtimeHours (body), regularHours (body), remarks (body), weekend (body); states: loading, error, success | path=employeeId; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/hr/attendance/summary` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/hr/attendance/today` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/hr/employees` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/hr/employees` | create-form; req: email (body), firstName (body), lastName (body); opt: bankAccountNumber (body), bankName (body), dailyWage (body), doubleOtRateMultiplier (body), employeeType (body), hiredDate (body), ifscCode (body), monthlySalary (body), overtimeRateMultiplier (body), paymentSchedule (body), phone (body), role (body), standardHoursPerDay (body), weeklyOffDays (body), workingDaysPerMonth (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `DELETE /api/v1/hr/employees/{id}` | delete-action; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `PUT /api/v1/hr/employees/{id}` | edit-form; req: email (body), firstName (body), id (path), lastName (body); opt: bankAccountNumber (body), bankName (body), dailyWage (body), doubleOtRateMultiplier (body), employeeType (body), hiredDate (body), ifscCode (body), monthlySalary (body), overtimeRateMultiplier (body), paymentSchedule (body), phone (body), role (body), standardHoursPerDay (body), weeklyOffDays (body), workingDaysPerMonth (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/hr/leave-requests` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/hr/leave-requests` | create-form; req: endDate (body), leaveType (body), startDate (body); opt: employeeId (body), reason (body), status (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `PATCH /api/v1/hr/leave-requests/{id}/status` | edit-form; req: id (path); opt: status (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/hr/payroll-runs` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/hr/payroll-runs` | create-form; req: creditAccountId (body), debitAccountId (body), initiatedBy (body), payrollDate (body), postingAmount (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |

### hr-payroll-controller (16)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/payroll/runs` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/payroll/runs` | create-form; req: -; opt: periodEnd (body), periodStart (body), remarks (body), runType (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/payroll/runs/monthly` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/payroll/runs/monthly` | create-form; req: month (query), year (query); opt: -; states: loading, error, success | path=-; query=year, month; body=none; ct=- | ok 200; err - |
| `GET /api/v1/payroll/runs/weekly` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/payroll/runs/weekly` | create-form; req: weekEndingDate (query); opt: -; states: loading, error, success | path=-; query=weekEndingDate; body=none; ct=- | ok 200; err - |
| `GET /api/v1/payroll/runs/{id}` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/payroll/runs/{id}/approve` | create-form; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/payroll/runs/{id}/calculate` | create-form; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/payroll/runs/{id}/lines` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/payroll/runs/{id}/mark-paid` | create-form; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/payroll/runs/{id}/post` | create-form; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/payroll/summary/current-month` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/payroll/summary/current-week` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/payroll/summary/monthly` | list-view; req: month (query), year (query); opt: -; states: loading, error, success, empty | path=-; query=year, month; body=none; ct=- | ok 200; err - |
| `GET /api/v1/payroll/summary/weekly` | list-view; req: weekEndingDate (query); opt: -; states: loading, error, success, empty | path=-; query=weekEndingDate; body=none; ct=- | ok 200; err - |

### payroll-controller (1)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `POST /api/v1/accounting/payroll/payments/batch` | create-form; req: cashAccountId (body), expenseAccountId (body), lines (body), lines[].dailyWage (body), lines[].days (body), lines[].name (body), runDate (body); opt: defaultPfRate (body), defaultTaxRate (body), employerPfExpenseAccountId (body), employerPfRate (body), employerTaxExpenseAccountId (body), employerTaxRate (body), lines[].advances (body), lines[].notes (body), lines[].pfWithholding (body), lines[].taxWithholding (body), memo (body), pfPayableAccountId (body), referenceNumber (body), taxPayableAccountId (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |

## Reports & Reconciliation

### report-controller (13)

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/accounting/reports/aged-debtors` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/account-statement` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/balance-sheet` | list-view; req: -; opt: date (query); states: loading, error, success, empty | path=-; query=date; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/balance-warnings` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/cash-flow` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/inventory-reconciliation` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/inventory-valuation` | list-view; req: -; opt: date (query); states: loading, error, success, empty | path=-; query=date; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/monthly-production-costs` | list-view; req: month (query), year (query); opt: -; states: loading, error, success, empty | path=-; query=year, month; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/production-logs/{id}/cost-breakdown` | detail-view; req: id (path); opt: -; states: loading, error, success, empty | path=id; query=-; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/profit-loss` | list-view; req: -; opt: date (query); states: loading, error, success, empty | path=-; query=date; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/reconciliation-dashboard` | list-view; req: bankAccountId (query); opt: statementBalance (query); states: loading, error, success, empty | path=-; query=bankAccountId, statementBalance; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/trial-balance` | list-view; req: -; opt: date (query); states: loading, error, success, empty | path=-; query=date; body=none; ct=- | ok 200; err - |
| `GET /api/v1/reports/wastage` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |

## Key Accounting Risks From Spec

- Many endpoints only define `200` responses and do not provide typed `4xx/5xx` contracts.
- Security requirements are often `unspecified` in OpenAPI; enforce RBAC in frontend via `auth/me` permission claims and backend policy.
- Workflow actions that should be safely repeatable (`close/lock/reopen/approve/post`) are exposed as `POST`; idempotency guarantees are not explicit.
- `POST /api/v1/inventory/opening-stock` defines only `200`; partial-row errors and idempotency-conflict behavior are runtime/business errors not strongly typed in OpenAPI.
- `POST /api/v1/inventory/opening-stock` accepts optional `Idempotency-Key`; backend normalizes missing key to file hash, so frontend should still send a stable key per import job.
- Accounting and reporting endpoints are split across `/api/v1/accounting/reports/*` and `/api/v1/reports/*` namespaces.
