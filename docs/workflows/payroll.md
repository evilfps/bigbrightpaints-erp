# Payroll Workflow

> ⚠️ **NON-CANONICAL**: This document is superseded by the canonical flow packets in [docs/flows/](flows/). The current HR and payroll behavior is documented in [docs/flows/hr-payroll.md](../flows/hr-payroll.md).

**Audience:** HR operations, payroll processor, finance/accounting approver

This guide covers payroll run creation through accounting posting and payment closure.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Create payroll run | **Payroll Run Setup** → generic `POST /api/v1/payroll/runs`, weekly `POST /api/v1/payroll/runs/weekly`, monthly `POST /api/v1/payroll/runs/monthly` | Payroll run record is created for the period | Duplicate period run, invalid period boundaries, or missing employee setup. Validate period and employee master data. |
| 2 | Calculate earnings/deductions (PF/ESI/TDS/prof-tax) | **Payroll Calculation** → `POST /api/v1/payroll/runs/{id}/calculate`; check lines `GET /api/v1/payroll/runs/{id}/lines` | Gross, deductions, and net pay are computed per employee | Missing salary structure, invalid standard hours, or bad attendance data can block calculation. Fix employee/pay config and recalculate. |
| 3 | Approve payroll | **Approval Action** → `POST /api/v1/payroll/runs/{id}/approve` | Run status changes to approved and becomes ready to post | Approval rejected for incomplete calculations or stale run state. Recalculate and retry approval. |
| 4 | Post payroll to accounting | **Accounting Posting** → `POST /api/v1/payroll/runs/{id}/post` | Payroll expense/liability journals are posted and linked to run | Missing mapping accounts or closed period can block posting. Fix accounting setup/period status and re-post. |
| 5 | Mark payroll paid | **Payment Closure** → `POST /api/v1/payroll/runs/{id}/mark-paid` (body includes `paymentReference`) | Run status changes to paid and payment reference is captured | Missing/incorrect payment reference or posting not done yet. Post payroll first, then mark paid with final bank reference. |

## Supporting APIs

- Payroll run list/detail: `GET /api/v1/payroll/runs`, `GET /api/v1/payroll/runs/{id}`
- Weekly/monthly run views: `GET /api/v1/payroll/runs/weekly`, `GET /api/v1/payroll/runs/monthly`
- Summary checks: `GET /api/v1/payroll/summary/current-week`, `GET /api/v1/payroll/summary/current-month`
- Payroll payment journal recording (accounting side): `POST /api/v1/accounting/payroll/payments`

## Troubleshooting Quick Notes

1. **Payroll calculate fails for some employees:** check attendance and salary structure completeness first.
2. **Posting blocked despite approval:** verify accounting period is open and required accounts exist.
3. **Marked paid but reconciliation mismatch:** verify payment reference and compare with accounting payroll payment journal.
4. **Unexpected deductions:** review PF/ESI/TDS/prof-tax settings and employee-specific tax identifiers.
