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

### Product Catalog & Inventory
_To be documented_

### Sales & Dealers
_To be documented_

### Purchasing & Suppliers
_To be documented_

### HR & Payroll
_To be documented_

### Reports
_To be documented_
