# HR and Payroll Module

Last reviewed: 2026-03-30

## Overview

The HR module owns employee management, leave management, attendance tracking, and the complete payroll run lifecycle. It is the payroll calculation truth boundary but depends on the accounting module for payroll posting and payment recording. This module is an **optional module** controlled by tenant-level module gating.

## What This Module Owns

### Employee Management
- Employee CRUD operations: creation, update, deletion, and retrieval
- Employee types: STAFF (monthly salary) and LABOUR (daily wage)
- Payment schedules: MONTHLY (staff) and WEEKLY (labour)
- Employee status: ACTIVE, INACTIVE, etc.
- Salary structures: salary structure template assignment per employee
- Statutory details: PF number, ESI number, PAN number, tax regime (OLD/NEW)
- Bank details: account number, bank name, IFSC code (with encryption for sensitive data)
- Advance balance tracking for salary deductions

### Leave Management
- Leave type policies: configurable leave categories with entitlement rules
- Leave balances: per-employee leave entitlement tracking per year
- Leave requests: request submission, approval workflow, status updates
- Leave status transitions: PENDING → APPROVED/REJECTED

### Attendance Tracking
- Daily attendance marking: single employee and bulk marking
- Attendance import: bulk import from external systems
- Attendance summary: daily and monthly attendance reports
- Payroll linkage: attendance records linked to payroll runs for calculation

### Payroll Run Lifecycle
The payroll run follows a strict workflow with status progression:

| Status | Description | Next Status |
| --- | --- | --- |
| DRAFT | Payroll run created, no calculations done | CALCULATED |
| CALCULATED | Earnings and deductions computed per employee | APPROVED |
| APPROVED | Ready for accounting posting | POSTED |
| POSTED | Journal entries posted to accounting | PAID |
| PAID | Payment reference recorded, run complete | — (terminal) |
| CANCELLED | Run cancelled before completion | — (terminal) |

- **Create**: `POST /api/v1/payroll/runs`, `/api/v1/payroll/runs/weekly`, `/api/v1/payroll/runs/monthly`
- **Calculate**: `POST /api/v1/payroll/runs/{id}/calculate`
- **Approve**: `POST /api/v1/payroll/runs/{id}/approve`
- **Post to Accounting**: `POST /api/v1/payroll/runs/{id}/post`
- **Mark Paid**: `POST /api/v1/payroll/runs/{id}/mark-paid`

### Payroll Calculation
- **Earnings**: gross pay based on employee salary/wage and attendance
- **Deductions**:
  - PF (Provident Fund) deduction
  - ESI (Employee State Insurance) deduction
  - TDS (Tax Deducted at Source)
  - Professional Tax
  - Loan/advance recovery
  - Other deductions
- **Net Pay**: gross pay minus all deductions
- Statutory deduction calculation using Indian rules engine (`StatutoryDeductionEngine`)

## Primary Controllers

### HrController (`/api/v1/hr`)
- **Employee endpoints**: CRUD at `/api/v1/hr/employees`
- **Leave endpoints**: leave requests at `/api/v1/hr/leave-requests`, leave types at `/api/v1/hr/leave-types`, leave balances at `/api/v1/hr/employees/{employeeId}/leave-balances`
- **Attendance endpoints**: daily attendance at `/api/v1/hr/attendance/date/{date}`, summary at `/api/v1/hr/attendance/summary`, bulk operations at `/api/v1/hr/attendance/bulk-mark` and `/api/v1/hr/attendance/bulk-import`
- **Salary structure endpoints**: `/api/v1/hr/salary-structures`
- **Legacy payroll endpoints**: Return `410 GONE` with canonical path redirect to `/api/v1/payroll/runs`

### HrPayrollController (`/api/v1/payroll`)
- **Payroll run endpoints**: CRUD at `/api/v1/payroll/runs`, weekly runs at `/api/v1/payroll/runs/weekly`, monthly runs at `/api/v1/payroll/runs/monthly`
- **Payroll workflow**: calculate at `/api/v1/payroll/runs/{id}/calculate`, approve at `/api/v1/payroll/runs/{id}/approve`, post at `/api/v1/payroll/runs/{id}/post`, mark paid at `/api/v1/payroll/runs/{id}/mark-paid`
- **Pay summaries**: weekly at `/api/v1/payroll/summary/weekly`, monthly at `/api/v1/payroll/summary/monthly`, current period shortcuts

## Key Services/Facades

| Service | Responsibility |
| --- | --- |
| `EmployeeService` | Employee CRUD, employee type and salary resolution |
| `LeaveService` | Leave request workflow, balance computation, policy enforcement |
| `AttendanceService` | Attendance marking, bulk operations, summary generation |
| `PayrollRunService` | Payroll run lifecycle management, period+type idempotency |
| `PayrollCalculationService` | Line-level earnings/deductions computation, statutory calculation |
| `PayrollPostingService` | Payroll posting to accounting, payment recording |
| `SalaryStructureTemplateService` | Salary structure template CRUD |
| `StatutoryDeductionEngine` | Indian statutory deduction rules (PF, ESI, TDS, professional tax) |

## DTO Families

- `EmployeeDto` / `EmployeeRequest` — employee representation
- `LeaveRequestDto` / `LeaveRequestRequest` — leave request representation
- `LeaveBalanceDto` — leave balance per employee per year
- `AttendanceDto` / `MarkAttendanceRequest` — attendance records
- `PayrollRunDto` / `PayrollRunLineDto` — payroll run and line representation
- `SalaryStructureTemplateDto` / `SalaryStructureTemplateRequest` — salary structure templates

## Cross-Module Boundaries

### HR → Accounting (Payroll Posting Seam)
The HR module posts payroll journal entries to accounting via the **`AccountingFacade`** interface. This is the canonical accounting seam — do not reference `AccountingCoreEngineCore` directly.

```
PayrollPostingService.postPayrollToAccounting()
  └─> AccountingFacade.postPayrollRun(runNumber, runId, postingDate, memo, lines)
        └─> Creates journal entry in accounting
```

**Host**: `/api/v1/payroll/runs/{id}/post` (HR controller) → `/api/v1/accounting/journals` (Accounting)

The posting creates:
- Expense entry: SALARY-EXP or WAGE-EXP
- Liability entries: SALARY-PAYABLE, PF-PAYABLE, ESI-PAYABLE, TDS-PAYABLE, PROFESSIONAL-TAX-PAYABLE
- Asset entry (if applicable): EMP-ADV (loan/advance recovery)

### HR → Accounting (Payment Recording Seam)
Payroll payment is recorded on the accounting host after bank payment:

1. Payment journal created at `/api/v1/accounting/payroll/payments`
2. HR module marks payroll as PAID via `PayrollPostingService.markAsPaid()`
3. Payment reference is linked back to the payroll run

This is a two-phase process — posting creates the liability, payment recording clears it.

### HR → Company (Module Gating)
The HR/Payroll module is an **optional module** controlled by `ModuleGatingService`. When disabled:
- `/api/v1/payroll/**` endpoints return `MODULE_DISABLED` error
- HR-related admin metrics are hidden
- Orchestrator HR snapshots are skipped
- Accounting-period payroll diagnostics are hidden

Default state: paused for new tenants (ERP-33). Super-admin must enable via tenant configuration.

### HR → Inventory (Employee Attendance Context)
Attendance records are linked to payroll runs. The HR module reads attendance but does not modify inventory state.

## Required Accounts for Payroll Posting

Payroll posting requires specific accounts in the tenant's chart of accounts. These accounts must exist before payroll can be posted:

| Account Code | Account Type | Purpose |
| --- | --- | --- |
| SALARY-EXP | EXPENSE | Monthly salary expense |
| WAGE-EXP | EXPENSE | Daily wage expense |
| SALARY-PAYABLE | LIABILITY | Net salary payable to employees |
| EMP-ADV | ASSET | Employee advances/loans recoverable |
| PF-PAYABLE | LIABILITY | PF contribution payable |
| ESI-PAYABLE | LIABILITY | ESI contribution payable |
| TDS-PAYABLE | LIABILITY | Tax deducted at source payable |
| PROFESSIONAL-TAX-PAYABLE | LIABILITY | Professional tax payable |

If any required account is missing, posting fails with `VALIDATION_INVALID_REFERENCE` and a clear error message indicating which account code is required.

## Multi-Tenant Boundaries

- **Tenant isolation**: All HR data (employees, leave, attendance, payroll runs) is scoped to the current company via `CompanyContextService.requireCurrentCompany()`
- **No cross-tenant access**: Queries filter by `company_id` in all repositories
- **Module gating**: HR module can be enabled/disabled per tenant independently
- **Period context**: Payroll runs are tied to accounting periods; posting requires an open period

## Idempotency and Replay Expectations

### Payroll Run Idempotency
- **Period + Type uniqueness**: A payroll run is idempotent for a given period and run type (WEEKLY/MONTHLY)
- **Duplicate detection**: Creating a run for an existing period+type returns the existing run rather than creating a duplicate
- **Idempotency key support**: The `Idempotency-Key` header is supported for HTTP-level duplicate request protection on all `/api/v1/payroll/**` endpoints

### Payroll Calculation Idempotency
- Calculate is idempotent — recalculating an already-calculated run updates the lines
- Attendance data is read at calculation time; changes to attendance after calculation do not automatically recalculate

### Payroll Posting Idempotency
- Posting is idempotent — re-posting an already-posted run validates the existing journal link matches
- If status is POSTED but journal link is missing, posting fails with `BUSINESS_INVALID_STATE`
- If journal link exists but points to a different journal, posting fails with `CONCURRENCY_CONFLICT`

### Payroll Payment Idempotency
- Mark-as-paid is idempotent within a POSTED or PAID run
- Payment reference can be updated; the canonical reference comes from the accounting payment journal

## Deprecated and Non-Canonical Surfaces

### Legacy HR Endpoints (Deprecated)
**Status**: Deprecated

The `HrController` has legacy payroll endpoints that return `410 GONE`:
- `GET /api/v1/hr/payroll-runs` → redirects to `/api/v1/payroll/runs`
- `POST /api/v1/hr/payroll-runs` → redirects to `/api/v1/payroll/runs`

**Canonical path**: Use `HrPayrollController` at `/api/v1/payroll/**` for all payroll operations.

### Direct HR Endpoints Without Standard Flow
**Status**: Deprecated

Any `/api/v1/hr/**` endpoints that exist outside the standard payroll flow should be considered deprecated.

**Canonical paths**:
- Employee management: `/api/v1/hr/employees`
- Leave management: `/api/v1/hr/leave-requests`, `/api/v1/hr/leave-types`
- Attendance: `/api/v1/hr/attendance/**`
- Salary structures: `/api/v1/hr/salary-structures`

### Payroll Module Paused by Default
**Status**: Intentional product decision (ERP-33), not a bug

The HR/Payroll module is paused by default for new tenants. To enable:
1. Super-admin enables the `HR_PAYROLL` module via tenant configuration
2. All `/api/v1/payroll/**` endpoints become active

This is the canonical current state — there is no plan to enable by default.

### Accounting-Host Payment Seam
**Status**: Canonical design

Payroll is not fully self-contained — it depends on the accounting module for:
- **Payroll posting**: `PayrollPostingService` → `AccountingFacade.postPayrollRun()`
- **Payroll payment**: Bank payment reference recording via the canonical accounting payroll-payment journal surface (`/api/v1/accounting/payroll/payments`)

There is no replacement for this seam — this is the canonical design. The HR module calculates payroll, but accounting owns the financial truth.

## Current Definition of Done

The HR/Payroll module currently supports:

- ✓ Employee CRUD with types (STAFF/LABOUR) and payment schedules
- ✓ Leave management with balances and request workflow
- ✓ Attendance tracking with bulk operations
- ✓ Payroll run lifecycle (create → calculate → approve → post → mark-paid)
- ✓ Statutory deductions (PF, ESI, TDS, professional tax) for Indian context
- ✓ Payroll posting to accounting via `AccountingFacade`
- ✓ Payroll payment recording via accounting journal reference
- ✓ Module gating per tenant (optional module)
- ✓ Period+type idempotency for payroll runs

The module does **not** currently support:
- ✗ Payroll for non-Indian regulatory contexts (PF/ESI/TDS are India-specific)
- ✗ Automatic attendance-to-payroll sync after payroll calculation
- ✗ Self-service employee portal for leave requests (admin-only)
- ✗ Multi-currency payroll

## Cross-references

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/modules/MODULE-INVENTORY.md](MODULE-INVENTORY.md) — module inventory
- [docs/flows/FLOW-INVENTORY.md](../flows/FLOW-INVENTORY.md) — flow inventory
- [docs/flows/hr-payroll.md](../flows/hr-payroll.md) — canonical HR/payroll flow (behavioral entrypoint)
- [docs/workflows/payroll.md](../workflows/payroll.md) — operational workflow guide (historical reference)
- [docs/developer/accounting-flows/00-accounting-module-map.md](../developer/accounting-flows/00-accounting-module-map.md) — accounting module (payroll posting seam)
- [erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/AGENTS.md](../../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/AGENTS.md) — source module definition
- [docs/frontend-portals/accounting/README.md](../frontend-portals/accounting/README.md) — Accounting frontend handoff (HR and payroll accounting payloads, RBAC)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (legacy /hr/payroll-runs endpoints)
