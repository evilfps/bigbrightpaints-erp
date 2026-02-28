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

#### Internal Service Structure (Refactor: no API contract change)

`AccountingService` is now a thin compatibility facade. Endpoint behavior and DTO contracts are unchanged, but backend responsibilities are split into focused services:

1. `JournalEntryService`
   - Journal listing, manual/system journal creation, reversal, cascade reversal, reference routing.
2. `DealerReceiptService`
   - Dealer receipt recording and split/hybrid receipt posting flows.
3. `SettlementService`
   - Dealer/supplier settlement posting, supplier payment posting, allocation orchestration.
4. `CreditDebitNoteService`
   - Credit note, debit note, accrual, and bad-debt write-off journal workflows.
5. `AccountingAuditService`
   - Audit digest generation and CSV digest export routing.
6. `AccountingIdempotencyService`
   - Idempotency-backed reservation/replay handling for accounting mutation flows.
7. `InventoryAccountingService`
   - Landed-cost posting, inventory revaluation, and WIP adjustment posting.

#### Controller Wiring Update

- `AccountingController` now delegates accounting endpoints to the focused services above (journal, receipt, settlement, notes, audit, inventory).
- Account-list and account-create endpoints continue through `AccountingService` facade for backward compatibility.
- **Frontend impact:** none expected. Existing paths, payloads, response envelopes, and error shapes remain unchanged.

#### Accounting Period Costing Method (period-scoped)

##### Endpoint Map

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/accounting/periods` | Authenticated accounting user | None | `List<AccountingPeriodDto>` |
| POST | `/api/v1/accounting/periods` | Authenticated accounting user | `AccountingPeriodUpsertRequest` | `AccountingPeriodDto` |
| PUT | `/api/v1/accounting/periods/{periodId}` | Authenticated accounting user | `AccountingPeriodUpdateRequest` | `AccountingPeriodDto` |

`POST /periods` accepts `costingMethod` as optional. If omitted/null, backend defaults to `WEIGHTED_AVERAGE` for backward compatibility.

##### Costing Policy (critical behavior)

- Allowed values: `FIFO`, `LIFO`, `WEIGHTED_AVERAGE`.
- Costing method is resolved from the **active accounting period** at movement time.
- Method applies to inventory movements posted in that period (dispatch and inventory adjustments).
- **No retroactive revaluation:** changing method in a new period does not recalculate or mutate historical movement costs from prior periods.

##### User Flow: Change costing for next period

1. Load periods via `GET /api/v1/accounting/periods`.
2. Upsert/create the target period via `POST /api/v1/accounting/periods` with `{ year, month, costingMethod }`.
3. If period already exists, update only method via `PUT /api/v1/accounting/periods/{periodId}`.
4. New dispatch/adjustment transactions in that period use the new method automatically.

##### Data Contracts

- `AccountingPeriodUpsertRequest`
  - `year: number` (required, `1900..9999`)
  - `month: number` (required, `1..12`)
  - `costingMethod?: "FIFO" | "LIFO" | "WEIGHTED_AVERAGE"` (optional, defaults to `WEIGHTED_AVERAGE`)

- `AccountingPeriodUpdateRequest`
  - `costingMethod: "FIFO" | "LIFO" | "WEIGHTED_AVERAGE"` (required)

- `AccountingPeriodDto`
  - Includes `costingMethod: "FIFO" | "LIFO" | "WEIGHTED_AVERAGE"` for frontend display and filtering.

##### Error Handling

- `400 VALIDATION_INVALID_INPUT` for invalid month/year or missing `costingMethod` on `PUT`.
- `400 VALIDATION_INVALID_REFERENCE` when period id is unknown for the tenant/company.

##### UI Hints

- Use a radio/select control for costing method (three fixed enum options).
- On period list screens, show costing method badge per row to make method transitions visible.
- Show an inline note: “Changes affect only new movements in this period; prior periods are not revalued.”

#### GST Tax Flows (India)

##### Endpoint Map

| Method | Path | Auth | Query | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/accounting/gst/return` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | `period=YYYY-MM` (optional) | `GstReturnDto` |
| GET | `/api/v1/accounting/gst/reconciliation` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | `period=YYYY-MM` (optional) | `GstReconciliationDto` |

##### Flow

1. Load `gst/reconciliation` for month dashboard (component split).
2. Load `gst/return` for filing summary (`outputTax`, `inputTax`, `netPayable`).
3. Use same selected period (`YYYY-MM`) in both API calls.

##### Data Contracts

- `GstReturnDto`
  - `period: YYYY-MM`
  - `periodStart: date`
  - `periodEnd: date`
  - `outputTax: number`
  - `inputTax: number`
  - `netPayable: number`

- `GstReconciliationDto`
  - `period: YYYY-MM`
  - `periodStart: date`
  - `periodEnd: date`
  - `collected: { cgst, sgst, igst, total }`
  - `inputTaxCredit: { cgst, sgst, igst, total }`
  - `netLiability: { cgst, sgst, igst, total }`

##### Error Handling

- `400 VALIDATION_INVALID_DATE` when `period` is in the future.
- `400 VALIDATION_INVALID_INPUT` in non-GST mode if GST accounts are configured inconsistently.

##### UI Hints

- Show component cards for CGST/SGST/IGST and one total card.
- Display negative `netLiability.total` as input credit carry-forward.
- Default period picker to current month when period query is omitted.

#### Hands-off Settlement, Period Close, and Reconciliation

##### Endpoint Map

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| POST | `/api/v1/accounting/dealers/{dealerId}/auto-settle` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | `AutoSettlementRequest` | `PartnerSettlementResponse` |
| POST | `/api/v1/accounting/suppliers/{supplierId}/auto-settle` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | `AutoSettlementRequest` | `PartnerSettlementResponse` |
| GET | `/api/v1/accounting/month-end/checklist` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | Query: `periodId?` | `MonthEndChecklistDto` |
| POST | `/api/v1/accounting/periods/{periodId}/close` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | `AccountingPeriodCloseRequest` | `AccountingPeriodDto` |
| POST | `/api/v1/accounting/periods/{periodId}/reopen` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | `AccountingPeriodReopenRequest` | `AccountingPeriodDto` |
| POST | `/api/v1/accounting/reconciliation/bank` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | `BankReconciliationRequest` | `BankReconciliationSummaryDto` |
| GET | `/api/v1/accounting/reconciliation/subledger` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | None | `SubledgerReconciliationReport` |

##### User Flows

1. **Dealer auto-settlement (FIFO):**
   1. Submit `POST /dealers/{dealerId}/auto-settle` with `amount` (and optional `cashAccountId`, `referenceNumber`, `memo`, `idempotencyKey`).
   2. Backend allocates against oldest outstanding invoices first.
   3. Backend creates receipt journal + settlement allocation rows in one transaction.
   4. UI receives `PartnerSettlementResponse.allocations[]` for receipt allocation breakdown.

2. **Supplier auto-settlement (FIFO):**
   1. Submit `POST /suppliers/{supplierId}/auto-settle` with settlement amount.
   2. Backend allocates against oldest outstanding purchases first.
   3. Backend creates supplier payment journal + allocation rows atomically.

3. **Period close checklist + close/reopen:**
   1. Load checklist via `GET /month-end/checklist?periodId={id}`.
   2. Block close until checklist items are complete (including `trialBalanceBalanced`, reconciliation controls, and unposted/unlinked checks).
   3. Close via `POST /periods/{periodId}/close` with reason (`note`).
   4. Reopen via `POST /periods/{periodId}/reopen` with reason. Backend stores reopen audit fields (`reopenedAt`, `reopenedBy`, `reopenReason`).

4. **Reconciliation diagnostics:**
   1. Run `POST /reconciliation/bank` with statement info and optional cleared refs; show unmatched deposits/checks and difference.
   2. Run `GET /reconciliation/subledger` to compare AR/AP GL totals against dealer/supplier sub-ledgers and render discrepancy rows.

##### Data Contracts

- `AutoSettlementRequest`
  - `cashAccountId?: number` (optional; backend can resolve default active cash/bank account)
  - `amount: number` (required, `>= 0.01`)
  - `referenceNumber?: string`
  - `memo?: string`
  - `idempotencyKey?: string`

- `MonthEndChecklistItemDto.key` now includes `trialBalanceBalanced` with pass/fail details.

- `BankReconciliationSummaryDto`
  - `ledgerBalance`, `statementEndingBalance`, `outstandingDeposits`, `outstandingChecks`, `difference`, `balanced`
  - `unclearedDeposits[]` and `unclearedChecks[]` include reference/date/memo/debit/credit/net for UI discrepancy tables.

- `SubledgerReconciliationReport`
  - `dealerReconciliation` (AR vs dealer ledger totals + per-dealer discrepancies)
  - `supplierReconciliation` (AP vs supplier ledger totals + per-supplier discrepancies)
  - `combinedVariance`, `reconciled`

##### Error Codes / UI Behavior

- `VALIDATION_INVALID_INPUT` for over-allocation or missing open items in auto-settle.
- `VALIDATION_MISSING_REQUIRED_FIELD` when no `cashAccountId` provided and no default active cash/bank account exists.
- `VALIDATION_INVALID_REFERENCE` when dealer/supplier/account is not found.
- `VALIDATION_INVALID_STATE` for close attempts when checklist controls fail (trial balance mismatch, unreconciled controls, unposted/unlinked documents).

##### UI Hints

- Auto-settle dialogs should show amount-first UX with optional cash-account override.
- In settlement success toasts, include count of allocations applied.
- Period-close screens should render checklist as strict pass/fail rows and disable close CTA until all rows pass (unless using explicit force flow).
- Reconciliation pages should highlight discrepancy rows and show variance badges (`within tolerance` vs `exceeds tolerance`).

#### Accounting Compliance Audit Trail

##### Endpoint Map

| Method | Path | Auth | Query Params | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/accounting/audit-trail` | `ROLE_ADMIN`,`ROLE_ACCOUNTING` | `from?`, `to?` (ISO date), `user?`, `actionType?`, `entityType?`, `page=0..`, `size<=200` | `PageResponse<AccountingAuditTrailEntryDto>` |

##### Filter Behavior

- `from` / `to`: inclusive date range window on event timestamp.
- `user`: matches `actorIdentifier` (typically email/username) or numeric actor user id.
- `actionType`: exact action key (for example `MANUAL_JOURNAL_CREATED`, `JOURNAL_REVERSED`, `PERIOD_CLOSED`, `PERIOD_REOPENED`, `COSTING_METHOD_CHANGED`, `ACCOUNT_DEACTIVATED`).
- `entityType`: exact business entity key (`JOURNAL_ENTRY`, `ACCOUNTING_PERIOD`, `ACCOUNT`).
- Results are tenant/company scoped and sorted newest-first.

##### Data Contract

- `AccountingAuditTrailEntryDto`
  - `id: number`
  - `timestamp: string` (ISO-8601 UTC)
  - `companyId: number`
  - `companyCode: string`
  - `actorUserId: number | null`
  - `actorIdentifier: string` (email/username/system actor)
  - `actionType: string`
  - `entityType: string`
  - `entityId: string | null`
  - `referenceNumber: string | null`
  - `traceId: string | null`
  - `ipAddress: string | null`
  - `beforeState: string` (JSON snapshot)
  - `afterState: string` (JSON snapshot)
  - `sensitiveOperation: boolean`
  - `metadata: Record<string,string>`

##### UI Guidance

- Render audit rows in a compliance table with fixed columns: `timestamp`, `user`, `action`, `entity`, `traceId`, `sensitive`.
- Show `beforeState`/`afterState` in expandable JSON viewers.
- Highlight `sensitiveOperation=true` entries with warning styling/badge.
- Expose quick filters for common sensitive actions: manual journal, costing method change, account deactivation.
- Treat audit entries as immutable: UI should provide read-only views only (no edit/delete controls).

### Product Catalog & Inventory
_To be documented_

### Sales & Dealers

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

#### GST Fields

- Supplier create/update payloads support:
  - `gstNumber` (15-char GSTIN, optional)
  - `stateCode` (2-char Indian state code, optional)
  - `gstRegistrationType` (`REGULAR | COMPOSITION | UNREGISTERED`, optional; defaults to `UNREGISTERED`)

#### Purchase GST Component Exposure

- Raw material purchase line response now includes:
  - `cgstAmount`
  - `sgstAmount`
  - `igstAmount`

GST components are computed per line using company state vs supplier state:
- Same state => CGST + SGST split
- Different state => IGST

### HR & Payroll
_To be documented_

### Reports
_To be documented_
