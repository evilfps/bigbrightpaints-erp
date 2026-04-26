# Accounting / Period Close Flow

Last reviewed: 2026-04-26

This packet documents the **accounting and period close flow**: journal posting, correction, bank reconciliation, subledger reconciliation, discrepancy handling, month-end checklist controls, and the maker-checker close workflow. It is code-grounded and describes only current backend routes.

Accounting is the financial truth boundary for the ERP. Other modules may trigger accounting effects, but period state, reconciliation evidence, journal correction, and close artifacts are owned by accounting.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Admin** | Close checker, accounting operator | `ROLE_ADMIN` |
| **Accounting** | Close maker, accounting operator | `ROLE_ACCOUNTING` |
| **Super-admin** | Controlled period reopen | `ROLE_SUPER_ADMIN` |

---

## 2. Entrypoints

### Journals — `JournalController` (`/api/v1/accounting/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Journal Entries | GET | `/api/v1/accounting/journal-entries` | ADMIN, ACCOUNTING | List journal entries with dealer/supplier/source filters. |
| List Journals | GET | `/api/v1/accounting/journals` | ADMIN, ACCOUNTING | Paginated journal list with date/type/source filters. |
| Post Manual Journal | POST | `/api/v1/accounting/journal-entries` | ADMIN, ACCOUNTING | Post a balanced manual journal. |
| Reverse Journal | POST | `/api/v1/accounting/journal-entries/{entryId}/reverse` | ADMIN, ACCOUNTING | Full/partial/void correction according to period rules. |
| Credit Note | POST | `/api/v1/accounting/credit-notes` | ADMIN, ACCOUNTING | Post credit note journal. |
| Debit Note | POST | `/api/v1/accounting/debit-notes` | ADMIN, ACCOUNTING | Post debit note journal. |
| Accrual | POST | `/api/v1/accounting/accruals` | ADMIN, ACCOUNTING | Post accrual journal. |
| Bad Debt Write-Off | POST | `/api/v1/accounting/bad-debts/write-off` | ADMIN, ACCOUNTING | Post bad debt write-off. |

### Period Management — `PeriodController` (`/api/v1/accounting/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Periods | GET | `/api/v1/accounting/periods` | ADMIN, ACCOUNTING | List tenant accounting periods. |
| Save Period | POST | `/api/v1/accounting/periods` | ADMIN, ACCOUNTING | Create or update a period. |
| Update Period | PUT | `/api/v1/accounting/periods/{periodId}` | ADMIN, ACCOUNTING | Update an existing period. |
| Request Close | POST | `/api/v1/accounting/periods/{periodId}/request-close` | ADMIN, ACCOUNTING | Submit maker close request. |
| Approve Close | POST | `/api/v1/accounting/periods/{periodId}/approve-close` | ADMIN | Checker approval closes the period. |
| Reject Close | POST | `/api/v1/accounting/periods/{periodId}/reject-close` | ADMIN | Reject pending close request. |
| Reopen Period | POST | `/api/v1/accounting/periods/{periodId}/reopen` | SUPER_ADMIN | Reopen a closed period with reason and audit evidence. |

### Month-End Checklist — `PeriodController`

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Read Checklist | GET | `/api/v1/accounting/month-end/checklist?periodId={periodId}` | ADMIN, ACCOUNTING | Returns readiness flags and actionable detail. |
| Update Checklist | POST | `/api/v1/accounting/month-end/checklist/{periodId}` | ADMIN, ACCOUNTING | Updates allowed checklist fields and notes. |

### Reconciliation — `ReconciliationController`

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Start Bank Session | POST | `/api/v1/accounting/reconciliation/bank/sessions` | ADMIN, ACCOUNTING | Create `IN_PROGRESS` bank reconciliation session. |
| List Bank Sessions | GET | `/api/v1/accounting/reconciliation/bank/sessions` | ADMIN, ACCOUNTING | Paginated bank session list. |
| Get Bank Session | GET | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}` | ADMIN, ACCOUNTING | Resume/review a saved session. |
| Update Session Items | PUT | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items` | ADMIN, ACCOUNTING | Add/remove matched journal lines. |
| Complete Session | POST | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete` | ADMIN, ACCOUNTING | Promote session to `COMPLETED`; confirm period bank flag when linked. |
| Subledger Reconciliation | GET | `/api/v1/accounting/reconciliation/subledger` | ADMIN, ACCOUNTING | Compare AR/AP control balances to dealer/supplier ledgers. |
| Discrepancy List | GET | `/api/v1/accounting/reconciliation/discrepancies` | ADMIN, ACCOUNTING | List discrepancies with status/type filters. |
| Discrepancy Resolve | POST | `/api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve` | ADMIN, ACCOUNTING | Record acknowledgement, correction, adjustment, or write-off resolution. |
| Inter-Company Reconciliation | GET | `/api/v1/accounting/reconciliation/inter-company` | ADMIN, ACCOUNTING | Match cross-entity positions. |

### GST and Temporal Reads — `StatementReportController`

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| GST Return | GET | `/api/v1/accounting/gst/return` | ADMIN, ACCOUNTING | Accounting GST return read. |
| GST Reconciliation | GET | `/api/v1/accounting/gst/reconciliation` | ADMIN, ACCOUNTING | GST reconciliation read. |
| Balance As-Of | GET | `/api/v1/accounting/accounts/{accountId}/balance/as-of?date={date}` | ADMIN, ACCOUNTING | Point-in-time balance. |
| Trial Balance As-Of | GET | `/api/v1/accounting/trial-balance/as-of?date={date}` | ADMIN, ACCOUNTING | As-of trial balance. |
| Account Activity | GET | `/api/v1/accounting/accounts/{accountId}/activity` | ADMIN, ACCOUNTING | Account activity and movement report. |
| Date Context | GET | `/api/v1/accounting/date-context` | ADMIN, ACCOUNTING | Tenant accounting date context. |

### Data Import

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Opening Balance Import | POST | `/api/v1/accounting/opening-balances` | ADMIN | CSV opening-balance import. |
| Tally Import | POST | `/api/v1/migration/tally-import` | ADMIN | Legacy Tally XML migration path. |

---

## 3. Preconditions

### Journal Preconditions

1. **Balanced lines** — total debit must equal total credit.
2. **Reason present** — manual journals require a nonblank memo/reason.
3. **Reference namespace safe** — system-reserved references are rejected for manual journals.
4. **Period postable** — locked/closed period restrictions apply to posting and correction.
5. **Tenant scoped** — accounts and journals belong to the authenticated company.

### Reconciliation Preconditions

1. **Bank account valid** — bank reconciliation sessions use valid accounting data.
2. **Session state valid** — only in-progress sessions accept item updates.
3. **Linked period valid when supplied** — period linkage must match the statement month and be open at completion.
4. **Discrepancy status valid** — only open discrepancies are resolvable.

### Period Close Preconditions

1. **Checklist readiness** — month-end checklist items return detail and can block close.
2. **Close request note** — request, approval, and rejection require meaningful notes where enforced.
3. **Maker-checker** — checker must differ from requester where the workflow requires it.
4. **Admin checker** — approval/rejection is admin-gated.
5. **No direct finalize route** — approval is the close completion step.

---

## 4. Lifecycle

### 4.1 Journal and Correction Lifecycle

```
[Start] → Validate role + tenant → Validate period/account/lines →
Post journal → Audit/provenance evidence visible in accounting reads
```

Corrections do not edit posted entries. They use reversal/correction entries through `POST /api/v1/accounting/journal-entries/{entryId}/reverse`. Locked periods block reversal; closed-period reversal requires explicit override behavior as implemented by the accounting policy.

### 4.2 Bank Reconciliation Draft Lifecycle

```
Create session → IN_PROGRESS draft → update matched items →
read/resume session → complete session → COMPLETED
```

This is the current save/resume workflow for accounting close support. An `IN_PROGRESS` session preserves the matching work without closing a period or posting unrelated accounting effects. Completion promotes the session and, when linked to a valid period, confirms the period's bank reconciliation flag.

### 4.3 Subledger and Discrepancy Lifecycle

```
Run subledger reconciliation → review AR/AP variances →
list discrepancies → resolve each open discrepancy with explicit action
```

Discrepancy resolution supports acknowledgement, correction, adjustment/journal-backed adjustment, and write-off style outcomes according to the request validation rules.

### 4.4 Period Close Lifecycle

```
Checklist/reconciliation evidence ready → Maker requests close → PENDING →
Checker approves or rejects → APPROVED closes period or REJECTED leaves period open/locked
```

Close approval records close artifacts and closes the period. There is no intermediate finalize state and no separate finalize route. A controlled super-admin reopen route exists for post-close correction.

### 4.5 Approval Inbox Visibility

Period close requests also appear in the tenant-admin approval inbox:

| Purpose | Method | Path |
| --- | --- | --- |
| Approval inbox | GET | `/api/v1/admin/approvals` |
| Close decision | POST | `/api/v1/admin/approvals/PERIOD_CLOSE_REQUEST/{id}/decisions` |

The period controller close routes remain the direct accounting lifecycle routes; the approval inbox is the canonical shared approval surface.

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Financial events posted** — manual and automated journals are posted or rejected with controlled validation errors.
2. **Reconciliation evidence saved** — bank sessions, subledger checks, and discrepancy decisions are visible.
3. **Checklist ready** — month-end checklist items explain readiness and blockers.
4. **Close decision recorded** — close request is approved or rejected through the maker-checker workflow.
5. **Reports can read the result** — closed-period snapshots and live report reads reflect the implemented reporting rules.

### Current Limitations

1. **No separate finalize route** — approval is the close completion step.
2. **No dealer/supplier-specific reconciliation routes** — AR/AP reconciliation is exposed through the combined subledger route.
3. **Tally XML remains legacy migration support** — use opening-balance import for current controlled opening balance work.
4. **Reopen is super-admin-only** — close is maker-checker; reopen is a controlled privileged correction route.

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/accounting/journal-entries` | `JournalController` | Manual journal posting. |
| `POST /api/v1/accounting/journal-entries/{entryId}/reverse` | `JournalController` | Correction/reversal. |
| `POST /api/v1/accounting/periods/{periodId}/request-close` | `PeriodController` | Maker close request. |
| `POST /api/v1/accounting/periods/{periodId}/approve-close` | `PeriodController` | Checker approval closes period. |
| `POST /api/v1/accounting/periods/{periodId}/reject-close` | `PeriodController` | Checker rejection. |
| `POST /api/v1/accounting/periods/{periodId}/reopen` | `PeriodController` | Super-admin reopen. |
| `POST /api/v1/accounting/reconciliation/bank/sessions` | `ReconciliationController` | Bank reconciliation draft/session. |
| `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items` | `ReconciliationController` | Update matched items. |
| `POST /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete` | `ReconciliationController` | Complete bank session. |
| `GET /api/v1/accounting/reconciliation/subledger` | `ReconciliationController` | AR/AP reconciliation. |
| `POST /api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve` | `ReconciliationController` | Discrepancy resolution. |

### Non-Canonical / Retired Paths

| Path | Status | Replacement |
| --- | --- | --- |
| Direct period close route | Not exposed | Request close, then approve close. |
| Legacy close-request alias | Stale alias | `/api/v1/accounting/periods/{periodId}/request-close` |
| Legacy close-approve alias | Stale alias | `/api/v1/accounting/periods/{periodId}/approve-close` |
| Legacy close-finalize alias | Not exposed | No separate finalize route; approval closes. |
| Legacy bank-session prefix without `/bank` | Stale alias | `/api/v1/accounting/reconciliation/bank/sessions/**` |
| Legacy bank-session match/unmatch aliases | Not exposed | `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items` |
| Dealer-specific reconciliation alias | Not exposed | `GET /api/v1/accounting/reconciliation/subledger` |
| Supplier-specific reconciliation alias | Not exposed | `GET /api/v1/accounting/reconciliation/subledger` |
| Legacy full reconciliation run route | Not exposed | `GET /api/v1/accounting/reconciliation/subledger` and discrepancy routes. |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `sales` | Dispatch, invoice, AR, dealer ledger, receipt/settlement events | Trigger/write through accounting |
| `purchasing` | GRN, purchase invoice, AP, supplier settlement events | Trigger/write through accounting |
| `inventory` | Opening stock, adjustments, landed cost, revaluation, WIP | Trigger/write through accounting |
| `factory` | Production WIP and cost allocation | Trigger/write through accounting |
| `reports` | Period snapshots, live journals, reconciliation outputs | Read |

---

## 8. Security Considerations

- **RBAC** — accounting operations require `ROLE_ADMIN` or `ROLE_ACCOUNTING`; close approval requires `ROLE_ADMIN`; reopen requires `ROLE_SUPER_ADMIN`.
- **Tenant scoping** — period, journal, reconciliation, and report reads are company-scoped.
- **Maker-checker** — close request and close approval are separated.
- **Posted-truth immutability** — posted journals are corrected with counter-entries, not edited in place.
- **Auditability** — close, reopen, reconciliation, and journal correction actions record explicit evidence.

---

## 9. Related Documentation

- [Accounting Workflow Architecture](accounting-workflow-architecture.md) — connected accounting architecture for client sharing
- [Reporting / Export Flow](reporting-export.md) — reports and export approvals
- [Invoice / Dealer Finance Flow](invoice-dealer-finance.md) — settlement and dealer finance reads
- [docs/modules/reports.md](../modules/reports.md) — reporting module packet
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — flow inventory

---

## 10. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| Close finalization route | Not implemented; approval closes. |
| Scheduled reconciliation | Not implemented; reconciliation is operator-driven. |
| Automatic GST filing | Not implemented; GST return/reconciliation are backend reads. |
| Edit posted journals | Not supported; reversal/correction is required. |
| Reopen maker-checker | Not implemented; reopen is super-admin-only by design. |
