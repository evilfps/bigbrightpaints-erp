# Accounting and Period Close Workflow

**Audience:** Accountants, finance manager, approver/admin

This guide covers journaling, reconciliation, discrepancy handling, period controls, and reporting.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Post journals (manual + automated review) | Manual journals: `POST /api/v1/accounting/journals/manual`; list/filter: `GET /api/v1/accounting/journals`; legacy manual path: `POST /api/v1/accounting/journal-entries` | Balanced entry is posted with audit trail; automated journals are also visible in listing | Debits/credits not balanced, wrong account, closed/locked period. Correct lines and re-submit. |
| 2 | Perform reconciliation (bank, AR/AP, GST) | Bank session start/update/complete: `POST /api/v1/accounting/reconciliation/bank/sessions`, `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items`, `POST /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete`; AR/AP: `GET /api/v1/accounting/reconciliation/subledger`; GST: `GET /api/v1/accounting/gst/reconciliation` | Reconciliation status and variances are visible before close | Statement balance/date window wrong, uncleared items missing, or GST period mismatch. Re-run with correct period and source docs. |
| 3 | Resolve discrepancies | List: `GET /api/v1/accounting/reconciliation/discrepancies`; resolve: `POST /api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve` | Discrepancy moves from OPEN to resolved/acknowledged with notes/journal linkage as needed | Invalid resolution option or missing adjustment account for adjustment path. Choose valid resolution and provide required data. |
| 4 | Lock period when operational posting should stop | `POST /api/v1/accounting/periods/{periodId}/lock` | Period becomes locked for controlled finalization | Checklist updates blocked while locked; unlock/reopen governance may be required if data correction is still pending. |
| 5 | Submit close request (maker step) | `POST /api/v1/accounting/periods/{periodId}/request-close` | Request enters pending state for approval workflow | Request blocked by unresolved checklist or open discrepancies. Clear blockers first. |
| 6 | Approve/reject close request (checker step) | Approve: `POST /api/v1/accounting/periods/{periodId}/approve-close`; reject: `POST /api/v1/accounting/periods/{periodId}/reject-close`; visibility in approvals: `GET /api/v1/admin/approvals` | Maker-checker control enforced (different users) | Same user attempting self-approval or missing justification on rejection. Use separate approver account. |
| 7 | Finalize period close and post closing adjustments | `POST /api/v1/accounting/periods/{periodId}/close` (and if required, reversal/correction via `POST /api/v1/accounting/journals/{entryId}/reverse`) | Period status moves to closed and close artifacts become reportable | Close fails if checklist/reconciliation still incomplete. Re-open request cycle after fixes. |
| 8 | Publish finance reports | Trial balance: `GET /api/v1/reports/trial-balance`; P&L: `GET /api/v1/reports/profit-loss`; balance sheet: `GET /api/v1/reports/balance-sheet`; GST return: `GET /api/v1/reports/gst-return` | Final period reports are consistent and auditable | Out-of-balance or unexpected values usually indicate missing postings/settlements or late adjustments. Investigate journals and reconciliation logs. |

## Daily Accounting Operational APIs

- Journal list/filter: `GET /api/v1/accounting/journals?fromDate=&toDate=&type=&sourceModule=`
- Dealer finance reads: `GET /api/v1/portal/finance/ledger?dealerId=`, `GET /api/v1/portal/finance/aging?dealerId=`
- Supplier statements/aging: `GET /api/v1/accounting/statements/suppliers/{supplierId}`, `GET /api/v1/accounting/aging/suppliers/{supplierId}`
- Audit trail query: `GET /api/v1/accounting/audit-trail`

## Troubleshooting Quick Notes

1. **Manual journal rejected:** check debits=credits and valid account IDs.
2. **Period close won’t move:** ensure discrepancies are resolved and checklist is complete.
3. **Need correction after posting:** reverse wrong entry with journal reverse endpoint; do not edit posted journal in place.
4. **GST mismatch to books:** rerun GST reconciliation for correct period and verify purchase returns were considered.
