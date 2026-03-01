# Frontend Handoff

API contracts, flow documentation, and design guidance for frontend developers.

**What belongs here:** Endpoint maps per module, request/response schemas, user flow descriptions, state machines, error codes, and UI hints.
**Updated by:** Backend workers after implementing/refactoring each module.

---

## Documentation Format Per Module

Each module section should include:
1. **Endpoint Map** - All REST endpoints with HTTP method, path, auth requirements, request/response types
2. **User Flows** - Step-by-step flows a frontend would implement (e.g., "Create Sales Order" flow with all API calls in sequence)
3. **State Machines** - Entity lifecycle states and valid transitions (e.g., Order: Draft -> Confirmed -> Dispatched -> Invoiced)
4. **Error Codes** - Module-specific error codes the frontend should handle with suggested UX behavior
5. **Data Contracts** - Key request/response DTOs with field descriptions and validation rules
6. **UI Hints** - Suggested form fields, required vs optional, dropdowns vs free text, dependent fields

---

## Modules (populated by workers as features complete)

### Auth
_To be documented_

### Tenant & Admin

#### Endpoint Map (SUPER_ADMIN only)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/superadmin/dashboard` | `ROLE_SUPER_ADMIN` | None | `SuperAdminDashboardDto` |
| GET | `/api/v1/superadmin/tenants` | `ROLE_SUPER_ADMIN` | Optional query: `status=ACTIVE|SUSPENDED|DEACTIVATED` | `List<SuperAdminTenantDto>` |
| POST | `/api/v1/superadmin/tenants/{id}/suspend` | `ROLE_SUPER_ADMIN` | None | `SuperAdminTenantDto` |
| POST | `/api/v1/superadmin/tenants/{id}/activate` | `ROLE_SUPER_ADMIN` | None | `SuperAdminTenantDto` |
| POST | `/api/v1/superadmin/tenants/{id}/deactivate` | `ROLE_SUPER_ADMIN` | None | `SuperAdminTenantDto` |
| POST | `/api/v1/superadmin/tenants/{id}/lifecycle-state` | `ROLE_SUPER_ADMIN` | `CompanyLifecycleStateRequest` | `CompanyLifecycleStateDto` |
| GET | `/api/v1/superadmin/tenants/{id}/usage` | `ROLE_SUPER_ADMIN` | None | `SuperAdminTenantUsageDto` |
| GET | `/api/v1/superadmin/tenants/coa-templates` | `ROLE_SUPER_ADMIN` | None | `List<CoATemplateDto>` |
| POST | `/api/v1/superadmin/tenants/onboard` | `ROLE_SUPER_ADMIN` | `TenantOnboardingRequest` | `TenantOnboardingResponse` |

All responses are wrapped in `ApiResponse<T>`.

#### Module Feature Gating (tenant runtime behavior)

Module access is enforced per-tenant using `companies.enabled_modules` (JSON array of module keys).

- **Gatable modules**: `MANUFACTURING`, `HR_PAYROLL`, `PURCHASING`, `PORTAL`, `REPORTS_ADVANCED`
- **Core modules (always enabled, cannot be disabled)**: `AUTH`, `ACCOUNTING`, `SALES`, `INVENTORY`

If a request hits a disabled gatable module, backend returns **403** with `ErrorCode.MODULE_DISABLED` (`BUS_010`).

Current runtime path mapping used by backend:
- `MANUFACTURING`: `/api/v1/factory/**`, `/api/v1/production/**`
- `HR_PAYROLL`: `/api/v1/hr/**`, `/api/v1/payroll/**`
- `PURCHASING`: `/api/v1/purchasing/**`, `/api/v1/suppliers/**`
- `PORTAL`: `/api/v1/portal/**`, `/api/v1/dealer-portal/**`
- `REPORTS_ADVANCED`: `/api/v1/reports/**`, `/api/v1/accounting/reports/**`

#### User Flows

1. **Load platform dashboard**
   1. `GET /api/v1/superadmin/dashboard`
   2. Render cards: total tenants, active/suspended, total users, API calls, storage, recent activity.

2. **Browse tenants**
   1. `GET /api/v1/superadmin/tenants`
   2. Optional filter: `GET /api/v1/superadmin/tenants?status=SUSPENDED`
   3. Render each row with status + usage summary.

3. **Suspend tenant**
   1. User clicks Suspend in tenant row
   2. `POST /api/v1/superadmin/tenants/{id}/suspend`
   3. Update row status to `SUSPENDED`.

4. **Activate tenant**
   1. User clicks Activate in tenant row
   2. `POST /api/v1/superadmin/tenants/{id}/activate`
   3. Update row status to `ACTIVE`.

5. **Deactivate tenant**
   1. User clicks Deactivate in tenant row
   2. `POST /api/v1/superadmin/tenants/{id}/deactivate`
   3. Update row status to `DEACTIVATED`.

6. **Set lifecycle state explicitly (with reason)**
   1. `POST /api/v1/superadmin/tenants/{id}/lifecycle-state` with `{ state, reason }`
   2. Use for auditable transition requests from superadmin console.

7. **Inspect tenant usage**
   1. `GET /api/v1/superadmin/tenants/{id}/usage`
   2. Show API calls, active users, storage, last activity timestamp.

8. **Tenant onboarding (single-call bootstrap)**
   1. `GET /api/v1/superadmin/tenants/coa-templates` to populate template dropdown (Generic / Indian Standard / Manufacturing).
   2. Submit onboarding form once via `POST /api/v1/superadmin/tenants/onboard`.
   3. Backend creates tenant company, admin user, full chart of accounts from selected template, default accounting period, and baseline system settings.
   4. Frontend stores/display one-time `adminTemporaryPassword` immediately after success.

9. **Tenant runtime lifecycle enforcement expectations**
   - `ACTIVE`: read/write allowed.
   - `SUSPENDED`: **read-only** (write methods return 403).
   - `DEACTIVATED`: all requests blocked with 403.

#### State Machine (superadmin view)

- `ACTIVE` -> `SUSPENDED` via `POST /api/v1/superadmin/tenants/{id}/suspend`
- `SUSPENDED` -> `ACTIVE` via `POST /api/v1/superadmin/tenants/{id}/activate`
- `ACTIVE` -> `DEACTIVATED` via `POST /api/v1/superadmin/tenants/{id}/deactivate`
- `SUSPENDED` -> `DEACTIVATED` via `POST /api/v1/superadmin/tenants/{id}/deactivate`
- `DEACTIVATED` is terminal (no activation path from this state)

#### Error Codes / Error Handling

- **403 Forbidden**: caller is not `ROLE_SUPER_ADMIN`.
  - Frontend behavior: block page access and show “Superadmin access required”.
- **403 + `BUS_010` (`MODULE_DISABLED`)**: tenant module is disabled.
  - Frontend behavior: show contextual “Module disabled for this tenant” empty/error state and hide write actions for that module.
- **403** (tenant lifecycle runtime guard): suspended write or deactivated access.
  - Frontend behavior: show non-retryable state banner (“Tenant suspended: read-only” or “Tenant deactivated”).
- **400 Business validation error** (invalid filter/status or unknown tenant id).
  - Frontend behavior: show inline toast with server message, keep current page state.

#### Data Contracts

- `SuperAdminDashboardDto`
  - `totalTenants: number`
  - `activeTenants: number`
  - `suspendedTenants: number`
  - `deactivatedTenants: number`
  - `totalUsers: number`
  - `totalApiCalls: number`
  - `totalStorageBytes: number`
  - `recentActivityAt: string | null` (ISO-8601)

- `SuperAdminTenantDto`
  - `companyId: number`
  - `companyCode: string`
  - `companyName: string`
  - `status: "ACTIVE" | "SUSPENDED" | "DEACTIVATED"`
  - `activeUsers: number`
  - `apiCallCount: number`
  - `storageBytes: number`
  - `lastActivityAt: string | null` (ISO-8601)

- `SuperAdminTenantUsageDto`
  - `companyId: number`
  - `companyCode: string`
  - `status: "ACTIVE" | "SUSPENDED" | "DEACTIVATED"`
  - `apiCallCount: number`
  - `activeUsers: number`
  - `storageBytes: number`
  - `lastActivityAt: string | null` (ISO-8601)

- `CompanyLifecycleStateRequest`
  - `state: "ACTIVE" | "SUSPENDED" | "DEACTIVATED"` (required)
  - `reason: string` (required, max 1024)

- `CompanyLifecycleStateDto`
  - `companyId: number`
  - `companyCode: string`
  - `previousLifecycleState: "ACTIVE" | "SUSPENDED" | "DEACTIVATED"`
  - `lifecycleState: "ACTIVE" | "SUSPENDED" | "DEACTIVATED"`
  - `reason: string`

- `CoATemplateDto`
  - `code: "GENERIC" | "INDIAN_STANDARD" | "MANUFACTURING"`
  - `name: string`
  - `description: string`
  - `accountCount: number` (always 50-100)

- `TenantOnboardingRequest`
  - `name: string` (required, max 255)
  - `code: string` (required, max 64, normalized uppercase)
  - `timezone: string` (required, max 64)
  - `defaultGstRate?: number` (0-100)
  - `maxActiveUsers?: number >= 0`
  - `maxApiRequests?: number >= 0`
  - `maxStorageBytes?: number >= 0`
  - `maxConcurrentUsers?: number >= 0`
  - `softLimitEnabled?: boolean`
  - `hardLimitEnabled?: boolean`
  - `firstAdminEmail: string` (required email)
  - `firstAdminDisplayName?: string`
  - `coaTemplateCode: "GENERIC" | "INDIAN_STANDARD" | "MANUFACTURING"` (required)

- `TenantOnboardingResponse`
  - `companyId: number`
  - `companyCode: string`
  - `templateCode: string`
  - `accountsCreated: number`
  - `accountingPeriodId: number`
  - `adminEmail: string`
  - `adminTemporaryPassword: string` (show once, do not persist in browser local storage)
  - `credentialsEmailSent: boolean`
  - `systemSettingsInitialized: boolean`

#### UI Hints

- Use a status filter dropdown with `ACTIVE`, `SUSPENDED`, and `DEACTIVATED`.
- Show human-readable storage units (KB/MB/GB) while keeping raw bytes for sorting.
- For lifecycle buttons, show confirmation modals before suspend/activate/deactivate.
- Treat `lastActivityAt = null` as “No activity yet”.
- Refresh dashboard + tenant list after lifecycle mutation to keep aggregates in sync.
- For onboarding, fetch templates first and show `name + description + accountCount` in template picker.
- On onboarding success, immediately display/copy `adminTemporaryPassword` in a modal and force explicit acknowledgement.
- Disable duplicate submits on onboarding button until API response is received.

### Accounting

Comprehensive frontend handoff for `VAL-DOC-003` (chart of accounts, journals, settlement, period controls, reconciliation, GST, audit, catalog bridge, and temporal/reporting endpoints).

> Response envelope convention: almost all endpoints return `ApiResponse<T>` where payload is in `data`; PDF endpoints return raw `byte[]`; CSV endpoint returns `text/csv` string.

#### Complete Endpoint Map (all accounting controllers)

| Method | Path | Auth | Request body | Response `data` type |
|---|---|---|---|---|
| `GET` | `/api/v1/accounting/accounts` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<AccountDto>` |
| `POST` | `/api/v1/accounting/accounts` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountRequest` | `AccountDto` |
| `GET` | `/api/v1/accounting/accounts/tree` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<AccountHierarchyService.AccountNode>` |
| `GET` | `/api/v1/accounting/accounts/tree/{type}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<AccountHierarchyService.AccountNode>` |
| `GET` | `/api/v1/accounting/accounts/{accountId}/activity` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `TemporalBalanceService.AccountActivityReport` |
| `GET` | `/api/v1/accounting/accounts/{accountId}/balance/as-of` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `BigDecimal` |
| `GET` | `/api/v1/accounting/accounts/{accountId}/balance/compare` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `TemporalBalanceService.BalanceComparison` |
| `POST` | `/api/v1/accounting/accruals` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccrualRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/aging/dealers/{dealerId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AgingSummaryResponse` |
| `GET` | `/api/v1/accounting/aging/dealers/{dealerId}/pdf` | `hasAuthority('ROLE_ADMIN')` | `—` | `byte[]` |
| `GET` | `/api/v1/accounting/aging/suppliers/{supplierId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AgingSummaryResponse` |
| `GET` | `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | `hasAuthority('ROLE_ADMIN')` | `—` | `byte[]` |
| `GET` | `/api/v1/accounting/audit-trail` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `PageResponse<AccountingAuditTrailEntryDto>` |
| `GET` | `/api/v1/accounting/audit/digest` | `hasAuthority('ROLE_ADMIN')` | `—` | `AuditDigestResponse` |
| `GET` | `/api/v1/accounting/audit/digest.csv` | `hasAuthority('ROLE_ADMIN')` | `—` | `String` |
| `GET` | `/api/v1/accounting/audit/transactions` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `PageResponse<AccountingTransactionAuditListItemDto>` |
| `GET` | `/api/v1/accounting/audit/transactions/{journalEntryId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AccountingTransactionAuditDetailDto` |
| `POST` | `/api/v1/accounting/bad-debts/write-off` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `BadDebtWriteOffRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/catalog/import` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `multipart/form-data` | `CatalogImportResponse` |
| `GET` | `/api/v1/accounting/catalog/products` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<ProductionProductDto>` |
| `POST` | `/api/v1/accounting/catalog/products` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `ProductCreateRequest` | `ProductionProductDto` |
| `POST` | `/api/v1/accounting/catalog/products/bulk-variants` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `BulkVariantRequest` | `BulkVariantResponse` |
| `PUT` | `/api/v1/accounting/catalog/products/{id}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `ProductUpdateRequest` | `ProductionProductDto` |
| `GET` | `/api/v1/accounting/configuration/health` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `ConfigurationHealthService.ConfigurationHealthReport` |
| `POST` | `/api/v1/accounting/credit-notes` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `CreditNoteRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/date-context` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `Map<String, Object>` |
| `POST` | `/api/v1/accounting/dealers/{dealerId}/auto-settle` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AutoSettlementRequest` | `PartnerSettlementResponse` |
| `POST` | `/api/v1/accounting/debit-notes` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `DebitNoteRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/default-accounts` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `CompanyDefaultAccountsResponse` |
| `PUT` | `/api/v1/accounting/default-accounts` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `CompanyDefaultAccountsRequest` | `CompanyDefaultAccountsResponse` |
| `GET` | `/api/v1/accounting/gst/reconciliation` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `GstReconciliationDto` |
| `GET` | `/api/v1/accounting/gst/return` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `GstReturnDto` |
| `POST` | `/api/v1/accounting/inventory/landed-cost` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `LandedCostRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/inventory/revaluation` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `InventoryRevaluationRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/inventory/wip-adjustment` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `WipAdjustmentRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/journal-entries` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<JournalEntryDto>` |
| `POST` | `/api/v1/accounting/journal-entries` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `JournalEntryRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `JournalEntryReversalRequest` | `List<JournalEntryDto>` |
| `POST` | `/api/v1/accounting/journal-entries/{entryId}/reverse` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `JournalEntryReversalRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/journals` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<JournalListItemDto>` |
| `POST` | `/api/v1/accounting/journals/manual` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `ManualJournalRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/journals/{entryId}/reverse` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `JournalEntryReversalRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/month-end/checklist` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `MonthEndChecklistDto` |
| `POST` | `/api/v1/accounting/month-end/checklist/{periodId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `MonthEndChecklistUpdateRequest` | `MonthEndChecklistDto` |
| `POST` | `/api/v1/accounting/payroll/payments` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `PayrollPaymentRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/payroll/payments/batch` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `PayrollBatchPaymentRequest` | `PayrollBatchPaymentResponse` |
| `GET` | `/api/v1/accounting/periods` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<AccountingPeriodDto>` |
| `POST` | `/api/v1/accounting/periods` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodUpsertRequest` | `AccountingPeriodDto` |
| `PUT` | `/api/v1/accounting/periods/{periodId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodUpdateRequest` | `AccountingPeriodDto` |
| `POST` | `/api/v1/accounting/periods/{periodId}/close` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodCloseRequest` | `AccountingPeriodDto` |
| `POST` | `/api/v1/accounting/periods/{periodId}/lock` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodLockRequest` | `AccountingPeriodDto` |
| `POST` | `/api/v1/accounting/periods/{periodId}/reopen` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodReopenRequest` | `AccountingPeriodDto` |
| `POST` | `/api/v1/accounting/receipts/dealer` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `DealerReceiptRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/receipts/dealer/hybrid` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `DealerReceiptSplitRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/reconciliation/bank` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `BankReconciliationRequest` | `BankReconciliationSummaryDto` |
| `GET` | `/api/v1/accounting/reconciliation/subledger` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `ReconciliationService.SubledgerReconciliationReport` |
| `GET` | `/api/v1/accounting/reports/aging/dealer/{dealerId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AgingReportService.DealerAgingDetail` |
| `GET` | `/api/v1/accounting/reports/aging/dealer/{dealerId}/detailed` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AgingReportService.DealerAgingDetailedReport` |
| `GET` | `/api/v1/accounting/reports/aging/receivables` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AgingReportService.AgedReceivablesReport` |
| `GET` | `/api/v1/accounting/reports/balance-sheet/hierarchy` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AccountHierarchyService.BalanceSheetHierarchy` |
| `GET` | `/api/v1/accounting/reports/dso/dealer/{dealerId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AgingReportService.DSOReport` |
| `GET` | `/api/v1/accounting/reports/income-statement/hierarchy` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AccountHierarchyService.IncomeStatementHierarchy` |
| `GET` | `/api/v1/accounting/sales/returns` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')` | `—` | `List<JournalEntryDto>` |
| `POST` | `/api/v1/accounting/sales/returns` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `SalesReturnRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/settlements/dealers` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `DealerSettlementRequest` | `PartnerSettlementResponse` |
| `POST` | `/api/v1/accounting/settlements/suppliers` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `SupplierSettlementRequest` | `PartnerSettlementResponse` |
| `GET` | `/api/v1/accounting/statements/dealers/{dealerId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `PartnerStatementResponse` |
| `GET` | `/api/v1/accounting/statements/dealers/{dealerId}/pdf` | `hasAuthority('ROLE_ADMIN')` | `—` | `byte[]` |
| `GET` | `/api/v1/accounting/statements/suppliers/{supplierId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `PartnerStatementResponse` |
| `GET` | `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | `hasAuthority('ROLE_ADMIN')` | `—` | `byte[]` |
| `POST` | `/api/v1/accounting/suppliers/payments` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `SupplierPaymentRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/suppliers/{supplierId}/auto-settle` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AutoSettlementRequest` | `PartnerSettlementResponse` |
| `GET` | `/api/v1/accounting/trial-balance/as-of` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `TemporalBalanceService.TrialBalanceSnapshot` |

_Total documented accounting endpoints: **73**._

#### Required User Flows (API call sequences)

1. **Chart of accounts setup**
   1. `GET /api/v1/accounting/accounts` (bootstrap account list)
   2. `GET /api/v1/accounting/accounts/tree` (hierarchy rendering)
   3. `POST /api/v1/accounting/accounts` (create account)
   4. `GET /api/v1/accounting/default-accounts` + `PUT /api/v1/accounting/default-accounts` (default mappings)
   5. Optional typed tree views: `GET /api/v1/accounting/accounts/tree/{type}`

2. **Manual journal entry**
   1. Dropdown preload: `GET /api/v1/accounting/accounts`, `GET /api/v1/dealers`, `GET /api/v1/suppliers`
   2. Create manual journal: `POST /api/v1/accounting/journals/manual` (preferred multi-line path)
   3. List and filter: `GET /api/v1/accounting/journals?fromDate&toDate&type&sourceModule`
   4. Reverse (single): `POST /api/v1/accounting/journals/{entryId}/reverse` or `/journal-entries/{entryId}/reverse`
   5. Reverse (cascade): `POST /api/v1/accounting/journal-entries/{entryId}/cascade-reverse`

3. **Auto-settlement (hands-off)**
   1. Dealer lookup + outstanding context: `GET /api/v1/dealers`, statement/aging endpoints as needed
   2. Dealer auto-settle: `POST /api/v1/accounting/dealers/{dealerId}/auto-settle`
   3. Supplier auto-settle: `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle`
   4. For explicit allocation flows use `POST /settlements/dealers`, `POST /settlements/suppliers`, `POST /receipts/dealer`, `POST /suppliers/payments`

4. **Period close / reopen**
   1. Load periods: `GET /api/v1/accounting/periods`
   2. Validate readiness: `GET /api/v1/accounting/month-end/checklist?periodId={id}`
   3. Optionally mark checklist controls: `POST /api/v1/accounting/month-end/checklist/{periodId}`
   4. Close: `POST /api/v1/accounting/periods/{periodId}/close` (requires non-empty `note`; `force` optional)
   5. Reopen: `POST /api/v1/accounting/periods/{periodId}/reopen` (requires `reason`; auto-reverses closing journal when applicable)

5. **Bank reconciliation**
   1. Account source: `GET /api/v1/accounting/accounts` (ASSET/bank account selection)
   2. Submit statement match: `POST /api/v1/accounting/reconciliation/bank`
   3. Cross-check AR/AP controls: `GET /api/v1/accounting/reconciliation/subledger`

6. **GST return preparation**
   1. Run tax return: `GET /api/v1/accounting/gst/return?period=YYYY-MM`
   2. Run component reconciliation: `GET /api/v1/accounting/gst/reconciliation?period=YYYY-MM`
   3. Optional diagnostics for audit period: `GET /api/v1/accounting/audit-trail`

#### State Machines

1. **Journal lifecycle** (`JournalEntry.status`)
   - `DRAFT/PENDING` -> `POSTED` on successful creation/posting
   - `POSTED` -> `REVERSED` via reversal endpoints
   - `POSTED` -> `VOIDED` when reversal request uses `voidOnly=true` (still creates correction linkage)
   - Guardrails: entry must balance, period must be open, already-reversed/voided entries are rejected

2. **Accounting period lifecycle** (`AccountingPeriodStatus`)
   - `OPEN` -> `LOCKED` via `/periods/{id}/lock`
   - `OPEN` or `LOCKED` -> `CLOSED` via `/periods/{id}/close` (checklist + reconciliation + balancing validations)
   - `LOCKED/CLOSED` -> `OPEN` via `/periods/{id}/reopen` (reason required; closes snapshot + reverses closing journal when present)

3. **Settlement lifecycle** (frontend orchestration state)
   - `INITIATED` (draft UI form)
   - `VALIDATED` (allocations/payments pass amount/account checks)
   - `POSTED` (journal + allocation rows persisted, returns `PartnerSettlementResponse`)
   - `PARTIALLY_SETTLED` / `FULLY_SETTLED` determined by outstanding balance after allocation
   - `REVERSED` when the settlement-linked journal is reversed

#### Accounting ErrorCodes (all referenced in accounting module)

| ErrorCode enum | Wire code | Description | Suggested frontend behavior |
|---|---|---|---|
| `BUSINESS_CONSTRAINT_VIOLATION` | `BUS_004` | Business rule violation | Show business-rule toast/banner; do not auto-retry; keep action disabled until user changes input/state. |
| `BUSINESS_DUPLICATE_ENTRY` | `BUS_002` | Duplicate entry found | Show business-rule toast/banner; do not auto-retry; keep action disabled until user changes input/state. |
| `BUSINESS_ENTITY_NOT_FOUND` | `BUS_003` | Requested resource not found | Show business-rule toast/banner; do not auto-retry; keep action disabled until user changes input/state. |
| `BUSINESS_INVALID_STATE` | `BUS_001` | Operation not allowed in current state | Show business-rule toast/banner; do not auto-retry; keep action disabled until user changes input/state. |
| `CONCURRENCY_CONFLICT` | `CONC_001` | Resource was modified by another user | Show stale-data dialog and force refresh before retry. |
| `INTERNAL_CONCURRENCY_FAILURE` | `CONC_003` | Internal concurrency failure | Show stale-data dialog and force refresh before retry. |
| `SYSTEM_CONFIGURATION_ERROR` | `SYS_005` | System configuration error | Show non-field error with retry option; log traceId for support. |
| `SYSTEM_DATABASE_ERROR` | `SYS_003` | Database operation failed | Show non-field error with retry option; log traceId for support. |
| `SYSTEM_INTERNAL_ERROR` | `SYS_001` | An internal error occurred | Show non-field error with retry option; log traceId for support. |
| `VALIDATION_INVALID_DATE` | `VAL_005` | Invalid date or time value | Show blocking validation message and keep form editable. |
| `VALIDATION_INVALID_INPUT` | `VAL_001` | Invalid input provided | Show blocking validation message and keep form editable. |
| `VALIDATION_INVALID_REFERENCE` | `VAL_006` | Invalid reference to another resource | Mark referenced selector invalid and refresh dropdown source. |
| `VALIDATION_MISSING_REQUIRED_FIELD` | `VAL_002` | Required field is missing | Inline field validation + prevent submit. |

#### Request DTO Contracts (all endpoint request bodies)

- **`AccountRequest`**
  - `code`: `String` — validation `@NotBlank`
  - `name`: `String` — validation `@NotBlank`
  - `type`: `AccountType` — validation `@NotNull`
  - `parentId`: `Long` — validation `—`
- **`AccrualRequest`**
  - `debitAccountId`: `Long` — validation `@NotNull`
  - `creditAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `autoReverseDate`: `LocalDate` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`BadDebtWriteOffRequest`**
  - `invoiceId`: `Long` — validation `@NotNull`
  - `expenseAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`ProductCreateRequest`**
  - `brandId`: `Long` — validation `—`
  - `brandName`: `String` — validation `—`
  - `brandCode`: `String` — validation `—`
  - `productName`: `String` — validation `@NotBlank(message = "Product name is required")`
  - `category`: `String` — validation `@NotBlank(message = "Category is required")`
  - `defaultColour`: `String` — validation `—`
  - `sizeLabel`: `String` — validation `—`
  - `unitOfMeasure`: `String` — validation `—`
  - `customSkuCode`: `String` — validation `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`
  - `basePrice`: `BigDecimal` — validation `—`
  - `gstRate`: `BigDecimal` — validation `—`
  - `minDiscountPercent`: `BigDecimal` — validation `—`
  - `minSellingPrice`: `BigDecimal` — validation `—`
  - `metadata`: `Map<String, Object>` — validation `—`
- **`BulkVariantRequest`**
  - `brandId`: `Long` — validation `—`
  - `brandName`: `String` — validation `—`
  - `brandCode`: `String` — validation `—`
  - `baseProductName`: `String` — validation `@NotBlank`
  - `category`: `String` — validation `@NotBlank`
  - `colors`: `List<String>` — validation `—`
  - `sizes`: `List<String>` — validation `—`
  - `colorSizeMatrix`: `List<ColorSizeMatrixEntry>` — validation `@Valid`
  - `unitOfMeasure`: `String` — validation `—`
  - `skuPrefix`: `String` — validation `—`
  - `basePrice`: `BigDecimal` — validation `—`
  - `gstRate`: `BigDecimal` — validation `—`
  - `minDiscountPercent`: `BigDecimal` — validation `—`
  - `minSellingPrice`: `BigDecimal` — validation `—`
  - `metadata`: `Map<String, Object>` — validation `—`
- **`ProductUpdateRequest`**
  - `productName`: `String` — validation `—`
  - `category`: `String` — validation `—`
  - `defaultColour`: `String` — validation `—`
  - `sizeLabel`: `String` — validation `—`
  - `unitOfMeasure`: `String` — validation `—`
  - `basePrice`: `BigDecimal` — validation `—`
  - `gstRate`: `BigDecimal` — validation `—`
  - `minDiscountPercent`: `BigDecimal` — validation `—`
  - `minSellingPrice`: `BigDecimal` — validation `—`
  - `metadata`: `Map<String, Object>` — validation `—`
- **`CreditNoteRequest`**
  - `invoiceId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@DecimalMin(value = "0.01")`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`AutoSettlementRequest`**
  - `cashAccountId`: `Long` — validation `—`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
- **`DebitNoteRequest`**
  - `purchaseId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@DecimalMin(value = "0.01")`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`CompanyDefaultAccountsRequest`**
  - `inventoryAccountId`: `Long` — validation `—`
  - `cogsAccountId`: `Long` — validation `—`
  - `revenueAccountId`: `Long` — validation `—`
  - `discountAccountId`: `Long` — validation `—`
  - `taxAccountId`: `Long` — validation `—`
- **`LandedCostRequest`**
  - `rawMaterialPurchaseId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull`
  - `inventoryAccountId`: `Long` — validation `@NotNull`
  - `offsetAccountId`: `Long` — validation `@NotNull`
  - `entryDate`: `LocalDate` — validation `—`
  - `memo`: `String` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`InventoryRevaluationRequest`**
  - `inventoryAccountId`: `Long` — validation `@NotNull`
  - `revaluationAccountId`: `Long` — validation `@NotNull`
  - `deltaAmount`: `BigDecimal` — validation `@NotNull`
  - `memo`: `String` — validation `—`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`WipAdjustmentRequest`**
  - `productionLogId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull`
  - `wipAccountId`: `Long` — validation `@NotNull`
  - `inventoryAccountId`: `Long` — validation `@NotNull`
  - `direction`: `Direction` — validation `@NotNull`
  - `memo`: `String` — validation `—`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`JournalEntryRequest`**
  - `referenceNumber`: `String` — validation `—`
  - `entryDate`: `LocalDate` — validation `@NotNull`
  - `memo`: `String` — validation `—`
  - `dealerId`: `Long` — validation `—`
  - `supplierId`: `Long` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
  - `lines`: `List<JournalLineRequest>` — validation `@NotEmpty; @Valid`
  - `currency`: `String` — validation `—`
  - `fxRate`: `BigDecimal` — validation `—`
  - `sourceModule`: `String` — validation `—`
  - `sourceReference`: `String` — validation `—`
  - `journalType`: `String` — validation `—`
- **`JournalEntryReversalRequest`**
  - `reversalDate`: `LocalDate` — validation `—`
  - `voidOnly`: `boolean` — validation `—`
  - `reason`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
  - `reversalPercentage`: `BigDecimal` — validation `@DecimalMin("0.01"); @DecimalMax("100.00")`
  - `cascadeRelatedEntries`: `boolean` — validation `—`
  - `relatedEntryIds`: `List<Long>` — validation `—`
  - `reasonCode`: `ReversalReasonCode` — validation `—`
  - `approvedBy`: `String` — validation `—`
  - `supportingDocumentRef`: `String` — validation `—`
- **`ManualJournalRequest`**
  - `entryDate`: `LocalDate` — validation `—`
  - `narration`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
  - `lines`: `List<LineRequest>` — validation `—`
- **`MonthEndChecklistUpdateRequest`**
  - `bankReconciled`: `Boolean` — validation `—`
  - `inventoryCounted`: `Boolean` — validation `—`
  - `note`: `String` — validation `—`
- **`PayrollPaymentRequest`**
  - `payrollRunId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `expenseAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
- **`PayrollBatchPaymentRequest`**
  - `runDate`: `LocalDate` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `expenseAccountId`: `Long` — validation `@NotNull`
  - `taxPayableAccountId`: `Long` — validation `—`
  - `pfPayableAccountId`: `Long` — validation `—`
  - `employerTaxExpenseAccountId`: `Long` — validation `—`
  - `employerPfExpenseAccountId`: `Long` — validation `—`
  - `defaultTaxRate`: `BigDecimal` — validation `@DecimalMin("0.00")`
  - `defaultPfRate`: `BigDecimal` — validation `@DecimalMin("0.00")`
  - `employerTaxRate`: `BigDecimal` — validation `@DecimalMin("0.00")`
  - `employerPfRate`: `BigDecimal` — validation `@DecimalMin("0.00")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `lines`: `List<PayrollLine>` — validation `@NotEmpty; @Valid`
- **`AccountingPeriodUpsertRequest`**
  - `year`: `int` — validation `@Min(1900); @Max(9999)`
  - `month`: `int` — validation `@Min(1); @Max(12)`
  - `costingMethod`: `CostingMethod` — validation `—`
- **`AccountingPeriodUpdateRequest`**
  - `costingMethod`: `CostingMethod` — validation `@NotNull`
- **`AccountingPeriodCloseRequest`**
  - `force`: `Boolean` — validation `—`
  - `note`: `String` — validation `—`
- **`AccountingPeriodLockRequest`**
  - `reason`: `String` — validation `—`
- **`AccountingPeriodReopenRequest`**
  - `reason`: `String` — validation `—`
- **`DealerReceiptRequest`**
  - `dealerId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `allocations`: `List<SettlementAllocationRequest>` — validation `@NotEmpty(message = "Allocations are required for dealer receipts; use settlement endpoints or include allocations"); @Valid`
- **`DealerReceiptSplitRequest`**
  - `dealerId`: `Long` — validation `@NotNull`
  - `incomingLines`: `List<IncomingLine>` — validation `@NotEmpty; @Valid`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
- **`BankReconciliationRequest`**
  - `bankAccountId`: `Long` — validation `@NotNull`
  - `statementDate`: `LocalDate` — validation `@NotNull`
  - `statementEndingBalance`: `BigDecimal` — validation `@NotNull`
  - `startDate`: `LocalDate` — validation `—`
  - `endDate`: `LocalDate` — validation `—`
  - `clearedReferences`: `List<String>` — validation `—`
  - `accountingPeriodId`: `Long` — validation `—`
  - `markAsComplete`: `Boolean` — validation `—`
  - `note`: `String` — validation `—`
- **`SalesReturnRequest`**
  - `invoiceId`: `Long` — validation `@NotNull`
  - `reason`: `String` — validation `@NotBlank`
  - `lines`: `List<ReturnLine>` — validation `@NotEmpty; @Valid`
- **`DealerSettlementRequest`**
  - `dealerId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `—`
  - `discountAccountId`: `Long` — validation `—`
  - `writeOffAccountId`: `Long` — validation `—`
  - `fxGainAccountId`: `Long` — validation `—`
  - `fxLossAccountId`: `Long` — validation `—`
  - `settlementDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
  - `allocations`: `List<SettlementAllocationRequest>` — validation `@NotEmpty; @Valid`
  - `payments`: `List<SettlementPaymentRequest>` — validation `@Valid`
- **`SupplierSettlementRequest`**
  - `supplierId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `discountAccountId`: `Long` — validation `—`
  - `writeOffAccountId`: `Long` — validation `—`
  - `fxGainAccountId`: `Long` — validation `—`
  - `fxLossAccountId`: `Long` — validation `—`
  - `settlementDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
  - `allocations`: `List<SettlementAllocationRequest>` — validation `@NotEmpty; @Valid`
- **`SupplierPaymentRequest`**
  - `supplierId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `allocations`: `List<SettlementAllocationRequest>` — validation `@NotEmpty(message = "Allocations are required for supplier payments; use settlement endpoints or include allocations"); @Valid`

#### Response DTO Contracts (all endpoint `data` types)

- **`List<AccountDto>`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `code`: `String`
  - `name`: `String`
  - `type`: `AccountType`
  - `balance`: `BigDecimal`
- **`AccountDto`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `code`: `String`
  - `name`: `String`
  - `type`: `AccountType`
  - `balance`: `BigDecimal`
- **`List<AccountHierarchyService.AccountNode>`**
  - `id`: `Long`
  - `code`: `String`
  - `name`: `String`
  - `type`: `String`
  - `balance`: `BigDecimal`
  - `level`: `Integer`
  - `parentId`: `Long`
  - `children`: `List<AccountNode>`
- **`TemporalBalanceService.AccountActivityReport`**
  - `accountCode`: `String`
  - `accountName`: `String`
  - `startDate`: `LocalDate`
  - `endDate`: `LocalDate`
  - `openingBalance`: `BigDecimal`
  - `closingBalance`: `BigDecimal`
  - `totalDebits`: `BigDecimal`
  - `totalCredits`: `BigDecimal`
  - `movements`: `List<AccountMovement>`
- **`BigDecimal`**
  - Primitive/raw payload type (no DTO field list).
- **`TemporalBalanceService.BalanceComparison`**
  - `accountId`: `Long`
  - `date1`: `LocalDate`
  - `balance1`: `BigDecimal`
  - `date2`: `LocalDate`
  - `balance2`: `BigDecimal`
  - `change`: `BigDecimal`
- **`JournalEntryDto`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `referenceNumber`: `String`
  - `entryDate`: `LocalDate`
  - `memo`: `String`
  - `status`: `String`
  - `dealerId`: `Long`
  - `dealerName`: `String`
  - `supplierId`: `Long`
  - `supplierName`: `String`
  - `accountingPeriodId`: `Long`
  - `accountingPeriodLabel`: `String`
  - `accountingPeriodStatus`: `String`
  - `reversalOfEntryId`: `Long`
  - `reversalEntryId`: `Long`
  - `correctionType`: `String`
  - `correctionReason`: `String`
  - `voidReason`: `String`
  - `lines`: `List<JournalLineDto>`
  - `createdAt`: `Instant`
  - `updatedAt`: `Instant`
  - `postedAt`: `Instant`
  - `createdBy`: `String`
  - `postedBy`: `String`
  - `lastModifiedBy`: `String`
- **`AgingSummaryResponse`**
  - `partnerId`: `Long`
  - `partnerName`: `String`
  - `totalOutstanding`: `BigDecimal`
  - `buckets`: `List<AgingBucketDto>`
- **`byte[]`**
  - Primitive/raw payload type (no DTO field list).
- **`PageResponse<AccountingAuditTrailEntryDto>`**
  - `content`: `List<AccountingAuditTrailEntryDto>`
    - `id`: `Long`
    - `timestamp`: `Instant`
    - `companyId`: `Long`
    - `companyCode`: `String`
    - `actorUserId`: `Long`
    - `actorIdentifier`: `String`
    - `actionType`: `String`
    - `entityType`: `String`
    - `entityId`: `String`
    - `referenceNumber`: `String`
    - `traceId`: `String`
    - `ipAddress`: `String`
    - `beforeState`: `String`
    - `afterState`: `String`
    - `sensitiveOperation`: `boolean`
    - `metadata`: `Map<String, String>`
  - `totalElements`: `long`
  - `totalPages`: `int`
  - `page`: `int` (0-based)
  - `size`: `int`
- **`AuditDigestResponse`**
  - `periodLabel`: `String`
  - `entries`: `List<String>`
- **`String`**
  - Primitive/raw payload type (no DTO field list).
- **`PageResponse<AccountingTransactionAuditListItemDto>`**
  - `content`: `List<AccountingTransactionAuditListItemDto>`
    - `journalEntryId`: `Long`
    - `referenceNumber`: `String`
    - `entryDate`: `LocalDate`
    - `status`: `String`
    - `module`: `String`
    - `transactionType`: `String`
    - `memo`: `String`
    - `dealerId`: `Long`
    - `dealerName`: `String`
    - `supplierId`: `Long`
    - `supplierName`: `String`
    - `totalDebit`: `BigDecimal`
    - `totalCredit`: `BigDecimal`
    - `reversalOfId`: `Long`
    - `reversalEntryId`: `Long`
    - `correctionType`: `String`
    - `consistencyStatus`: `String`
    - `postedAt`: `Instant`
  - `totalElements`: `long`
  - `totalPages`: `int`
  - `page`: `int` (0-based)
  - `size`: `int`
- **`AccountingTransactionAuditDetailDto`**
  - `journalEntryId`: `Long`
  - `journalPublicId`: `UUID`
  - `referenceNumber`: `String`
  - `entryDate`: `LocalDate`
  - `status`: `String`
  - `module`: `String`
  - `transactionType`: `String`
  - `memo`: `String`
  - `dealerId`: `Long`
  - `dealerName`: `String`
  - `supplierId`: `Long`
  - `supplierName`: `String`
  - `accountingPeriodId`: `Long`
  - `accountingPeriodLabel`: `String`
  - `accountingPeriodStatus`: `String`
  - `reversalOfId`: `Long`
  - `reversalEntryId`: `Long`
  - `correctionType`: `String`
  - `correctionReason`: `String`
  - `voidReason`: `String`
  - `totalDebit`: `BigDecimal`
  - `totalCredit`: `BigDecimal`
  - `consistencyStatus`: `String`
  - `consistencyNotes`: `List<String>`
  - `lines`: `List<JournalLineDto>`
  - `linkedDocuments`: `List<LinkedDocument>`
  - `settlementAllocations`: `List<SettlementAllocation>`
  - `eventTrail`: `List<EventTrailItem>`
  - `createdAt`: `Instant`
  - `updatedAt`: `Instant`
  - `postedAt`: `Instant`
  - `createdBy`: `String`
  - `postedBy`: `String`
  - `lastModifiedBy`: `String`
- **`CatalogImportResponse`**
  - `rowsProcessed`: `int`
  - `brandsCreated`: `int`
  - `productsCreated`: `int`
  - `productsUpdated`: `int`
  - `rawMaterialsSeeded`: `int`
  - `errors`: `List<ImportError>`
- **`List<ProductionProductDto>`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `brandId`: `Long`
  - `brandName`: `String`
  - `brandCode`: `String`
  - `productName`: `String`
  - `category`: `String`
  - `defaultColour`: `String`
  - `sizeLabel`: `String`
  - `unitOfMeasure`: `String`
  - `skuCode`: `String`
  - `active`: `boolean`
  - `basePrice`: `BigDecimal`
  - `gstRate`: `BigDecimal`
  - `minDiscountPercent`: `BigDecimal`
  - `minSellingPrice`: `BigDecimal`
  - `metadata`: `Map<String, Object>`
- **`ProductionProductDto`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `brandId`: `Long`
  - `brandName`: `String`
  - `brandCode`: `String`
  - `productName`: `String`
  - `category`: `String`
  - `defaultColour`: `String`
  - `sizeLabel`: `String`
  - `unitOfMeasure`: `String`
  - `skuCode`: `String`
  - `active`: `boolean`
  - `basePrice`: `BigDecimal`
  - `gstRate`: `BigDecimal`
  - `minDiscountPercent`: `BigDecimal`
  - `minSellingPrice`: `BigDecimal`
  - `metadata`: `Map<String, Object>`
- **`BulkVariantResponse`**
  - `generated`: `List<VariantItem>`
  - `conflicts`: `List<VariantItem>`
  - `wouldCreate`: `List<VariantItem>`
  - `created`: `List<VariantItem>`
- **`ConfigurationHealthService.ConfigurationHealthReport`**
  - `healthy`: `boolean` (true when no issues are present)
  - `issues`: `List<ConfigurationIssue>`
    - `companyCode`: `String` (tenant/company code where issue was detected)
    - `domain`: `String` (issue category, e.g. `DEFAULT_ACCOUNTS`, `TAX_ACCOUNT`, `PRODUCTION_METADATA`)
    - `reference`: `String` (entity reference such as SKU, `BASE`, or `COMPANY_DEFAULTS`)
    - `message`: `String` (human-readable remediation hint)
- **`Map<String, Object>`**
  - Primitive/raw payload type (no DTO field list).
- **`PartnerSettlementResponse`**
  - `journalEntry`: `JournalEntryDto`
  - `totalApplied`: `BigDecimal`
  - `cashAmount`: `BigDecimal`
  - `totalDiscount`: `BigDecimal`
  - `totalWriteOff`: `BigDecimal`
  - `totalFxGain`: `BigDecimal`
  - `totalFxLoss`: `BigDecimal`
  - `allocations`: `List<Allocation>`
- **`CompanyDefaultAccountsResponse`**
  - `inventoryAccountId`: `Long`
  - `cogsAccountId`: `Long`
  - `revenueAccountId`: `Long`
  - `discountAccountId`: `Long`
  - `taxAccountId`: `Long`
- **`GstReconciliationDto`**
  - `period`: `YearMonth`
  - `periodStart`: `LocalDate`
  - `periodEnd`: `LocalDate`
  - `collected`: `GstComponentSummary`
  - `inputTaxCredit`: `GstComponentSummary`
  - `netLiability`: `GstComponentSummary`
  - `cgst`: `BigDecimal`
  - `sgst`: `BigDecimal`
  - `igst`: `BigDecimal`
  - `total`: `BigDecimal`
- **`GstReturnDto`**
  - `period`: `YearMonth`
  - `periodStart`: `LocalDate`
  - `periodEnd`: `LocalDate`
  - `outputTax`: `BigDecimal`
  - `inputTax`: `BigDecimal`
  - `netPayable`: `BigDecimal`
- **`List<JournalEntryDto>`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `referenceNumber`: `String`
  - `entryDate`: `LocalDate`
  - `memo`: `String`
  - `status`: `String`
  - `dealerId`: `Long`
  - `dealerName`: `String`
  - `supplierId`: `Long`
  - `supplierName`: `String`
  - `accountingPeriodId`: `Long`
  - `accountingPeriodLabel`: `String`
  - `accountingPeriodStatus`: `String`
  - `reversalOfEntryId`: `Long`
  - `reversalEntryId`: `Long`
  - `correctionType`: `String`
  - `correctionReason`: `String`
  - `voidReason`: `String`
  - `lines`: `List<JournalLineDto>`
  - `createdAt`: `Instant`
  - `updatedAt`: `Instant`
  - `postedAt`: `Instant`
  - `createdBy`: `String`
  - `postedBy`: `String`
  - `lastModifiedBy`: `String`
- **`List<JournalListItemDto>`**
  - `id`: `Long`
  - `referenceNumber`: `String`
  - `entryDate`: `LocalDate`
  - `memo`: `String`
  - `status`: `String`
  - `journalType`: `String`
  - `sourceModule`: `String`
  - `sourceReference`: `String`
  - `totalDebit`: `BigDecimal`
  - `totalCredit`: `BigDecimal`
- **`MonthEndChecklistDto`**
  - `period`: `AccountingPeriodDto`
  - `items`: `List<MonthEndChecklistItemDto>`
  - `readyToClose`: `boolean`
- **`PayrollBatchPaymentResponse`**
  - `payrollRunId`: `Long`
  - `runDate`: `LocalDate`
  - `grossAmount`: `BigDecimal`
  - `totalTaxWithholding`: `BigDecimal`
  - `totalPfWithholding`: `BigDecimal`
  - `totalAdvances`: `BigDecimal`
  - `netPayAmount`: `BigDecimal`
  - `employerTaxAmount`: `BigDecimal`
  - `employerPfAmount`: `BigDecimal`
  - `totalEmployerCost`: `BigDecimal`
  - `payrollJournalId`: `Long`
  - `employerContribJournalId`: `Long`
  - `lines`: `List<LineTotal>`
- **`List<AccountingPeriodDto>`**
  - `id`: `Long`
  - `year`: `int`
  - `month`: `int`
  - `startDate`: `LocalDate`
  - `endDate`: `LocalDate`
  - `label`: `String`
  - `status`: `String`
  - `bankReconciled`: `boolean`
  - `bankReconciledAt`: `Instant`
  - `bankReconciledBy`: `String`
  - `inventoryCounted`: `boolean`
  - `inventoryCountedAt`: `Instant`
  - `inventoryCountedBy`: `String`
  - `closedAt`: `Instant`
  - `closedBy`: `String`
  - `closedReason`: `String`
  - `lockedAt`: `Instant`
  - `lockedBy`: `String`
  - `lockReason`: `String`
  - `reopenedAt`: `Instant`
  - `reopenedBy`: `String`
  - `reopenReason`: `String`
  - `closingJournalEntryId`: `Long`
  - `checklistNotes`: `String`
  - `costingMethod`: `String`
- **`AccountingPeriodDto`**
  - `id`: `Long`
  - `year`: `int`
  - `month`: `int`
  - `startDate`: `LocalDate`
  - `endDate`: `LocalDate`
  - `label`: `String`
  - `status`: `String`
  - `bankReconciled`: `boolean`
  - `bankReconciledAt`: `Instant`
  - `bankReconciledBy`: `String`
  - `inventoryCounted`: `boolean`
  - `inventoryCountedAt`: `Instant`
  - `inventoryCountedBy`: `String`
  - `closedAt`: `Instant`
  - `closedBy`: `String`
  - `closedReason`: `String`
  - `lockedAt`: `Instant`
  - `lockedBy`: `String`
  - `lockReason`: `String`
  - `reopenedAt`: `Instant`
  - `reopenedBy`: `String`
  - `reopenReason`: `String`
  - `closingJournalEntryId`: `Long`
  - `checklistNotes`: `String`
  - `costingMethod`: `String`
- **`BankReconciliationSummaryDto`**
  - `accountId`: `Long`
  - `accountCode`: `String`
  - `accountName`: `String`
  - `statementDate`: `LocalDate`
  - `ledgerBalance`: `BigDecimal`
  - `statementEndingBalance`: `BigDecimal`
  - `outstandingDeposits`: `BigDecimal`
  - `outstandingChecks`: `BigDecimal`
  - `difference`: `BigDecimal`
  - `balanced`: `boolean`
  - `unclearedDeposits`: `List<BankReconciliationItemDto>`
  - `unclearedChecks`: `List<BankReconciliationItemDto>`
- **`ReconciliationService.SubledgerReconciliationReport`**
  - `dealerReconciliation`: `ReconciliationResult`
  - `supplierReconciliation`: `SupplierReconciliationResult`
  - `combinedVariance`: `BigDecimal`
  - `reconciled`: `boolean`
- **`AgingReportService.DealerAgingDetail`**
  - `dealerId`: `Long`
  - `dealerCode`: `String`
  - `dealerName`: `String`
  - `buckets`: `AgingBuckets`
  - `totalOutstanding`: `BigDecimal`
- **`AgingReportService.DealerAgingDetailedReport`**
  - `dealerId`: `Long`
  - `dealerCode`: `String`
  - `dealerName`: `String`
  - `lineItems`: `List<AgingLineItem>`
  - `buckets`: `AgingBuckets`
  - `totalOutstanding`: `BigDecimal`
  - `averageDSO`: `double`
- **`AgingReportService.AgedReceivablesReport`**
  - `asOfDate`: `LocalDate`
  - `dealers`: `List<DealerAgingDetail>`
  - `totalBuckets`: `AgingBuckets`
  - `grandTotal`: `BigDecimal`
- **`AccountHierarchyService.BalanceSheetHierarchy`**
  - `assets`: `List<AccountNode>`
  - `totalAssets`: `BigDecimal`
  - `liabilities`: `List<AccountNode>`
  - `totalLiabilities`: `BigDecimal`
  - `equity`: `List<AccountNode>`
  - `totalEquity`: `BigDecimal`
- **`AgingReportService.DSOReport`**
  - `dealerId`: `Long`
  - `dealerName`: `String`
  - `averageDSO`: `double`
  - `totalOutstanding`: `BigDecimal`
  - `openInvoices`: `int`
  - `overdueInvoices`: `long`
- **`AccountHierarchyService.IncomeStatementHierarchy`**
  - `revenue`: `List<AccountNode>`
  - `totalRevenue`: `BigDecimal`
  - `cogs`: `List<AccountNode>`
  - `totalCogs`: `BigDecimal`
  - `grossProfit`: `BigDecimal`
  - `expenses`: `List<AccountNode>`
  - `totalExpenses`: `BigDecimal`
  - `netIncome`: `BigDecimal`
- **`PartnerStatementResponse`**
  - `partnerId`: `Long`
  - `partnerName`: `String`
  - `fromDate`: `LocalDate`
  - `toDate`: `LocalDate`
  - `openingBalance`: `BigDecimal`
  - `closingBalance`: `BigDecimal`
  - `transactions`: `List<StatementTransactionDto>`
- **`TemporalBalanceService.TrialBalanceSnapshot`**
  - `asOfDate`: `LocalDate`
  - `entries`: `List<TrialBalanceEntry>`
  - `totalDebits`: `BigDecimal`
  - `totalCredits`: `BigDecimal`

#### UI Hints (accounting screens)

- **Dropdown sources**
  - Account dropdowns: `GET /api/v1/accounting/accounts`
  - Dealer dropdowns/search: `GET /api/v1/dealers`, `GET /api/v1/dealers/search?query=`
  - Supplier dropdowns: `GET /api/v1/suppliers`
  - Catalog product selection in accounting context: `GET /api/v1/accounting/catalog/products`
- **Computed fields**
  - GST component split is computed server-side: `taxType=INTRA_STATE` => `cgst+sgst`; `INTER_STATE` => `igst`
  - Settlement totals (`totalApplied`, `totalDiscount`, `totalFxGain/loss`) are computed; render read-only summary cards
  - Statement running balances and period checklist readiness are server computed; never recompute from partial UI data
- **Dependent fields**
  - `sourceState`/`destState` (dealer/supplier/company state codes) decide GST type and tax split
  - In settlement requests, non-zero discount/write-off/fx values require corresponding account IDs (`discountAccountId`, `writeOffAccountId`, etc.)
  - Period close requires checklist controls satisfied unless `force=true` is explicitly used
- **Idempotency**
  - For mutation endpoints supporting replay protection, send `Idempotency-Key` (preferred). Legacy `X-Idempotency-Key` is accepted; mismatches are rejected.

### Product Catalog & Inventory

Comprehensive handoff for `VAL-DOC-004` covering catalog, inventory, dispatch, and manufacturing API surfaces.

> Response convention: endpoints below return `ApiResponse<T>` unless explicitly noted (`DELETE /api/v1/factory/production-plans/{id}` and `DELETE /api/v1/accounting/raw-materials/{id}` return `204`).

#### Endpoint Map — Catalog (brands/products/bulk/search)

Auth default: `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES','ROLE_FACTORY')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| POST | `/api/v1/catalog/brands` | `CatalogBrandRequest` | `CatalogBrandDto` |
| GET | `/api/v1/catalog/brands` | Query: `active?` | `List<CatalogBrandDto>` |
| GET | `/api/v1/catalog/brands/{brandId}` | — | `CatalogBrandDto` |
| PUT | `/api/v1/catalog/brands/{brandId}` | `CatalogBrandRequest` | `CatalogBrandDto` |
| DELETE | `/api/v1/catalog/brands/{brandId}` | — | `CatalogBrandDto` (deactivated) |
| POST | `/api/v1/catalog/products` | `CatalogProductRequest` | `CatalogProductDto` |
| GET | `/api/v1/catalog/products` | Query: `brandId?`, `color?`, `size?`, `active?`, `page`, `pageSize` | `PageResponse<CatalogProductDto>` |
| GET | `/api/v1/catalog/products/{productId}` | — | `CatalogProductDto` |
| PUT | `/api/v1/catalog/products/{productId}` | `CatalogProductRequest` | `CatalogProductDto` |
| DELETE | `/api/v1/catalog/products/{productId}` | — | `CatalogProductDto` (deactivated) |
| POST | `/api/v1/catalog/products/bulk` | `List<CatalogProductBulkItemRequest>` | `CatalogProductBulkResponse` |
| GET | `/api/v1/production/brands` | — | `List<ProductionBrandDto>` |
| GET | `/api/v1/production/brands/{brandId}/products` | — | `List<ProductionProductDto>` |

#### Endpoint Map — Inventory (stock, batches, movement history, adjustments, dispatch)

##### Finished goods stock + batch APIs

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/finished-goods` | ADMIN/FACTORY/SALES/ACCOUNTING | — | `List<FinishedGoodDto>` |
| GET | `/api/v1/finished-goods/{id}` | ADMIN/FACTORY/SALES/ACCOUNTING | — | `FinishedGoodDto` |
| POST | `/api/v1/finished-goods` | ADMIN/FACTORY | `FinishedGoodRequest` | `FinishedGoodDto` |
| PUT | `/api/v1/finished-goods/{id}` | ADMIN/FACTORY | `FinishedGoodRequest` | `FinishedGoodDto` |
| GET | `/api/v1/finished-goods/{id}/batches` | ADMIN/FACTORY/SALES | — | `List<FinishedGoodBatchDto>` |
| POST | `/api/v1/finished-goods/{id}/batches` | ADMIN/FACTORY | `FinishedGoodBatchRequest` | `FinishedGoodBatchDto` |
| GET | `/api/v1/finished-goods/stock-summary` | ADMIN/FACTORY/SALES/ACCOUNTING | — | `List<StockSummaryDto>` |
| GET | `/api/v1/finished-goods/low-stock` | ADMIN/FACTORY/SALES | Query: `threshold?` | `List<FinishedGoodDto>` |
| GET | `/api/v1/finished-goods/{id}/low-stock-threshold` | ADMIN/FACTORY/SALES/ACCOUNTING | — | `FinishedGoodLowStockThresholdDto` |
| PUT | `/api/v1/finished-goods/{id}/low-stock-threshold` | ADMIN/FACTORY/ACCOUNTING | `FinishedGoodLowStockThresholdRequest` | `FinishedGoodLowStockThresholdDto` |

##### Raw material stock + batch APIs

Auth default for controller: `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| GET | `/api/v1/accounting/raw-materials` | — | `List<RawMaterialDto>` |
| POST | `/api/v1/accounting/raw-materials` | `RawMaterialRequest` | `RawMaterialDto` |
| PUT | `/api/v1/accounting/raw-materials/{id}` | `RawMaterialRequest` | `RawMaterialDto` |
| DELETE | `/api/v1/accounting/raw-materials/{id}` | — | `204 No Content` |
| GET | `/api/v1/raw-materials/stock` | — | `StockSummaryDto` |
| GET | `/api/v1/raw-materials/stock/inventory` | — | `List<InventoryStockSnapshot>` |
| GET | `/api/v1/raw-materials/stock/low-stock` | — | `List<InventoryStockSnapshot>` |
| GET | `/api/v1/raw-material-batches/{rawMaterialId}` | — | `List<RawMaterialBatchDto>` |
| POST | `/api/v1/raw-material-batches/{rawMaterialId}` | Headers: `Idempotency-Key`/`X-Idempotency-Key`, body `RawMaterialBatchRequest` | `RawMaterialBatchDto` |
| POST | `/api/v1/raw-materials/intake` | Headers: `Idempotency-Key`/`X-Idempotency-Key`, body `RawMaterialIntakeRequest` | `RawMaterialBatchDto` |

##### Inventory adjustment + traceability APIs

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/inventory/adjustments` | ADMIN/ACCOUNTING | — | `List<InventoryAdjustmentDto>` |
| POST | `/api/v1/inventory/adjustments` | ADMIN/ACCOUNTING | Header/body idempotency + `InventoryAdjustmentRequest` | `InventoryAdjustmentDto` |
| GET | `/api/v1/inventory/batches/{id}/movements` | ADMIN/FACTORY/ACCOUNTING/SALES | Query: `batchType?` | `InventoryBatchTraceabilityDto` |
| POST | `/api/v1/inventory/opening-stock` | ADMIN/ACCOUNTING/FACTORY | `multipart/form-data` (`file`) + idempotency header | `OpeningStockImportResponse` |

##### Dispatch APIs

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/dispatch/pending` | ADMIN/FACTORY/SALES | — | `List<PackagingSlipDto>` |
| GET | `/api/v1/dispatch/preview/{slipId}` | ADMIN/FACTORY | — | `DispatchPreviewDto` |
| GET | `/api/v1/dispatch/slip/{slipId}` | ADMIN/FACTORY/SALES | — | `PackagingSlipDto` |
| GET | `/api/v1/dispatch/order/{orderId}` | ADMIN/FACTORY/SALES | — | `PackagingSlipDto` |
| POST | `/api/v1/dispatch/confirm` | ADMIN/FACTORY + authority `dispatch.confirm` | `DispatchConfirmationRequest` | `DispatchConfirmationResponse` |
| PATCH | `/api/v1/dispatch/slip/{slipId}/status` | ADMIN/FACTORY | Query: `status` | `PackagingSlipDto` |
| POST | `/api/v1/dispatch/backorder/{slipId}/cancel` | ADMIN/FACTORY | Query: `reason?` | `PackagingSlipDto` |

#### Endpoint Map — Manufacturing (plans, logs, packing, wastage)

##### Core factory endpoints (`/api/v1/factory`)

Default auth: `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')` unless noted.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| GET | `/api/v1/factory/production-plans` | — | `List<ProductionPlanDto>` |
| POST | `/api/v1/factory/production-plans` | `ProductionPlanRequest` | `ProductionPlanDto` |
| PUT | `/api/v1/factory/production-plans/{id}` | `ProductionPlanRequest` | `ProductionPlanDto` |
| PATCH | `/api/v1/factory/production-plans/{id}/status` | Body `{ status }` | `ProductionPlanDto` |
| DELETE | `/api/v1/factory/production-plans/{id}` | — | `204 No Content` |
| GET | `/api/v1/factory/production-batches` | — | `List<ProductionBatchDto>` |
| POST | `/api/v1/factory/production-batches` | Query `planId?` + `ProductionBatchRequest` | `ProductionBatchDto` |
| GET | `/api/v1/factory/tasks` | — | `List<FactoryTaskDto>` |
| POST | `/api/v1/factory/tasks` | `FactoryTaskRequest` | `FactoryTaskDto` |
| PUT | `/api/v1/factory/tasks/{id}` | `FactoryTaskRequest` | `FactoryTaskDto` |
| GET | `/api/v1/factory/dashboard` | — | `FactoryDashboardDto` |
| POST | `/api/v1/factory/cost-allocation` | `CostAllocationRequest` | `CostAllocationResponse` |

##### Production logs (`/api/v1/factory/production/logs`)

Auth: `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| POST | `/api/v1/factory/production/logs` | `ProductionLogRequest` | `ProductionLogDetailDto` |
| GET | `/api/v1/factory/production/logs` | — | `List<ProductionLogDto>` |
| GET | `/api/v1/factory/production/logs/{id}` | — | `ProductionLogDetailDto` |

##### Packing + bulk-to-size packing (`/api/v1/factory`)

Auth default: `hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| POST | `/api/v1/factory/packing-records` | Headers: idempotency (`Idempotency-Key`/`X-Idempotency-Key`/`X-Request-Id`), body `PackingRequest` | `ProductionLogDetailDto` |
| POST | `/api/v1/factory/packing-records/{productionLogId}/complete` | — | `ProductionLogDetailDto` |
| GET | `/api/v1/factory/unpacked-batches` | — | `List<UnpackedBatchDto>` |
| GET | `/api/v1/factory/production-logs/{productionLogId}/packing-history` | — | `List<PackingRecordDto>` |
| POST | `/api/v1/factory/pack` | `BulkPackRequest` | `BulkPackResponse` |
| GET | `/api/v1/factory/bulk-batches/{finishedGoodId}` | — | `List<BulkPackResponse.ChildBatchDto>` |
| GET | `/api/v1/factory/bulk-batches/{parentBatchId}/children` | — | `List<BulkPackResponse.ChildBatchDto>` |

##### Packaging mapping configuration (`/api/v1/factory/packaging-mappings`)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/factory/packaging-mappings` | ADMIN/FACTORY | — | `List<PackagingSizeMappingDto>` |
| GET | `/api/v1/factory/packaging-mappings/active` | ADMIN/FACTORY | — | `List<PackagingSizeMappingDto>` |
| POST | `/api/v1/factory/packaging-mappings` | ADMIN | `PackagingSizeMappingRequest` | `PackagingSizeMappingDto` |
| PUT | `/api/v1/factory/packaging-mappings/{id}` | ADMIN | `PackagingSizeMappingRequest` | `PackagingSizeMappingDto` |
| DELETE | `/api/v1/factory/packaging-mappings/{id}` | ADMIN | — | `ApiResponse<Void>` |

##### Wastage/cost analytics endpoints

Auth for report controller endpoints: `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| GET | `/api/v1/reports/wastage` | — | `List<WastageReportDto>` |
| GET | `/api/v1/reports/production-logs/{id}/cost-breakdown` | — | `CostBreakdownDto` |
| GET | `/api/v1/reports/monthly-production-costs` | Query: `year`, `month` | `MonthlyProductionCostDto` |

#### Required User Flows

1. **Product setup flow (`create brand -> create product -> set sizes/cartons`)**
   1. `POST /api/v1/catalog/brands`.
   2. `GET /api/v1/catalog/brands?active=true` (brand dropdown refresh).
   3. `POST /api/v1/catalog/products` with `colors[]`, `sizes[]`, and full `cartonSizes[]` mapping.
   4. `GET /api/v1/catalog/products?brandId={brandId}&page=0&pageSize=20` (table refresh/search).
   5. Optional bulk path: `POST /api/v1/catalog/products/bulk` and render row-level result states.

2. **Production flow (`plan -> log -> pack -> stock`)**
   1. Create plan: `POST /api/v1/factory/production-plans`.
   2. Log production with consumed materials and costs: `POST /api/v1/factory/production/logs`.
   3. Record packing sessions (repeat as needed): `POST /api/v1/factory/packing-records`.
   4. Finalize log/wastage: `POST /api/v1/factory/packing-records/{productionLogId}/complete`.
   5. Verify stock: `GET /api/v1/finished-goods/stock-summary` + `GET /api/v1/finished-goods/{id}/batches`.
   6. Optional size conversion: `POST /api/v1/factory/pack`.

3. **Dispatch flow (`reserve -> preview -> confirm`)**
   1. Inventory reservation is created during sales order create/update flows (`POST/PUT /api/v1/sales/orders...`) via `SalesService.reserveForOrder`; there is no standalone reserve endpoint.
   2. Resolve slip: `GET /api/v1/dispatch/order/{orderId}` (or list via `/pending`).
   3. Show preview modal: `GET /api/v1/dispatch/preview/{slipId}`.
   4. Confirm actual shipped quantities: `POST /api/v1/dispatch/confirm` (this finalizes shipment + accounting; it does not perform initial reservation).
   5. Refresh slip and totals: `GET /api/v1/dispatch/slip/{slipId}`.
   6. If needed, cancel generated backorder slip: `POST /api/v1/dispatch/backorder/{slipId}/cancel`.

#### State Machines

##### Production log lifecycle (`ProductionLogStatus`)

- `MIXED` (domain default/internal) -> `READY_TO_PACK` on `POST /api/v1/factory/production/logs`.
- `READY_TO_PACK` -> `PARTIAL_PACKED` when `POST /api/v1/factory/packing-records` packs only part of quantity.
- `READY_TO_PACK`/`PARTIAL_PACKED` -> `FULLY_PACKED` when packed quantity reaches mixed quantity or `POST /packing-records/{id}/complete` runs.
- No public API transition moves `PARTIAL_PACKED -> READY_TO_PACK`; frontend should treat packing progress as monotonic until completion.
- Wastage is materialized at completion (`wastageQuantity`, `wastageReasonCode`).

##### Dispatch slip lifecycle (`PackagingSlip.status`)

Operational statuses: `PENDING`, `PENDING_STOCK`, `PENDING_PRODUCTION`, `RESERVED`, `BACKORDER`, `DISPATCHED`, `CANCELLED`.

- Auto reservation path: shortages -> `PENDING_PRODUCTION`; no shortages -> `RESERVED`.
- `PENDING_STOCK` is not an initial reservation state; it is used when dispatch confirmation results in zero shipped quantity while stock is still pending.
- Manual status endpoint (`PATCH /dispatch/slip/{id}/status`) only allows transitions among: `PENDING`, `PENDING_STOCK`, `PENDING_PRODUCTION`, `RESERVED`.
- `POST /dispatch/confirm`:
  - if any quantity shipped -> current slip `DISPATCHED`.
  - if partial shipment -> backorder slip is created in `BACKORDER`.
  - if no shipment and shortage persists -> `PENDING_STOCK`.
- `POST /dispatch/backorder/{id}/cancel` moves `BACKORDER -> CANCELLED` and releases reservations.
- Terminal states: `DISPATCHED`, `CANCELLED`.

#### Error Codes (catalog/inventory/manufacturing)

| ErrorCode enum | Wire code | Typical trigger in this module area | Frontend behavior |
|---|---|---|---|
| `BUSINESS_INVALID_STATE` | `BUS_001` | Inactive brand used for product mutation, invalid dispatch/backorder state transition | Show non-retryable state error; reload entity state |
| `BUSINESS_DUPLICATE_ENTRY` | `BUS_002` | Duplicate brand name/product name | Inline field error + keep form open |
| `BUSINESS_ENTITY_NOT_FOUND` | `BUS_003` | Brand/product/batch/mapping not found | Show not-found toast and navigate back to list |
| `BUSINESS_CONSTRAINT_VIOLATION` | `BUS_004` | Insufficient stock/business guardrails | Block submit and surface corrective action |
| `VALIDATION_INVALID_INPUT` | `VAL_001` | Missing/invalid sizes-carton mapping, invalid quantities/status | Inline validation + do not retry automatically |
| `VALIDATION_MISSING_REQUIRED_FIELD` | `VAL_002` | Missing idempotency key or required payload fields | Highlight required fields/headers |
| `VALIDATION_INVALID_REFERENCE` | `VAL_006` | Raw material/account/reference IDs invalid | Refresh dropdown data and force re-selection |
| `CONCURRENCY_CONFLICT` | `CONC_001` | Race during reservation/dispatch/stock update | Refetch latest slip/stock and allow one retry |
| `INTERNAL_CONCURRENCY_FAILURE` | `CONC_003` | Internal lock/retry exhaustion in packing flows | Show retry CTA with trace ID |
| `DUPLICATE_ENTITY` | `DATA_001` | Duplicate packaging-size mapping | Inline duplicate warning on mapping screen |

#### Data Contracts (DTOs)

##### Catalog DTOs

- `CatalogBrandRequest`: `name*`, `logoUrl`, `description`, `active`.
- `CatalogBrandDto`: `id`, `publicId`, `name`, `code`, `logoUrl`, `description`, `active`.
- `CatalogProductRequest`: `brandId*`, `name*`, `colors[]`, `sizes[]`, `cartonSizes[]`, `unitOfMeasure*`, `hsnCode*`, `gstRate* (0..100)`, `active`.
- `CatalogProductCartonSizeRequest`: `size*`, `piecesPerCarton* (>0)`.
- `CatalogProductDto`: `id`, `publicId`, `brandId`, `brandName`, `brandCode`, `name`, `sku`, `colors[]`, `sizes[]`, `cartonSizes[]`, `unitOfMeasure`, `hsnCode`, `gstRate`, `active`.
- `CatalogProductCartonSizeDto`: `size`, `piecesPerCarton`.
- `CatalogProductBulkItemRequest`: `id?`, `sku?`, `product*`.
- `CatalogProductBulkItemResult`: `index`, `success`, `action`, `productId`, `sku`, `message`, `product`.
- `CatalogProductBulkResponse`: `total`, `succeeded`, `failed`, `results[]`.
- `PageResponse<CatalogProductDto>`: `content`, `totalElements`, `totalPages`, `page`, `size`.
- `ProductionBrandDto` (read-model): `id`, `publicId`, `name`, `code`, `productCount`.
- `ProductionProductDto` (read-model): `id`, `publicId`, `brandId`, `brandName`, `brandCode`, `productName`, `category`, `defaultColour`, `sizeLabel`, `unitOfMeasure`, `skuCode`, `active`, pricing/tax fields, `metadata`.

##### Inventory + dispatch DTOs

- `FinishedGoodRequest`: `productCode*`, `name*`, `unit`, `costingMethod`, account IDs.
- `FinishedGoodDto`: identity + stock totals + costing/account fields.
- `FinishedGoodBatchRequest`: `finishedGoodId*`, `batchCode`, `quantity* (>0)`, `unitCost* (>=0)`, `manufacturedAt`, `expiryDate`.
- `FinishedGoodBatchDto`: identity + `batchCode`, quantities, cost, manufacture/expiry dates.
- `FinishedGoodLowStockThresholdRequest`: `threshold* (>=0)`.
- `FinishedGoodLowStockThresholdDto`: `finishedGoodId`, `productCode`, `threshold`.
- `StockSummaryDto`: shared stock rollup (`currentStock`, `reservedStock`, `availableStock`, `weightedAverageCost`, batch/material counters).
- `RawMaterialRequest`: `name*`, `sku`, `unitType*`, `reorderLevel*`, `minStock*`, `maxStock*`, `inventoryAccountId`, `costingMethod`.
- `RawMaterialDto`: identity + stock levels + status + accounting/costing metadata.
- `RawMaterialBatchRequest`: `batchCode`, `quantity*`, `unit*`, `costPerUnit*`, `supplierId*`, `notes`.
- `RawMaterialBatchDto`: identity + batch/supplier/quantity/cost fields.
- `RawMaterialIntakeRequest`: `rawMaterialId*`, `batchCode`, `quantity*`, `unit*`, `costPerUnit*`, `supplierId*`, `notes`.
- `InventoryStockSnapshot`: `name`, `sku`, `currentStock`, `reorderLevel`, `status`.
- `InventoryAdjustmentRequest`: `adjustmentDate`, `type* (DAMAGED|SHRINKAGE|OBSOLETE)`, `adjustmentAccountId*`, `reason`, `adminOverride`, `idempotencyKey*`, `lines*`.
- `InventoryAdjustmentRequest.LineRequest`: `finishedGoodId*`, `quantity*`, `unitCost*`, `note`.
- `InventoryAdjustmentDto`: identity + `referenceNumber`, `adjustmentDate`, `type`, `status`, `reason`, `totalAmount`, `journalEntryId`, `lines[]`.
- `InventoryAdjustmentLineDto`: `finishedGoodId`, `finishedGoodName`, `quantity`, `unitCost`, `amount`, `note`.
- `InventoryBatchTraceabilityDto`: batch identity/type/item/source + quantity/cost + `movements[]`.
- `InventoryBatchMovementDto`: movement identity/type/qty/cost + `referenceType/referenceId` + linked journal/slip IDs.
- `OpeningStockImportResponse`: created counts + `errors[]` (`rowNumber`, `message`).
- `PackagingSlipDto`: slip identity + order/dealer + status/timestamps + journal links + `lines[]`.
- `PackagingSlipLineDto`: line batch/product/ordered/shipped/backorder/qty/cost/notes fields.
- `DispatchPreviewDto`: slip/order/dealer summary + `lines[]` with availability/suggested ship quantities.
- `DispatchConfirmationRequest`: `packagingSlipId*`, `lines*`, `notes`, `confirmedBy`, `overrideRequestId`.
- `DispatchConfirmationRequest.LineConfirmation`: `lineId*`, `shippedQuantity*`, `notes`.
- `DispatchConfirmationResponse`: slip + totals + `lines[]` + `backorderSlipId`.
- `DispatchConfirmationResponse.LineResult`: ordered/shipped/backorder quantities + costing and notes.

##### Manufacturing DTOs

- `ProductionPlanRequest`: `planNumber*`, `productName*`, `quantity*`, `plannedDate*`, `notes`.
- `ProductionPlanDto`: identity + plan/product/qty/date/status/notes.
- `ProductionBatchRequest` (legacy path): `batchNumber*`, `quantityProduced*`, `loggedBy`, `notes`.
- `ProductionBatchDto` (legacy path): identity + batch/qty/timestamp/user/notes.
- `ProductionLogRequest`: `brandId*`, `productId*`, `batchColour`, `batchSize*`, `unitOfMeasure`, `mixedQuantity*`, `producedAt`, `notes`, `createdBy`, `addToFinishedGoods`, `salesOrderId`, `laborCost`, `overheadCost`, `materials*`.
- `ProductionLogRequest.MaterialUsageRequest`: `rawMaterialId*`, `quantity* (>0)`, `unitOfMeasure`.
- `ProductionLogDto`: lifecycle summary with output, packed quantity, wastage, status, cost totals.
- `ProductionLogDetailDto`: `ProductionLogDto` fields + notes + `materials[]` + `packingRecords[]`.
- `ProductionLogMaterialDto`: raw material batch and movement linkage + quantity/cost fields.
- `ProductionLogPackingRecordDto`: packing output linkage (`finishedGoodId/batchId`, packaging size, packed quantity, packed metadata).
- `PackingRequest`: `productionLogId*`, `packedDate`, `packedBy`, `idempotencyKey`, `lines*`.
- `PackingLineRequest`: `packagingSize*`, `quantityLiters`, `piecesCount`, `boxesCount`, `piecesPerBox` (all positive when provided).
- `PackingRecordDto`: persisted packing record with line-level box/piece metadata.
- `UnpackedBatchDto`: production log quantities (`mixed`, `packed`, `remaining`) and status.
- `BulkPackRequest`: `bulkBatchId*`, `packs*`, `packagingMaterials`, `skipPackagingConsumption`, `packDate`, `packedBy`, `notes`, `idempotencyKey`.
- `BulkPackRequest.PackLine`: `childSkuId*`, `quantity*`, `sizeLabel`, `unit`.
- `BulkPackRequest.MaterialConsumption`: `materialId*`, `quantity*`, `unit`.
- `BulkPackResponse`: consumed bulk qty/cost/journal + created `childBatches[]`.
- `BulkPackResponse.ChildBatchDto`: child batch identity + SKU/size + qty/cost/value.
- `PackagingSizeMappingRequest`: `packagingSize`, `rawMaterialId`, `unitsPerPack`, `cartonSize`, `litersPerUnit`.
- `PackagingSizeMappingDto`: identity + mapping, raw material descriptors, activity flag.
- `FactoryTaskRequest` / `FactoryTaskDto`: task metadata (`title`, `assignee`, `status`, due/sales/slip linkage).
- `FactoryDashboardDto`: `productionEfficiency`, `completedPlans`, `batchesLogged`, `alerts`.
- `CostAllocationRequest`: period + labor/overhead + target account IDs + notes.
- `CostAllocationResponse`: allocation totals, affected journals, summary.
- `WastageReportDto`: wastage quantity/percentage/value by production log.
- `CostBreakdownDto`: per-log material/labor/overhead/unit cost split.
- `MonthlyProductionCostDto`: monthly totals (`liters`, costs, average/loss metrics).

#### UI Hints (frontend implementation)

- **Brand dropdown**: source from `GET /api/v1/catalog/brands?active=true`; store/use `brandId` only.
- **Color input**: chip-style multi-select; preserve order entered by user when sending `colors[]`.
- **Size grid inputs**: render one row per selected size with mandatory `piecesPerCarton`; send to `cartonSizes[]`.
- **HSN lookup**:
  - Backend currently validates/persists `hsnCode` but does not expose a dedicated HSN master endpoint.
  - Recommended UX: local searchable HSN dataset/autocomplete in UI + final backend validation on submit.
- **Product search/filter**: always use server pagination (`page`, `pageSize`); backend caps `pageSize` at 100.
- **Bulk upsert UX**: render `CatalogProductBulkResponse.results[]` row by row; retry only failed rows.
- **Dispatch confirm UI**: force explicit per-line shipped quantity entry (cannot exceed ordered quantity).
- **Slip status controls**: only expose `PENDING`, `PENDING_STOCK`, `PENDING_PRODUCTION`, `RESERVED`; do not expose direct set to `DISPATCHED/BACKORDER/CANCELLED`.
- **Idempotency-sensitive screens**: send stable idempotency keys for inventory adjustments, opening-stock import, raw-material intake/batch creation, and packing records.
- **Wastage dashboard**: combine `/reports/wastage` with `/reports/monthly-production-costs` for trend + variance cards.

### Sales & Dealers

#### Endpoint Map (sales + dealer management + dispatch)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/sales/orders` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_FACTORY`/`ROLE_ACCOUNTING` | Query: `status?`, `dealerId?`, `page=0..`, `size=1..200` | `List<SalesOrderDto>` |
| `GET` | `/api/v1/sales/orders/search` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_FACTORY`/`ROLE_ACCOUNTING` | Query: `status?`, `dealerId?`, `orderNumber?`, `fromDate?`, `toDate?`, `page=0..`, `size=1..200` (dates are ISO-8601 instants) | `PageResponse<SalesOrderDto>` |
| `POST` | `/api/v1/sales/orders` | `ROLE_SALES`/`ROLE_ADMIN` | `SalesOrderRequest` (+ optional `Idempotency-Key`/`X-Idempotency-Key`) | `SalesOrderDto` |
| `PUT` | `/api/v1/sales/orders/{id}` | `ROLE_SALES`/`ROLE_ADMIN` | `SalesOrderRequest` | `SalesOrderDto` |
| `DELETE` | `/api/v1/sales/orders/{id}` | `ROLE_SALES`/`ROLE_ADMIN` | — | `204 No Content` |
| `POST` | `/api/v1/sales/orders/{id}/confirm` | `ROLE_SALES`/`ROLE_ADMIN` | — | `SalesOrderDto` |
| `POST` | `/api/v1/sales/orders/{id}/cancel` | `ROLE_SALES`/`ROLE_ADMIN` | `CancelRequest { reasonCode, reason }` (`reasonCode` required by business rule) | `SalesOrderDto` |
| `PATCH` | `/api/v1/sales/orders/{id}/status` | `ROLE_SALES`/`ROLE_ADMIN` | `StatusRequest { status }` (manual statuses only) | `SalesOrderDto` |
| `GET` | `/api/v1/sales/orders/{id}/timeline` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_FACTORY`/`ROLE_ACCOUNTING` | — | `List<SalesOrderStatusHistoryDto>` |
| `GET` | `/api/v1/dealers` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | — | `List<DealerResponse>` |
| `POST` | `/api/v1/dealers` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | `CreateDealerRequest` | `DealerResponse` |
| `PUT` | `/api/v1/dealers/{dealerId}` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | `CreateDealerRequest` | `DealerResponse` |
| `GET` | `/api/v1/dealers/search` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | Query: `query?`, `status?`, `region?`, `creditStatus?` | `List<DealerLookupResponse>` |
| `GET` | `/api/v1/sales/dealers/search` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | Query: `query?`, `status?`, `region?`, `creditStatus?` | `List<DealerLookupResponse>` |
| `GET` | `/api/v1/dealers/{dealerId}/credit-utilization` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealers/{dealerId}/aging` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | — | `Map<String,Object>` |
| `POST` | `/api/v1/dealers/{dealerId}/dunning/hold` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | Query: `overdueDays` (default `45`), `minAmount` (default `0`) | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/dashboard` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/ledger` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/invoices` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/aging` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/orders` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `POST` | `/api/v1/dealer-portal/credit-requests` | `ROLE_DEALER` | `DealerPortalCreditRequestCreateRequest` | `CreditRequestDto` |
| `GET` | `/api/v1/dealer-portal/invoices/{invoiceId}/pdf` | `ROLE_DEALER` | — | `application/pdf` |
| `GET` | `/api/v1/dispatch/preview/{slipId}` | `ROLE_ADMIN`/`ROLE_FACTORY` | — | `DispatchPreviewDto` |
| `POST` | `/api/v1/sales/dispatch/confirm` | `ROLE_FACTORY`/`ROLE_ADMIN` + `dispatch.confirm` | `DispatchConfirmRequest` | `DispatchConfirmResponse` |

#### User Flows

1. **Create + reserve order**
   1. `POST /api/v1/sales/orders` with line items and totals.
   2. Backend creates order in `DRAFT`, attempts reservation, then transitions to:
      - `RESERVED` when reservation has no shortages, or
      - `PENDING_PRODUCTION` when shortages exist.
   3. UI should refresh with `GET /api/v1/sales/orders/search` and show resulting status.

2. **Confirm order (credit + stock checks)**
   1. `POST /api/v1/sales/orders/{id}/confirm`.
   2. Backend enforces credit limit and requires at least partial reserved stock.
   3. Success transitions to `CONFIRMED`; timeline records reason code `ORDER_CONFIRMED`.

3. **Dealer onboarding + credit visibility**
   1. Create/update dealer using `POST /api/v1/dealers` or `PUT /api/v1/dealers/{dealerId}` with GST + payment terms + region fields.
   2. Search from sales screen with `GET /api/v1/sales/dealers/search?query=&status=&region=&creditStatus=`.
   3. For dealer risk cards load `GET /api/v1/dealers/{dealerId}/credit-utilization` and `GET /api/v1/dealers/{dealerId}/aging`.
   4. Trigger manual hold guardrail using `POST /api/v1/dealers/{dealerId}/dunning/hold?overdueDays=45&minAmount=0`.

4. **Dealer portal self-service (dealer-authenticated)**
   1. Load summary from `GET /api/v1/dealer-portal/dashboard` (includes `creditStatus`, `pendingOrderExposure`, aging buckets).
   2. Load detailed ledgers/invoices/orders from `/ledger`, `/invoices`, `/orders`.
   3. Load overdue details from `GET /api/v1/dealer-portal/aging`.
   4. Dealers submit limit requests via `POST /api/v1/dealer-portal/credit-requests` and can download invoice PDFs via `/invoices/{invoiceId}/pdf`.

5. **Dispatch reserve -> preview -> confirm with GST breakdown**
   1. Reserve inventory during order creation/confirmation.
   2. Open modal with `GET /api/v1/dispatch/preview/{slipId}` and render per-line pricing/tax totals plus aggregate GST breakdown.
   3. Confirm financial dispatch via `POST /api/v1/sales/dispatch/confirm`.
   4. Use `DispatchConfirmResponse.gstBreakdown` to render final invoice-tax summary on success toast/detail page.

6. **Cancel order with reason code**
   1. UI collects structured reason code + optional free-text reason.
   2. `POST /api/v1/sales/orders/{id}/cancel` with `{ reasonCode, reason }`.
   3. Backend allows cancellation only from `DRAFT`/`CONFIRMED` and records timeline entry with supplied reason code.

7. **Track lifecycle timeline**
   1. `GET /api/v1/sales/orders/{id}/timeline`.
   2. Render chronological transition history (`fromStatus`, `toStatus`, `reasonCode`, `reason`, `changedBy`, `changedAt`).

8. **Search/filter orders**
   1. `GET /api/v1/sales/orders/search` with any combination of `status`, `dealerId`, `orderNumber`, date range, page/size.
   2. Use `PageResponse.totalElements/totalPages/page/size` for pagination controls.

#### Sales Order State Machine

Canonical order lifecycle exposed to frontend:

- `DRAFT` -> `RESERVED` (auto, stock fully reserved on create)
- `DRAFT` -> `PENDING_PRODUCTION` (auto, shortage on create)
- `DRAFT`/`RESERVED`/`PENDING_PRODUCTION`/`PENDING_INVENTORY`/`READY_TO_SHIP`/`PROCESSING` -> `CONFIRMED` (confirm endpoint)
- `CONFIRMED`/`PROCESSING`/`RESERVED`/`PENDING_PRODUCTION`/`PENDING_INVENTORY`/`READY_TO_SHIP` -> `DISPATCHED` (dispatch progression)
- `DISPATCHED` -> `INVOICED` (invoice marker present after dispatch)
- `INVOICED` -> `SETTLED` -> `CLOSED` (downstream finance lifecycle)
- `DRAFT`/`CONFIRMED` -> `CANCELLED` (cancel endpoint, reason code required)

Legacy compatibility mapping still accepted in responses/queries:
- `BOOKED` => `DRAFT`
- `SHIPPED`/`FULFILLED` => `DISPATCHED`
- `COMPLETED` => `SETTLED`
- `PENDING` => `DRAFT`
- `APPROVED` => `CONFIRMED`

#### Error Codes (sales + dealer + dispatch relevant)

- `VAL_001` (`VALIDATION_INVALID_INPUT`)
  - Invalid search date format, unknown/unsupported status inputs, invalid manual transition requests.
  - Invalid dealer credit filter (`creditStatus` must be one of `WITHIN_LIMIT | NEAR_LIMIT | OVER_LIMIT`).
  - Invalid GST/state validation (`gstNumber` not GSTIN-compliant, `stateCode` not 2-char code).
- `VAL_002` (`VALIDATION_MISSING_REQUIRED_FIELD`)
  - Missing cancellation reason code for cancel request.
- `BUS_001` (`BUSINESS_INVALID_STATE`)
  - Invalid transition (e.g., cancel from dispatched/invoiced states, illegal lifecycle jumps).
- `VAL_007` (`VALIDATION_INVALID_STATE`)
  - Operation blocked due to immutable/posting-locked order state.
  - Dispatch preview requested for an already dispatched slip.
- `VAL_003` (`VALIDATION_INVALID_REFERENCE`)
  - Dealer/sales-order/slip linkage missing for credit or dispatch operations.

Frontend behavior: treat these as non-retryable user/action-state errors; surface message inline and refresh entity state.

#### Data Contracts

- `CreateDealerRequest`
  - `name: string` (required)
  - `companyName: string` (required)
  - `contactEmail: string` (required, valid email)
  - `contactPhone: string` (required)
  - `address?: string`
  - `creditLimit?: decimal` (>=0)
  - `gstNumber?: string` (15-char GSTIN format)
  - `stateCode?: string` (2-char state code)
  - `gstRegistrationType?: REGULAR | COMPOSITION | UNREGISTERED` (default `UNREGISTERED`)
  - `paymentTerms?: NET_30 | NET_60 | NET_90` (default `NET_30`)
  - `region?: string` (normalized uppercase)

- `DealerResponse`
  - Existing dealer identity/contact/balance fields plus:
  - `gstNumber?: string`
  - `stateCode?: string`
  - `gstRegistrationType: REGULAR | COMPOSITION | UNREGISTERED`
  - `paymentTerms: NET_30 | NET_60 | NET_90`
  - `region?: string`

- `DealerLookupResponse`
  - Existing lightweight dealer lookup fields plus:
  - `stateCode?: string`
  - `gstRegistrationType: REGULAR | COMPOSITION | UNREGISTERED`
  - `paymentTerms: NET_30 | NET_60 | NET_90`
  - `region?: string`
  - `creditStatus: WITHIN_LIMIT | NEAR_LIMIT | OVER_LIMIT`

- `Dealer credit utilization payload` (`GET /api/v1/dealers/{dealerId}/credit-utilization`)
  - `dealerId`, `dealerName`
  - `creditLimit`, `outstandingAmount`, `pendingOrderExposure`, `creditUsed`, `availableCredit`
  - `creditStatus`

- `Dealer aging payload` (`GET /api/v1/dealers/{dealerId}/aging` and `/api/v1/dealer-portal/aging`)
  - `dealerId`, `dealerName`
  - `totalOutstanding`
  - `agingBuckets` (`current`, `1-30 days`, `31-60 days`, `61-90 days`, `90+ days`)
  - `overdueInvoices[]`

- `DispatchPreviewDto`
  - Existing slip/order summary + `lines[]`
  - `lines[]` now include `unitPrice`, `lineSubtotal`, `lineTax`, `lineTotal`
  - New aggregate `gstBreakdown { taxableAmount, cgst, sgst, igst, totalTax, grandTotal }`

- `DispatchConfirmResponse`
  - Existing `packingSlipId/salesOrderId/finalInvoiceId/arJournalEntryId/cogsPostings/dispatched/arPostings`
  - New `gstBreakdown { taxableAmount, cgst, sgst, igst, totalTax }`

- `SalesOrderSearchFilters` (query-model used by backend)
  - `status?: string` (canonicalized on backend)
  - `dealerId?: number`
  - `orderNumber?: string` (contains search)
  - `fromDate?: string` (ISO-8601 instant)
  - `toDate?: string` (ISO-8601 instant)
  - `page: number` (>=0)
  - `size: number` (1..200)

- `SalesOrderDto`
  - `id: number`
  - `publicId: uuid`
  - `orderNumber: string`
  - `status: string` (canonical lifecycle state)
  - `totalAmount: decimal`
  - `subtotalAmount: decimal`
  - `gstTotal: decimal`
  - `gstRate: decimal`
  - `gstTreatment: string`
  - `gstInclusive: boolean`
  - `gstRoundingAdjustment: decimal`
  - `currency: string`
  - `dealerName?: string`
  - `traceId?: string`
  - `createdAt: instant`
  - `items: SalesOrderItemDto[]`
  - `timeline: SalesOrderStatusHistoryDto[]` (currently empty on list/detail payloads; use timeline endpoint for canonical history)

- `SalesOrderStatusHistoryDto`
  - `id: number`
  - `fromStatus?: string`
  - `toStatus: string`
  - `reasonCode?: string`
  - `reason?: string`
  - `changedBy: string`
  - `changedAt: instant`

- `CancelRequest`
  - `reasonCode?: string` (**required by business logic**)
  - `reason?: string`

#### UI Hints

- Use a dedicated cancel-reason-code dropdown (e.g., `CUSTOMER_REQUEST`, `CREDIT_BLOCK`, `PRICING_ISSUE`, etc.) + optional free-text details.
- Always call `/sales/orders/{id}/timeline` when opening an order detail drawer/page; do not rely on `SalesOrderDto.timeline` from list API.
- For search date filters, submit UTC ISO instants (`2026-01-01T00:00:00Z` format) to avoid timezone ambiguity.
- Treat `RESERVED`, `PENDING_PRODUCTION`, `PENDING_INVENTORY`, `READY_TO_SHIP`, and `PROCESSING` as in-progress operational states in UI badges.
- Dealer forms must include payment terms + region dropdown/input and normalize state code/GST client-side before submit for better UX.
- Dealer search table should expose independent filters: `status`, `region`, and `creditStatus`; do not derive `creditStatus` client-side.
- Dealer portal dashboard should highlight `creditStatus` using thresholds from backend response and show `pendingOrderExposure` alongside outstanding dues.
- Dispatch confirmation modal should render both per-line tax and aggregate GST cards from preview; confirmation success should read final `DispatchConfirmResponse.gstBreakdown` instead of reusing stale preview totals.

#### GST Fields

- Dealer create/update payloads support:
  - `gstNumber` (15-char GSTIN, optional)
  - `stateCode` (2-char Indian state code, optional)
  - `gstRegistrationType` (`REGULAR | COMPOSITION | UNREGISTERED`, optional; defaults to `UNREGISTERED`)

#### Sales Invoice GST Component Exposure

- Invoice line DTO now includes component fields:
  - `cgstAmount`
  - `sgstAmount`
  - `igstAmount`

These values are populated during dispatch confirmation and returned in invoice APIs.

### Purchasing & Suppliers

Comprehensive frontend handoff for `VAL-DOC-006` (supplier management, purchase orders, GRN lifecycle, purchase invoices, and purchase returns).

> Envelope convention: endpoints return `ApiResponse<T>` with payload under `data`.

#### Endpoint Map

##### Supplier endpoints (CRUD + approval + list/search)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/suppliers` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` or `ROLE_FACTORY` | none | `List<SupplierResponse>` |
| `GET` | `/api/v1/suppliers/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` or `ROLE_FACTORY` | path `id` | `SupplierResponse` |
| `POST` | `/api/v1/suppliers` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `SupplierRequest` | `SupplierResponse` |
| `PUT` | `/api/v1/suppliers/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` + `SupplierRequest` | `SupplierResponse` |
| `POST` | `/api/v1/suppliers/{id}/approve` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `SupplierResponse` |
| `POST` | `/api/v1/suppliers/{id}/activate` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `SupplierResponse` |
| `POST` | `/api/v1/suppliers/{id}/suspend` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `SupplierResponse` |

Search behavior today:
- Server-side search query params are not exposed on supplier endpoints.
- Frontend search should call `GET /api/v1/suppliers` and filter client-side by `code`, `name`, `status`, GST fields, etc.

##### Purchase order endpoints (current API coverage)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/purchasing/purchase-orders?supplierId={id?}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | optional query `supplierId` | `List<PurchaseOrderResponse>` |
| `GET` | `/api/v1/purchasing/purchase-orders/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `PurchaseOrderResponse` |
| `POST` | `/api/v1/purchasing/purchase-orders` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `PurchaseOrderRequest` | `PurchaseOrderResponse` |
| `POST` | `/api/v1/purchasing/purchase-orders/{id}/approve` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `PurchaseOrderResponse` |
| `POST` | `/api/v1/purchasing/purchase-orders/{id}/void` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` + `PurchaseOrderVoidRequest` | `PurchaseOrderResponse` |
| `POST` | `/api/v1/purchasing/purchase-orders/{id}/close` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `PurchaseOrderResponse` |
| `GET` | `/api/v1/purchasing/purchase-orders/{id}/timeline` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `List<PurchaseOrderStatusHistoryResponse>` |

Notes:
- PO lifecycle is now explicit: creation writes `DRAFT` only.
- Approval, void, and close are explicit transition endpoints.
- `POST /void` requires a reason code payload.

##### Goods receipt (GRN) endpoints

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/purchasing/goods-receipts?supplierId={id?}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | optional query `supplierId` | `List<GoodsReceiptResponse>` |
| `GET` | `/api/v1/purchasing/goods-receipts/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `GoodsReceiptResponse` |
| `POST` | `/api/v1/purchasing/goods-receipts` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `GoodsReceiptRequest` + `Idempotency-Key` header (or body key) | `GoodsReceiptResponse` |

Idempotency contract for GRN creation:
- Canonical header: `Idempotency-Key`
- Legacy header `X-Idempotency-Key` is explicitly rejected
- If both header/body keys exist, they must match

##### Purchase invoice + return endpoints (needed for full P2P and return flow)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/purchasing/raw-material-purchases?supplierId={id?}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | optional query `supplierId` | `List<RawMaterialPurchaseResponse>` |
| `GET` | `/api/v1/purchasing/raw-material-purchases/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `RawMaterialPurchaseResponse` |
| `POST` | `/api/v1/purchasing/raw-material-purchases` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `RawMaterialPurchaseRequest` | `RawMaterialPurchaseResponse` |
| `POST` | `/api/v1/purchasing/raw-material-purchases/returns` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `PurchaseReturnRequest` | `JournalEntryDto` |

#### User Flows (Frontend API sequences)

1. **Supplier onboarding flow**
   1. `POST /api/v1/suppliers` with supplier master + optional GST/bank/payment data
   2. `POST /api/v1/suppliers/{id}/approve` (`PENDING -> APPROVED`)
   3. `POST /api/v1/suppliers/{id}/activate` (`APPROVED -> ACTIVE`)
   4. Refresh list/detail: `GET /api/v1/suppliers` or `GET /api/v1/suppliers/{id}`

2. **Create PO flow (select supplier -> add items -> approve)**
   1. Load suppliers: `GET /api/v1/suppliers`
   2. Enforce active-only supplier selection in UI
   3. Build PO lines (raw material, qty, unit, cost)
   4. Submit draft PO: `POST /api/v1/purchasing/purchase-orders`
   5. Backend persists PO in `DRAFT`
   6. Approve explicitly: `POST /api/v1/purchasing/purchase-orders/{id}/approve` (`DRAFT -> APPROVED`)
   7. Read back: `GET /api/v1/purchasing/purchase-orders/{id}`
   8. Optional timeline render: `GET /api/v1/purchasing/purchase-orders/{id}/timeline`

3. **Receive goods flow (GRN)**
   1. Load PO: `GET /api/v1/purchasing/purchase-orders/{id}`
   2. Ensure PO is `APPROVED` before showing GRN submit action
   3. Determine remaining per line (ordered - already received from prior GRNs)
   4. Submit GRN: `POST /api/v1/purchasing/goods-receipts` with `Idempotency-Key`
   5. Refresh GRN list/detail: `GET /api/v1/purchasing/goods-receipts` / `{id}`
   6. Observe PO status auto-transition to `PARTIALLY_RECEIVED` or `FULLY_RECEIVED`
   7. Optionally render PO timeline to explain transition reason codes (`GOODS_RECEIPT_PARTIAL`, `GOODS_RECEIPT_COMPLETED`)

4. **Post purchase invoice flow (required before final close)**
   1. Select supplier + GRN to invoice
   2. Submit: `POST /api/v1/purchasing/raw-material-purchases`
   3. Backend links GRN + PO + journal entry and sets GRN status to `INVOICED`
   4. PO becomes:
      - `INVOICED` when some GRNs remain uninvoiced
      - `CLOSED` automatically when all GRNs under the PO are invoiced (via internal `INVOICED -> CLOSED` transition)
   5. Manual close endpoint `POST /api/v1/purchasing/purchase-orders/{id}/close` is available for the canonical `INVOICED -> CLOSED` transition and rejects non-`INVOICED` states

5. **Process return flow**
   1. Load purchases to pick return candidate: `GET /api/v1/purchasing/raw-material-purchases?supplierId={id}`
   2. Submit return: `POST /api/v1/purchasing/raw-material-purchases/returns`
   3. Backend validates returnable qty + outstanding payable, creates corrective journal, and reverses inventory movement
   4. Refresh purchase to show updated `outstandingAmount` / status: `GET /api/v1/purchasing/raw-material-purchases/{id}`

#### State Machines

##### Supplier lifecycle

- `PENDING -> APPROVED` via `POST /api/v1/suppliers/{id}/approve`
- `APPROVED -> ACTIVE` via `POST /api/v1/suppliers/{id}/activate`
- `ACTIVE -> SUSPENDED` via `POST /api/v1/suppliers/{id}/suspend`
- `SUSPENDED -> ACTIVE` via `POST /api/v1/suppliers/{id}/activate`

Guards:
- Approve allowed only from `PENDING`
- Suspend allowed only from `ACTIVE`
- Activate allowed only from `APPROVED` or `SUSPENDED`

##### Purchase order lifecycle

Persisted status enum: `DRAFT`, `APPROVED`, `PARTIALLY_RECEIVED`, `FULLY_RECEIVED`, `INVOICED`, `CLOSED`, `VOID`

Canonical transition graph (enforced server-side):
- `DRAFT -> APPROVED` via `POST /api/v1/purchasing/purchase-orders/{id}/approve`
- `DRAFT -> VOID` via `POST /api/v1/purchasing/purchase-orders/{id}/void`
- `APPROVED -> PARTIALLY_RECEIVED` automatically on partial GRN
- `APPROVED -> FULLY_RECEIVED` automatically when first GRN fully satisfies PO quantity
- `APPROVED -> VOID` via `POST /api/v1/purchasing/purchase-orders/{id}/void`
- `PARTIALLY_RECEIVED -> FULLY_RECEIVED` automatically when cumulative GRNs satisfy PO quantity
- `FULLY_RECEIVED -> INVOICED` automatically when invoice posting begins
- `INVOICED -> CLOSED` automatically when all GRNs are invoiced, or explicitly via `POST /api/v1/purchasing/purchase-orders/{id}/close`

Rejected transitions (non-exhaustive):
- `DRAFT -> PARTIALLY_RECEIVED/FULLY_RECEIVED/INVOICED/CLOSED`
- `APPROVED -> INVOICED/CLOSED`
- `PARTIALLY_RECEIVED -> VOID`
- Any transition from `VOID` or `CLOSED`
- No-op transitions (same state to same state)

History/timeline:
- Every status change is persisted in `purchase_order_status_history`.
- Query via `GET /api/v1/purchasing/purchase-orders/{id}/timeline`.
- Timeline fields: `fromStatus`, `toStatus`, `reasonCode`, `reason`, `changedBy`, `changedAt`.

##### Goods receipt lifecycle

Persisted status enum: `PARTIAL`, `RECEIVED`, `INVOICED`

Transitions:
- On GRN create:
  - `PARTIAL` when any PO line remains pending
  - `RECEIVED` when GRN completes all remaining PO quantities
- `PARTIAL/RECEIVED -> INVOICED` when GRN is linked to posted purchase invoice

#### Error Codes (Purchasing/Supplier) + Frontend Handling

| ErrorCode enum | Wire code | Typical purchasing trigger | Suggested frontend behavior |
|---|---|---|---|
| `VALIDATION_MISSING_REQUIRED_FIELD` | `VAL_002` | Missing GRN idempotency key, missing receipt date/request fields | Block submit, show inline field validation, keep form editable |
| `VALIDATION_INVALID_INPUT` | `VAL_001` | Duplicate lines, quantity/unit mismatch, over-receipt, invalid GST/tax contract, unsupported legacy JSON aliases | Highlight offending rows/fields using error details (`rawMaterialId`, quantities, units, alias names) |
| `VALIDATION_INVALID_REFERENCE` | `VAL_006` | Supplier/PO/GRN linkage mismatch or missing referenced entity | Refresh dependent selectors and force reselection |
| `BUSINESS_INVALID_STATE` | `BUS_001` | Creating PO for non-`ACTIVE` supplier, invalid supplier transition, invalid PO lifecycle transition (including no-op) | Disable invalid action buttons based on current status and refresh timeline |
| `BUSINESS_CONSTRAINT_VIOLATION` | `BUS_004` | PO non-receivable (`CLOSED`/`VOID`), already-invoiced GRN, duplicate lock/linkage rules | Show non-retryable toast/banner and reload latest entity state |
| `CONCURRENCY_CONFLICT` | `CONC_001` | Idempotency key reused with different payload; duplicate invoice/GRN linking race | Show stale/conflict dialog and ask user to refresh before retry |
| `RETURN_EXCEEDS_OUTSTANDING` | `BUS_009` | Return amount would drop purchase outstanding below zero | Keep return form open and display max returnable/outstanding guidance |

#### Data Contracts (DTOs)

##### Supplier DTOs

- **`SupplierRequest`**
  - `name: string` *(required, max 64)*
  - `code?: string` *(max 64; auto-generated from name if missing)*
  - `contactEmail?: email`
  - `contactPhone?: string` *(max 32)*
  - `address?: string` *(max 512)*
  - `creditLimit?: decimal` *(>= 0)*
  - `gstNumber?: string` *(GSTIN pattern: 15 chars, `^[0-9]{2}[A-Za-z0-9]{13}$`)*
  - `stateCode?: string` *(2 chars)*
  - `gstRegistrationType?: REGULAR | COMPOSITION | UNREGISTERED` *(defaults to `UNREGISTERED`)*
  - `paymentTerms?: NET_30 | NET_60 | NET_90` *(defaults to `NET_30`)*
  - `bankAccountName?: string` *(max 128)*
  - `bankAccountNumber?: string` *(max 64)*
  - `bankIfsc?: string` *(max 32)*
  - `bankBranch?: string` *(max 128)*

- **`SupplierResponse`**
  - `id: number`
  - `publicId: uuid`
  - `code: string`
  - `name: string`
  - `status: PENDING | APPROVED | ACTIVE | SUSPENDED`
  - `email?: string`
  - `phone?: string`
  - `address?: string`
  - `creditLimit: decimal`
  - `outstandingBalance: decimal`
  - `payableAccountId?: number`
  - `payableAccountCode?: string`
  - `gstNumber?: string`
  - `stateCode?: string`
  - `gstRegistrationType: REGULAR | COMPOSITION | UNREGISTERED`
  - `paymentTerms: NET_30 | NET_60 | NET_90`
  - `bankAccountName?: string` *(decrypted for response)*
  - `bankAccountNumber?: string` *(decrypted for response)*
  - `bankIfsc?: string` *(decrypted for response)*
  - `bankBranch?: string` *(decrypted for response)*

##### Purchase order DTOs

- **`PurchaseOrderRequest`**
  - `supplierId: number` *(required)*
  - `orderNumber: string` *(required, non-blank)*
  - `orderDate: date` *(required)*
  - `memo?: string`
  - `lines: PurchaseOrderLineRequest[]` *(required, non-empty)*

- **`PurchaseOrderLineRequest`**
  - `rawMaterialId: number` *(required)*
  - `quantity: decimal` *(required, > 0)*
  - `unit?: string`
  - `costPerUnit: decimal` *(required, > 0)*
  - `notes?: string`

- **`PurchaseOrderVoidRequest`**
  - `reasonCode: string` *(required, non-blank)*
  - `reason?: string`

- **`PurchaseOrderResponse`**
  - `id, publicId, orderNumber, orderDate`
  - `totalAmount: decimal`
  - `status: DRAFT | APPROVED | PARTIALLY_RECEIVED | FULLY_RECEIVED | INVOICED | CLOSED | VOID`
  - `memo?: string`
  - `supplierId, supplierCode, supplierName`
  - `createdAt: instant`
  - `lines: PurchaseOrderLineResponse[]`

- **`PurchaseOrderLineResponse`**
  - `rawMaterialId, rawMaterialName, quantity, unit, costPerUnit, lineTotal, notes`

- **`PurchaseOrderStatusHistoryResponse`**
  - `id: number`
  - `fromStatus?: string`
  - `toStatus: string`
  - `reasonCode?: string`
  - `reason?: string`
  - `changedBy: string`
  - `changedAt: instant`

##### Goods receipt DTOs

- **`GoodsReceiptRequest`**
  - `purchaseOrderId: number` *(required)*
  - `receiptNumber: string` *(required, non-blank)*
  - `receiptDate: date` *(required)*
  - `memo?: string`
  - `idempotencyKey?: string` *(can be supplied in body; header is canonical)*
  - `lines: GoodsReceiptLineRequest[]` *(required, non-empty)*

- **`GoodsReceiptLineRequest`**
  - `rawMaterialId: number` *(required)*
  - `batchCode?: string`
  - `quantity: decimal` *(required, > 0)*
  - `unit?: string`
  - `costPerUnit: decimal` *(required, > 0)*
  - `manufacturingDate?: date`
  - `expiryDate?: date`
  - `notes?: string`

- **`GoodsReceiptResponse`**
  - `id, publicId, receiptNumber, receiptDate`
  - `totalAmount: decimal`
  - `status: PARTIAL | RECEIVED | INVOICED`
  - `memo?: string`
  - `supplierId, supplierCode, supplierName`
  - `purchaseOrderId, purchaseOrderNumber`
  - `createdAt: instant`
  - `lines: GoodsReceiptLineResponse[]`

- **`GoodsReceiptLineResponse`**
  - `rawMaterialId, rawMaterialName, batchCode, quantity, unit, costPerUnit, lineTotal, notes`

##### Purchase invoice + return DTOs

- **`RawMaterialPurchaseRequest`**
  - `supplierId: number` *(required)*
  - `invoiceNumber: string` *(required)*
  - `invoiceDate: date` *(required)*
  - `memo?: string`
  - `purchaseOrderId?: number`
  - `goodsReceiptId: number` *(required)*
  - `taxAmount?: decimal` *(>= 0; mutually exclusive with line-level tax declarations)*
  - `lines: RawMaterialPurchaseLineRequest[]` *(required, non-empty)*

  Canonical JSON keys only:
  - Supported: `invoiceNumber`, `goodsReceiptId`
  - Explicitly rejected legacy aliases: `invoiceNo`, `invoice_no`, `goodsReceiptID`, `goods_receipt_id`, `goodsReceipt`, `grnId`

- **`RawMaterialPurchaseLineRequest`**
  - `rawMaterialId: number` *(required)*
  - `batchCode?: string`
  - `quantity: decimal` *(required, > 0 and must match GRN qty for same material)*
  - `unit?: string` *(must match GRN unit if GRN line exists)*
  - `costPerUnit: decimal` *(required, > 0 and must match GRN cost within tolerance)*
  - `taxRate?: decimal`
  - `taxInclusive?: boolean`
  - `notes?: string`

- **`RawMaterialPurchaseResponse`**
  - `id, publicId, invoiceNumber, invoiceDate`
  - `totalAmount, taxAmount, outstandingAmount`
  - `status` *(runtime values include `POSTED`, `PARTIAL`, `PAID`, `VOID`, `REVERSED` depending on settlement/returns)*
  - `memo?: string`
  - `supplierId, supplierCode, supplierName`
  - `purchaseOrderId, purchaseOrderNumber`
  - `goodsReceiptId, goodsReceiptNumber`
  - `journalEntryId?: number`
  - `createdAt: instant`
  - `lines: RawMaterialPurchaseLineResponse[]`

- **`RawMaterialPurchaseLineResponse`**
  - `rawMaterialId, rawMaterialName`
  - `rawMaterialBatchId?, batchCode?`
  - `quantity, unit, costPerUnit, lineTotal`
  - `taxRate?, taxAmount?`
  - `cgstAmount?, sgstAmount?, igstAmount?`
  - `notes?`

- **`PurchaseReturnRequest`**
  - `supplierId: number` *(required)*
  - `purchaseId: number` *(required)*
  - `rawMaterialId: number` *(required)*
  - `quantity: decimal` *(required, > 0)*
  - `unitCost: decimal` *(required, > 0)*
  - `referenceNumber?: string` *(optional idempotent reference for replay-safe requests)*
  - `returnDate?: date` *(defaults to company date if omitted)*
  - `reason?: string`

Return response:
- `POST /raw-material-purchases/returns` returns `JournalEntryDto` (see Accounting section for full schema).

#### UI Hints

- **Supplier onboarding UI**
  - Use staged actions: `Create -> Approve -> Activate`.
  - Disable invalid transition buttons based on current `status`.
  - Show payable account code from response so finance team can verify ledger linkage.

- **Supplier search/list UI**
  - Since backend has no dedicated query endpoint, implement client-side filtering on top of `GET /suppliers`.
  - Recommended quick filters: `status`, `paymentTerms`, `gstRegistrationType`, and text match over `code/name`.

- **PO creation UI**
  - Allow PO creation only for `ACTIVE` suppliers.
  - Use line-level validations before submit (positive qty/cost, no duplicate raw material lines).
  - New POs are `DRAFT`; show explicit **Approve** action wired to `POST /purchase-orders/{id}/approve`.
  - Show **Void** action only for `DRAFT` and `APPROVED`; require reason-code selection before submit.
  - Show **Close** action only when PO is `INVOICED`.
  - Render timeline drawer/tab from `GET /purchase-orders/{id}/timeline` so users can audit every transition.

- **GRN UI**
  - Always attach `Idempotency-Key` header on create.
  - Never send `X-Idempotency-Key`; show developer-facing warning if legacy integration attempts it.
  - Show per-line remaining quantity and hard-block over-receipt client-side.

- **Purchase invoice UI**
  - Invoice lines should mirror GRN lines 1:1 in material/qty/unit/cost.
  - Enforce tax-mode consistency (GST vs non-GST) in line editor before submit.
  - If using top-level `taxAmount`, disable line `taxRate`/`taxInclusive` inputs.

- **Return UI**
  - Show both *remaining returnable quantity* and *current outstanding amount* before submit.
  - Recompute max returnable amount client-side to reduce BUS_009 errors.
  - On success, refresh purchase detail to show reduced `outstandingAmount` and updated status.

- **GST display hints**
  - Render CGST/SGST/IGST columns in purchase line tables where available.
  - Split display by interstate rule:
    - same-state supplier/company -> CGST + SGST
    - cross-state -> IGST

### HR & Payroll
_To be documented_

### Reports
_To be documented_
