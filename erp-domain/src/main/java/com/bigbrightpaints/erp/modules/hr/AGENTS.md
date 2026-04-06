# HR / Payroll Module

Last reviewed: 2026-03-29

## Overview

The HR module owns employee management, leave, attendance, and payroll lifecycle. It is the payroll calculation truth boundary, but depends on the accounting module for payroll posting and payment.

## What This Module Owns

- **Employee management** — employee CRUD and lifecycle.
- **Leave management** — leave types, balances, and requests.
- **Attendance** — attendance tracking and records.
- **Payroll run lifecycle** — create, calculate, approve, post, and pay payroll runs.
- **Payroll calculation** — earnings, deductions, and statutory computation.

## Primary Controllers

- `HrPayrollController` — payroll run lifecycle endpoints.

## Key Services

- `PayrollRunService` — payroll run lifecycle with period+type idempotency.
- `PayrollCalculationService` — line-level earnings/deductions from attendance + statutory engines.
- `PayrollPostingService` — payroll posting to accounting and payroll payment processing.

## DTO Families

- `PayrollRun` / `PayrollRunLine` — payroll run representation.
- `Employee` — employee representation.
- `Attendance` — attendance representation.

## Cross-Module Boundaries

- **HR → Accounting:** payroll posting via `PayrollPostingService` → `AccountingFacade.postPayrollRun` (directly owned by accounting `PayrollAccountingService`), while payroll payment recording stays on the canonical accounting payroll-payments surface (`POST /api/v1/accounting/payroll/payments`).
- **HR → Company:** payroll module gating via `ModuleGatingService` (`HR_PAYROLL` is an optional module).
- **HR → Admin:** payroll-related admin settings and approvals.

## Module Gating

The HR/Payroll module is an **optional module** controlled by `ModuleGatingService`. It can be enabled or disabled per tenant. When disabled:

- `/api/v1/payroll/**` endpoints return `MODULE_DISABLED`.
- Admin approvals and portal HR metrics are gated.
- Orchestrator HR snapshots are gated.
- Accounting-period payroll diagnostics are gated.

## Canonical Documentation

For the full architecture reference, see:
- [docs/ARCHITECTURE.md](../../../../../../../docs/ARCHITECTURE.md)
- [docs/INDEX.md](../../../../../../../docs/INDEX.md)

## Known Limitations

- Payroll posting requires specific payroll accounts to be configured in the chart of accounts.
- The accounting-host payment seam is a cross-module boundary — payroll is not fully self-contained.
- Payroll module is currently paused by default for new tenants (ERP-33).
- Some legacy payroll endpoints may exist as compatibility aliases.

## Deprecated and Non-Canonical Surfaces

### Legacy Payroll Endpoints

Some legacy payroll endpoints may exist as compatibility aliases for older frontend versions. These are:

- **Legacy endpoint pattern**: Any `/api/v1/hr/**` endpoints (non-standard) that may exist alongside the canonical `/api/v1/payroll/**` endpoints
- **Canonical replacement**: Use `HrPayrollController` at `/api/v1/payroll/**` for all payroll operations
- **Idempotency**: The canonical path uses `Idempotency-Key` header for duplicate request protection

### Payroll Module Paused by Default

The HR/Payroll module is paused by default for new tenants (ERP-33). This is an intentional product decision, not a bug or incomplete implementation. To enable:

- Super-admin must enable the `HR_PAYROLL` module via tenant configuration
- Once enabled, all `/api/v1/payroll/**` endpoints become active

### Accounting-Host Payment Seam

Payroll is not fully self-contained - it depends on the accounting module for:
- **Payroll posting**: `PayrollPostingService` → `AccountingFacade.postPayrollRun()` → accounting `PayrollAccountingService`
- **Payroll payment**: Bank payment reference recording via the canonical accounting payroll-payments journal surface (`POST /api/v1/accounting/payroll/payments`)

There is no replacement for this seam - this is the canonical design. The HR module calculates payroll, but accounting owns the financial truth.

### Deprecated: Direct HR Endpoints

Any legacy direct HR endpoints (outside the standard payroll flow) should be considered deprecated. The canonical paths are:
- Payroll run lifecycle: `POST /api/v1/payroll/runs`, `POST /api/v1/payroll/runs/{id}/calculate`, `POST /api/v1/payroll/runs/{id}/approve`, `POST /api/v1/payroll/runs/{id}/post`, `POST /api/v1/payroll/runs/{id}/mark-paid`
