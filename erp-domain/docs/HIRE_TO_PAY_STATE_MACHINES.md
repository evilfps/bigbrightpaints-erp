This file documents the current hire-to-pay (HR/payroll) state machines and invariants.
It reflects existing behavior only; no new flows are introduced.

# Payroll Run Statuses
Source: `modules/hr/domain/PayrollRun`, `PayrollService`.

States (enum `PayrollStatus` stored on `PayrollRun.status`):
- `DRAFT`: run created for a period; no lines or calculations are final.
- `CALCULATED`: payroll calculated from attendance and employee rates; lines populated.
- `APPROVED`: calculation accepted; ready for posting.
- `POSTED`: journal entry created and linked; attendance lines linked to the run.
- `PAID`: payments recorded; advance balances cleared per line; payment references stored.
- `CANCELLED`: exceptional terminal state (not produced by current service methods).

Transitions (current behavior):
- Create run -> `DRAFT` (weekly or monthly).
- Calculate -> `CALCULATED` (only from `DRAFT`; clears prior lines and rebuilds from attendance).
- Approve -> `APPROVED` (only from `CALCULATED`).
- Post to accounting -> `POSTED` (only from `APPROVED`):
  - builds a journal entry (reference `PAYROLL-<runNumber>`), posting date = period end clamped to `today`.
  - debits expense (salary or wage) for **net of advances**, credits salary payable for the same amount.
  - sets `journalEntryId`/reference on run; links attendance rows (`payrollRunId`).
- Mark paid -> `PAID` (only from `POSTED`):
  - sets line payment status = `PAID`, stores payment reference.
  - subtracts line advances from employee `advanceBalance`.

# Run Types
Source: `PayrollRun.RunType`.
- `WEEKLY`: labour payroll (week start Monday, end Saturday).
- `MONTHLY`: staff payroll (calendar month).

# Calculation Sources of Truth (used today)
- Attendance range = `[periodStart, periodEnd]` for the run.
- Present/half/absent/leave/holiday days drive base pay days and holiday pay.
- Hours (regular/OT/double OT) drive hourly-based overtime pay.
- Rates: `dailyRate`, `dailyWage`, `hourlyRate` derived from employee; OT multipliers from employee.
- Deductions: advance deduction capped at 20% of gross pay, limited to employee `advanceBalance`; PF currently zero in calculation service.
- Gross pay = base pay (daily rate * (present + 0.5 * half)) + overtime pay + holiday pay.
- Net pay = gross pay - total deductions (advance deduction only, today).
- Net pay = gross (base + OT + holiday) − deductions; run totals aggregate line values.

# Posting Semantics (current)
- Expense account: `SALARY-EXP` for monthly runs; `WAGE-EXP` for weekly runs.
- Liability: `SALARY-PAYABLE` credited for net pay (advances already netted).
- Journal is balanced; reference/memo prefixed with payroll run number.
- Posting date uses run period end; if in the future, clamped to company `today`.
- No cash/bank journal is created in this flow; payment handling is external.

# Payment and Clearing Behavior
- Mark-as-paid updates each line payment status/reference.
- Advance balances on employees are reduced by the line `advances` amount when paid.
- No accounting cash/payment journal is created in current flow; salary payable remains credited until external payment handling occurs.

# Reversal/Rollback Notes
- No automated reversal path is implemented. To reverse, accounting reversal must target the posted journal, and run status must be handled manually (risk of divergence if not synchronized).

# Invariants to Preserve
- State progression is linear: DRAFT → CALCULATED → APPROVED → POSTED → PAID (no skips).
- Journal posting only from APPROVED; mark-paid only from POSTED.
- Posted journal must remain linked via `journalEntryId` to `PayrollRun` and discoverable from accounting side.
- Payroll journal must balance and live in the run’s company; period lock rules apply via accounting service.
- Attendance rows used for calculation are linked to the run on posting for traceability.
- Advances are cleared against employee balances at payment; salary payable represents net-of-advance amount.
