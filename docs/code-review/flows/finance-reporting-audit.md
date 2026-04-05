# Finance / reporting / audit

## Scope and evidence

This review covers finance bootstrap/configuration, account/default-account governance, opening-balance and Tally migration paths, journal posting/idempotency/reversal behavior, period-close and month-end controls, reconciliation and discrepancy handling, GST/tax reporting, payroll accounting, accounting/enterprise audit trails, report generation and export governance, temporal/as-of reporting, and the purchasing/inventory boundary that can double-post the same business event into GL.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/{AccountingController,AccountingAuditController,AccountingConfigurationController,OpeningBalanceImportController,TallyImportController,PayrollController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/controller/HrPayrollController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/controller/ReportController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/audittrail/web/EnterpriseAuditTrailController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/{CompanyDefaultAccountsService,CompanyAccountingSettingsService,OpeningBalanceImportService,TallyImportService,AccountingPeriodService,TaxService,AccountingAuditTrailService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/{AccountingCoreEngineCore,AccountingFacadeCore,AccountingPeriodServiceCore,ReconciliationServiceCore}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/{PayrollService,PayrollPostingService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/{ReportService,ReportQuerySupport,BalanceSheetReportQueryService,ProfitLossReportQueryService,TrialBalanceReportQueryService,TemporalBalanceService,InventoryValuationService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/service/ExportApprovalService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/audittrail/EnterpriseAuditTrailService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/health/ConfigurationHealthService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/{GoodsReceiptService,PurchaseInvoiceEngine}.java`
- `erp-domain/src/main/resources/{application.yml,application-prod.yml}`
- `erp-domain/src/main/resources/db/migration_v2/{V2__accounting_core.sql,V7__enterprise_audit_ml_events.sql,V32__gst_component_tracking_fields.sql,V42__bank_reconciliation_sessions.sql,V43__reconciliation_discrepancies.sql,V44__period_close_requests.sql}`
- Tests: `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/{CriticalAccountingAxesIT,PayrollBatchPaymentIT,ReconciliationControlsIT}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/reports/ReportExportApprovalIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingExportGovernanceIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/core/audittrail/EnterpriseAuditTrailServiceTest.java`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_Reports_AsOfBalancesStable_AfterLatePostingIT.java`

Supporting runtime evidence was degraded in this session: `curl -i http://localhost:8081/actuator/health` returned HTTP `404` with `"No static resource actuator/health."`, so this review relies on static inspection plus existing integration/regression coverage. Baseline suite `mvn test -Pgate-fast -Djacoco.skip=true` passed before drafting.

## Executable remediation handoff

This review feeds:

- [Lane 03 exec spec](../executable-specs/03-lane-accounting-truth-boundary/EXEC-SPEC.md)
- [Lane 06 exec spec](../executable-specs/06-lane-governance-finance/EXEC-SPEC.md)
- [Lane 07 exec spec](../executable-specs/07-lane-orchestrator-ops/EXEC-SPEC.md)

Planning notes:

- Lane 03 Packet 0 is the prove-first boundary note in [`../executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md`](../executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md); it explicitly makes `AccountingPeriodServiceCore`, `ReconciliationServiceCore`, statements, and report services consumers of the chosen dispatch/purchase truth boundaries rather than alternate sources of accounting authority.
- The downstream finance consumers that must inherit Packet 0 are `ReportService`, `TemporalBalanceService`, `InventoryValuationService`, statement/aging routes, reconciliation sessions/discrepancies, and the period-close checklist / snapshot flow.
- `FIN-08` should be treated as an early runtime repair packet inside Lane 06, not as a reason to change the overall audit model prematurely.
- Keep the inventory-accounting listener disabled until Lane 03 finishes the canonical posting-boundary decision.

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Finance bootstrap and configuration | `GET/PUT /api/v1/accounting/default-accounts`, `GET /api/v1/accounting/configuration/health`, `POST /api/v1/accounting/opening-balances`, `POST /api/v1/migration/tally-import` | `AccountingController`, `AccountingConfigurationController`, `OpeningBalanceImportController`, `TallyImportController` | Default-account governance, configuration health scan, and migration/bootstrap flows share the finance setup boundary. |
| Journal posting and finance operations | `GET/POST /api/v1/accounting/accounts`, `GET/POST /api/v1/accounting/journal-entries`, `GET /api/v1/accounting/journals`, `POST /api/v1/accounting/journal-entries/{id}/reverse`, `POST /api/v1/accounting/{receipts/dealer,receipts/dealer/hybrid,settlements/dealers,dealers/{dealerId}/auto-settle,payroll/payments,suppliers/payments,settlements/suppliers,suppliers/{supplierId}/auto-settle,credit-notes,debit-notes,accruals,bad-debts/write-off}`, `POST /api/v1/accounting/inventory/{landed-cost,revaluation,wip-adjustment}` | `AccountingController` | One controller fronts general GL posting, settlements, bad-debt/credit/debit adjustments, payroll payment, and finance-led inventory journals. |
| Tax, period, and month-end controls | `GET /api/v1/accounting/gst/{return,reconciliation}`, `GET/POST/PUT /api/v1/accounting/periods`, `POST /api/v1/accounting/periods/{id}/{request-close,approve-close,reject-close,reopen}`, `GET/POST /api/v1/accounting/month-end/checklist{/{periodId}}` | `AccountingController` | Period close is a maker-checker path; direct close is not part of the frontend contract. |
| Reconciliation and statements | `POST/PUT/GET /api/v1/accounting/reconciliation/bank/sessions{/**}`, `GET /api/v1/accounting/reconciliation/{subledger,discrepancies,inter-company}`, `POST /api/v1/accounting/reconciliation/discrepancies/{id}/resolve`, `GET /api/v1/accounting/{statements,aging}/{dealers|suppliers}/{id}`, admin-only PDF variants | `ReconciliationController`, `StatementReportController` | Covers AR/AP/bank/GST/inter-company reconciliation plus partner statement and aging exports via the session-based bank reconciliation contract. |
| Payroll lifecycle and accounting | `GET/POST /api/v1/payroll/runs{,/weekly,/monthly}`, `POST /api/v1/payroll/runs/{id}/{calculate,approve,post,mark-paid}`, `GET /api/v1/payroll/summary/**`, `POST /api/v1/accounting/payroll/payments/batch`, `POST /api/v1/accounting/payroll/payments` | `HrPayrollController`, `PayrollController`, `AccountingController` | HR owns run creation/approval/post/mark-paid; accounting also exposes a one-call batch payroll path and explicit payroll-payment journal path. |
| Financial reporting and export approval | `GET /api/v1/reports/{balance-sheet,profit-loss,trial-balance,inventory-valuation,gst-return,inventory-reconciliation,reconciliation-dashboard,aged-debtors}`, `POST /api/v1/exports/request`, `GET /api/v1/exports/{requestId}/download` | `ReportController` | Reporting is admin/accounting scoped; export approval is a separate request/download workflow rather than the only way to see report data. |
| Accounting and enterprise audit | `GET /api/v1/accounting/audit/{events,transactions,transactions/{journalEntryId}}`, `GET /api/v1/admin/audit/events`, `GET /api/v1/superadmin/audit/platform-events`, `POST/GET /api/v1/audit/ml-events` | `AccountingAuditController`, `AdminAuditController`, `SuperAdminAuditController`, `EnterpriseAuditTrailController` | Canonical audit reads are now role-scoped: tenant accounting/admin feeds, tenant-admin-only review feed, and a platform-only superadmin feed. |
| Temporal/as-of finance queries | `GET /api/v1/accounting/accounts/{accountId}/balance/as-of`, `GET /api/v1/accounting/trial-balance/as-of`, `GET /api/v1/accounting/accounts/{accountId}/activity`, `GET /api/v1/accounting/accounts/{accountId}/compare-balances` | `AccountingController` | These routes bypass the generic report query layer and use `TemporalBalanceService` directly. |

## Data path and schema touchpoints

| Store / contract | Evidence | Used by |
| --- | --- | --- |
| Company-level finance defaults | `Company`, `CompanyDefaultAccountsService`, `CompanyAccountingSettingsService`, `ConfigurationHealthService` | Default inventory/COGS/revenue/discount/tax accounts, payroll defaults, GST account IDs, base currency, and production metadata preconditions. |
| `accounts`, `journal_entries`, `journal_lines`, `journal_reference_mappings`, `accounting_events` | `V2__accounting_core.sql`, `AccountingCoreEngineCore`, `AccountingFacadeCore`, `AccountingAuditTrailServiceCore` | Canonical GL, alias-reference replay, accounting event trail, reversals, settlements, and journal-centric audit views. |
| `accounting_periods`, `accounting_period_snapshots`, `accounting_period_trial_balance_lines`, `period_close_requests` | `AccountingPeriodServiceCore`, `TemporalBalanceService`, `ReportQuerySupport`, `V44__period_close_requests.sql` | Period state, month-end checklist flags, close-request maker/checker workflow, closed-period snapshots, and temporal report evidence. |
| `bank_reconciliation_sessions`, `bank_reconciliation_session_items`, `reconciliation_discrepancies` | `ReconciliationServiceCore`, `V42__bank_reconciliation_sessions.sql`, `V43__reconciliation_discrepancies.sql` | Persisted bank-statement matching sessions, open discrepancy tracking, and resolution-journal linkage. |
| GST component columns on invoice/purchase lines | `V32__gst_component_tracking_fields.sql`, `TaxService` | Line-level CGST/SGST/IGST and retained-quantity adjustments for GST return and reconciliation output. |
| `payroll_runs`, `payroll_run_lines`, journal links | `PayrollService`, `PayrollPostingService`, `AccountingCoreEngineCore` | Canonical payroll lifecycle, posting journal linkage, payment journal linkage, and the batch payroll shortcut. |
| `export_requests` plus persisted system settings | `ExportApprovalService`, `SystemSettingsService` | Approval queue, admin approval/rejection, and optional download gate controlled by `exportApprovalRequired`. |
| `audit_action_events`, `audit_action_event_retries`, `ml_interaction_events` | `EnterpriseAuditTrailService`, `V7__enterprise_audit_ml_events.sql` | Business audit persistence, retry storage, ML interaction ingestion, consent-aware identity handling, and query surfaces. |
| `raw_material_movements` / goods-receipt journal linkage | `GoodsReceiptService`, `PurchaseInvoiceEngine`, `InventoryAccountingEventListener` | The same physical goods-receipt movement can be the replay anchor for either safe single posting or accidental double posting, depending on the inventory-accounting listener flag. |

## Flow narrative

### 1. Finance bootstrap, account defaults, and migration imports

The finance stack starts with company-level account wiring rather than journals.

- `CompanyDefaultAccountsService.updateDefaults(...)` validates account type before wiring inventory (`ASSET`), COGS (`COGS`), revenue (`REVENUE`), discount (`REVENUE` or `EXPENSE`), and tax (`LIABILITY`) defaults.
- `CompanyAccountingSettingsService.requireTaxAccounts()` fails closed if GST input/output accounts are absent.
- `ConfigurationHealthService.evaluate()` walks every company and flags missing base currency, missing default accounts, missing GST accounts, raw-material inventory-account gaps, finished-good revenue/tax gaps, and missing production metadata such as `wipAccountId` and `semiFinishedAccountId`.

Bootstrap/migration paths already create accounting truth, not just metadata:

- `OpeningBalanceImportService` hashes the uploaded CSV, derives an idempotency key/reference, creates missing accounts when needed, posts a balancing opening-balance journal, persists the import row, and writes audit metadata with the resulting `journalEntryId`.
- `TallyImportService` follows the same file-hash/idempotency pattern, maps Tally groups into ERP `AccountType`s, converts opening vouchers into opening-balance rows, then delegates the final balancing journal to `OpeningBalanceImportService`.

Operationally, these imports are not “load data and fix later” tools. They are finance-posting surfaces with replay protection and audit side effects.

### 2. Journal posting is reference-driven, strictly balanced, and context-aware

`AccountingCoreEngineCore.createJournalEntry(...)` is the canonical GL write boundary.

Important rules observed in code and tests:

1. **Balancing tolerance is exact zero.** `JOURNAL_BALANCE_TOLERANCE` is `0`, so journals must already be fully rounded before posting.
2. **Reference numbers are the replay contract.** The engine resolves/canonicalizes the reference, checks for an existing journal by that reference, and returns the existing entry if the duplicate payload matches.
3. **Alias references are durable.** `AccountingFacadeCore` records `journal_reference_mappings` so canonical references like `INV-<order>` can be replayed through alternate aliases without duplicating the journal (`CriticalAccountingAxesIT` covers this path).
4. **AR/AP lines require matching partner context.** If a receivable account is used, dealer context must exist and match the account owner; if a payable account is used, supplier context must exist and match.
5. **AR and AP cannot mix in one entry.** The engine rejects journals that combine both control-account families.

Manual and system journals reuse the same machinery:

- `POST /api/v1/accounting/journal-entries` is the single public manual journal entry surface.
- Sales, purchase, payroll, settlement, and inventory override flows call back into `AccountingFacadeCore` so all roads still end at the same balancing/idempotency engine.
- Reversal creates a linked correction journal instead of mutating the original entry.

The audit trail is also part of the posting contract. `AccountingCoreEngineCore.handleAccountingEventTrailFailure(...)` is controlled by `erp.accounting.event-trail.strict`, which defaults to `true`; under the default policy, event-trail persistence failure raises `SYSTEM_DATABASE_ERROR` and fails the journal instead of silently dropping the trail.

### 3. Period close is a maker/checker workflow, not a direct button

The real path is:

1. requester submits `/request-close` with a required note,
2. a pending `PeriodCloseRequest` is created or updated,
3. approver calls `/approve-close` or `/reject-close`,
4. `assertMakerCheckerBoundary(...)` rejects the same actor acting as both requester and reviewer,
5. only an approved request can reach the internal `closePeriod(..., fromApprovedRequest=true, approvedRequest=...)` path.

Non-forced close enforces the full checklist and control scan:

- bank reconciliation confirmed,
- inventory count confirmed,
- zero draft/pending journal entries,
- inventory, AR, AP, and GST reconciliations within tolerance,
- zero open reconciliation discrepancies,
- zero unbalanced journals,
- zero unlinked documents,
- zero unposted documents,
- zero uninvoiced goods receipts.

If the period closes successfully, `snapshotService.captureSnapshot(...)` stores the as-of snapshot and the period moves to `CLOSED`. Reopen is even stricter:

- `POST /api/v1/accounting/periods/{periodId}/reopen` is `ROLE_SUPER_ADMIN` only,
- any closing journal is auto-reversed,
- the period snapshot is deleted,
- and the period returns to `OPEN` with explicit reopen metadata.

### 4. Reconciliation is not one report; it is a multi-layer control system

`ReconciliationServiceCore` covers several distinct reconciliations.

#### AR / AP sub-ledger parity

- `reconcileArWithDealerLedger()` compares AR control-account balances with dealer-ledger outstanding balances and emits dealer-level discrepancies.
- `reconcileApWithSupplierLedger()` does the same for supplier ledgers and AP.
- `reconcileSubledgersForPeriod(start, end)` rolls the same logic into a period-bound report and is used by the month-end checklist.

`ReconciliationControlsIT` proves the seeded-company happy path returns zero variance, and `CriticalAccountingAxesIT` proves period-bounded AR/AP movement stays aligned after posting sales and purchases.

#### Bank reconciliation

- Finance users can create bank-reconciliation sessions, upload/update matching items, and complete a session.
- Sessions and items persist in dedicated tables (`V42`), so reconciliation is not ephemeral UI state.

#### Discrepancy lifecycle

- Open discrepancies are persisted in `reconciliation_discrepancies` (`V43`).
- `resolveDiscrepancy(...)` supports `ACKNOWLEDGED`, `ADJUSTMENT_JOURNAL`, and `WRITE_OFF` resolutions.
- Journal-based resolutions create explicit finance entries like `RECON-ADJUSTMENT_JOURNAL-<id>` or `RECON-WRITE_OFF-<id>` and back-link the discrepancy to the resolution journal.

#### Inter-company

`reconcileInterCompany(...)` compares one company’s inter-company receivable posture with the counterparty’s payable posture by normalized company codes, then reports total discrepancy amount plus whether the counterparty record is missing.

### 5. GST/tax reporting is fail-closed and line-aware

`TaxService` has two different finance personalities depending on company mode.

- If the company is effectively non-GST (`defaultGstRate == 0`), `ensureNonGstCompanyDoesNotCarryGstAccounts(...)` fails if GST accounts are still configured.
- Otherwise, `requireTaxAccounts()` demands explicit GST input/output accounts before computing return data.

Return and reconciliation logic is line-aware rather than only GL-aware:

- output tax is built from sales lines,
- input tax is built from purchase lines,
- line-level `cgst_amount`, `sgst_amount`, and `igst_amount` are used when present,
- otherwise tax is split from aggregate tax amount,
- and purchase-side input tax is reduced by `returnedQuantity` using a retained-quantity ratio.

`CriticalAccountingAxesIT` proves GST return deltas reflect both posted sales tax and posted purchase input tax, and that returns reduce the reported output tax posture.

### 6. Payroll accounting has a canonical HR lifecycle plus a finance shortcut

There are two materially different payroll-to-GL flows.

#### Canonical HR payroll lifecycle

`HrPayrollController` exposes create -> calculate -> approve -> post -> mark-paid.

`PayrollPostingService` enforces the strongest controls:

- only `CALCULATED` runs with lines can be approved,
- only `APPROVED` runs can be posted unless they are already properly linked to the same posting journal,
- gross pay must be positive,
- “other deductions” must be classified before posting,
- posting journals credit `SALARY-PAYABLE`, PF, ESI, TDS, and professional-tax liabilities as needed,
- payroll-posted audit metadata must include run type, period, `journalEntryId`, posting date, gross pay, advances, deductions, and net pay,
- mark-paid requires a payment journal first, and that payment journal becomes the canonical payment reference for all run lines.

`AccountingCoreEngineCore.recordPayrollPayment(...)` adds another hard invariant: the payment amount must exactly match the `SALARY-PAYABLE` amount from the posted payroll journal. Partial or mismatched payroll settlement is rejected.

#### Accounting batch payroll shortcut

`POST /api/v1/accounting/payroll/payments/batch` is a one-call path in `AccountingCoreEngineCore.processPayrollBatchPayment(...)`.

- It creates a `PayrollRun`, persists `PayrollRunLine`s, posts the payroll journal, optionally posts employer-contribution journals, and then marks the run `PAID` immediately.
- `PayrollBatchPaymentIT` proves this path creates both the payroll run and the linked journal in one transaction.

This is operationally convenient for contractor/weekly batch disbursement, but it is not the same governance model as the HR approve/post/mark-paid sequence.

### 7. The repo has three distinct audit surfaces

#### 7.1 Canonical accounting and tenant-admin audit feeds

`/api/v1/accounting/audit/events` and `/api/v1/admin/audit/events` are backed by the unified `AuditAccessService`.

- They merge `audit_logs` and `audit_action_events` into one timestamp-sorted feed.
- `/api/v1/accounting/audit/events` is the accounting/admin view for tenant-scoped finance and business activity.
- `/api/v1/admin/audit/events` is tenant-admin only and keeps superadmins out of tenant business audit reads.

This is now the canonical “who changed what” view for tenant-scoped audit evidence.

#### 7.2 Journal-centric transaction audit

`/api/v1/accounting/audit/transactions` and `/api/v1/accounting/audit/transactions/{journalEntryId}` are backed by `AccountingAuditTrailServiceCore`.

- The list view derives transaction type/module, totals, reversal lineage, consistency state, and posting timestamps.
- The detail view dedupes linked documents (invoice, purchase, settlement documents), includes settlement allocations, and includes the accounting event trail rows for that journal.
- Consistency scoring explicitly flags unbalanced journals, posted rows missing `postedAt`, and settlement-like references missing allocation rows.

This is the “is the journal internally coherent and what business documents does it touch?” view.

#### 7.3 Enterprise audit and ML audit

`EnterpriseAuditTrailService` is broader than accounting.

- Business events are recorded asynchronously.
- If initial persistence fails, the service enqueues retries and also attempts to persist a retry record.
- `EnterpriseAuditTrailServiceTest` proves both retry persistence and “drop after max attempts” behavior.
- ML interaction ingest accepts up to 200 events per request, sanitizes metadata/payload sizes, and records actor identity differently depending on consent.
- If consent is absent, identity is anonymized with HMAC-SHA256 using `erp.security.audit.private-key`; the constructor hard-fails if that key is blank.

The role split is intentional:

- `GET /api/v1/admin/audit/events` is tenant-admin only,
- `GET /api/v1/accounting/audit/events` and transaction audit remain tenant admin/accounting,
- `GET /api/v1/superadmin/audit/platform-events` is platform-only for `ROLE_SUPER_ADMIN`,
- `POST /api/v1/audit/ml-events` is any authenticated user,
- `GET /api/v1/audit/ml-events` is admin only.

### 8. Reporting splits into generic report DTOs, temporal queries, and export governance

`ReportController` fronts generic financial reports, while `AccountingController` fronts the temporal/as-of detail surfaces.

#### Generic reports

`ReportService` delegates to query services for balance sheet, profit and loss, trial balance, aged debtors, inventory valuation, GST return, and reconciliation dashboard.

- `ReportQuerySupport.resolveWindow(...)` chooses `LIVE`, `AS_OF`, or `SNAPSHOT` source.
- If the relevant accounting period is `CLOSED`, the code requires a stored snapshot and returns `ReportSource.SNAPSHOT`.
- Comparative periods/ranges are supported by building a second `FinancialQueryWindow`.

#### Temporal detail reports

`TemporalBalanceService` is separate and more deterministic.

- Closed periods read from stored snapshot lines.
- Open periods use `journalLineRepository.summarizeByAccountUpTo(company, asOfDate)`.
- `/accounts/{id}/balance/as-of`, `/trial-balance/as-of`, account activity, and compare-balances all use this service directly.

`CR_Reports_AsOfBalancesStable_AfterLatePostingIT` proves the temporal trial-balance path remains stable when a later journal is posted after the requested as-of date.

#### Export governance

`ExportApprovalService` is opt-in governance, not a universal report gate.

- `createRequest(...)` always creates `PENDING` requests.
- `approve(...)` and `reject(...)` are explicit admin actions.
- `resolveDownload(...)` blocks unapproved downloads only when `SystemSettingsService.isExportApprovalRequired()` is `true`.
- If the setting is off, download returns success even for `REJECTED` requests with a message explaining that approval is disabled (`ReportExportApprovalIT` proves this).

Separate admin-only finance exports bypass this workflow entirely:

- dealer/supplier statement PDFs,
- dealer/supplier aging PDFs.

Those endpoints are still controlled and audit-logged (`AccountingExportGovernanceIT` proves admin-only access and `DATA_EXPORT` metadata), but they do not consult `ExportApprovalService`.

### 9. Purchasing, GRN accounting events, and the double-posting answer

This is the key cross-flow finance finding.

Observed chain:

1. `GoodsReceiptService.publishInventoryEvents(...)` publishes `InventoryMovementEvent` for raw-material receipts when both supplier payable and material inventory accounts are known.
2. The event carries `sourceAccountId = supplier payable`, `destinationAccountId = raw-material inventory`, a stable `movementId`, and the GRN `referenceNumber`.
3. `InventoryAccountingEventListener` is active when `erp.inventory.accounting.events.enabled=true` and, after commit, auto-posts a debit-to-destination / credit-to-source journal using an idempotent movement-based reference.
4. `PurchaseInvoiceEngine` later validates that the same goods-receipt movements are still unlinked, posts the purchase journal, and links those movements to the purchase journal.
5. If any movement is already linked to another journal, `PurchaseInvoiceEngine` throws `"Goods receipt <receipt> already linked to journal <id>"`.

That means the answer to the mission question is:

> **Yes — the code can post accounting for the same purchasing business event twice if raw-material inventory auto-posting is enabled for goods receipts.**

The repo mitigates this operationally through configuration, not redesign:

- `application.yml` defaults `erp.inventory.accounting.events.enabled: true`
- `application-prod.yml` explicitly overrides it to `false`

So production correctness currently depends on keeping the inventory-accounting listener off for the purchasing path.

## Control points

| Control point | Evidence | What it prevents | Residual caveat |
| --- | --- | --- | --- |
| Default-account type validation | `CompanyDefaultAccountsService.updateDefaults(...)` | Miswiring inventory/COGS/revenue/tax defaults to the wrong account class. | Payroll-default updates are looser and depend on downstream posting logic rather than upfront type enforcement. |
| Configuration health scan | `ConfigurationHealthService.evaluate()` | Silent tenant drift across base currency, default accounts, GST accounts, RM accounts, FG revenue/tax accounts, and production metadata. | It is an explicit health endpoint, not an always-on guard before every finance mutation. |
| Exact journal balancing | `AccountingCoreEngineCore.JOURNAL_BALANCE_TOLERANCE = 0` | Penny drift and unbalanced journals entering the ledger. | Upstream callers must pre-round correctly; no tolerant auto-fix exists. |
| Partner-context validation | `createJournalEntry(...)` AR/AP owner checks | Posting receivable/payable entries against the wrong dealer/supplier or mixing AR and AP in one journal. | Generic journals without AR/AP lines can still carry partner context for non-control postings. |
| Strict accounting event trail | `erp.accounting.event-trail.strict:true` default | Silent loss of accounting event trail when event-store persistence fails. | If a lower environment disables strict mode, audit loss becomes best-effort. |
| Period-close maker/checker | `PeriodCloseRequest`, `assertMakerCheckerBoundary(...)` | A single actor requesting and approving the same close. | The public `/close` endpoint still exists and can mislead clients even though it always fails. |
| Month-end checklist gates | `assertChecklistComplete(...)`, `assertNoUninvoicedReceipts(...)` | Closing periods with unreconciled balances, open discrepancies, unposted docs, or uninvoiced GRNs. | Forced close can override parts of the checklist; the override itself becomes a governance event to inspect. |
| Persisted discrepancy workflow | `reconciliation_discrepancies`, `resolveDiscrepancy(...)` | Losing track of reconciliation exceptions or resolving them without journal evidence. | `ACKNOWLEDGED` resolution documents the issue without actually fixing the ledger. |
| Payroll posting/payment invariants | `PayrollPostingService`, `recordPayrollPayment(...)` | Posting payroll before approval, marking payroll paid without a payment journal, or paying an amount that does not match `SALARY-PAYABLE`. | The batch payroll endpoint intentionally bypasses the longer HR approval lifecycle. |
| Closed-period snapshot requirement | `ReportQuerySupport`, `TemporalBalanceService` | Rebuilding closed-period reports from mutable live journals instead of frozen snapshots. | Open-period `AS_OF` behavior still depends on the generic report-query implementation, which has its own edge cases. |
| Export approval gate | `ExportApprovalService`, `SystemSettingsService` | Unapproved export downloads through the explicit request/download workflow. | Direct report JSON responses and admin-only PDF/CSV exports are outside this gate. |
| Consent-aware ML audit identity | `EnterpriseAuditTrailService.resolveMlIdentity(...)` | Storing raw user identity in ML telemetry when the actor has not opted in. | Business events still preserve actor identity; only ML telemetry is consent-anonymized. |

## State machine and idempotency assumptions

### State assumptions

| Object | State assumptions | Evidence |
| --- | --- | --- |
| Accounting period | Effective workflow is `OPEN -> PENDING close request -> APPROVED close request -> CLOSED`, with reopen only by `ROLE_SUPER_ADMIN`. | `AccountingPeriodService`, `AccountingPeriodServiceCore`, `V44__period_close_requests.sql` |
| Export request | `PENDING -> APPROVED|REJECTED`; download is blocked only when approval-required is enabled. | `ExportApprovalService`, `ReportExportApprovalIT` |
| Payroll run | Canonical HR flow is `DRAFT/CREATED -> CALCULATED -> APPROVED -> POSTED -> PAID`; accounting batch payroll jumps directly to a paid run. | `HrPayrollController`, `PayrollPostingService`, `PayrollBatchPaymentIT` |
| Reconciliation discrepancy | Starts `OPEN`, then moves to `ACKNOWLEDGED`, `ADJUSTED`, or `RESOLVED` depending on resolution type. | `ReconciliationServiceCore`, `V43__reconciliation_discrepancies.sql` |
| Audit retry item | Business-audit retry rows are transient repair objects, not durable business truth. | `EnterpriseAuditTrailService`, `EnterpriseAuditTrailServiceTest` |

### Idempotency assumptions

- **Opening balances / Tally import:** keyed by file hash plus normalized idempotency key/reference.
- **General journals:** keyed by canonical reference number plus alias mappings; duplicate payloads replay, mismatched payloads fail.
- **Manual journals:** explicit `idempotencyKey` is copied into the posting request.
- **Inventory-accounting listener:** builds deterministic references from GRN reference plus `movementId`.
- **Payroll payment:** reference number plus existing payment-journal parity checks prevent conflicting replay.
- **Batch payroll:** caller reference or generated payroll payment reference becomes the replay handle.
- **Export approval:** request IDs govern the approval/download workflow, but there is no content-hash replay control because the endpoint is approval metadata, not file generation.

## Side effects, integrations, and recovery behavior

- Opening-balance and Tally imports can create accounts and immediately create balancing journals.
- Standard journal posting updates AR/AP partner ledgers, accounting events, and export/audit metadata depending on the path.
- Period close captures immutable period snapshots; reopen reverses closing posture and deletes the snapshot.
- Reconciliation resolutions can create finance journals tied back to discrepancy rows.
- Payroll post links the payroll run to a posting journal; payroll payment links it again to a payment journal; mark-paid cascades the payment reference down to run lines.
- Enterprise audit business events are eventually consistent because persistence is async with retry.
- Generic report APIs and temporal finance APIs are separate read models; inconsistencies between them can reveal bugs in query-window selection rather than underlying ledger data.
- Goods-receipt inventory events and purchase-invoice journal linkage share the same raw-material movement anchors, which is why listener configuration matters so much.

Recovery posture is strongest where explicit replay anchors exist (imports, journals, payroll payments, inventory movement listener). Recovery is weaker for async enterprise audit because a just-written event may not be visible immediately, and persistent failure eventually ages out the retry row rather than blocking the original business transaction.

## Audit and reporting risks

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| critical | accounting boundary / configuration assumption | Goods receipts publish payable-to-inventory accounting events, and the inventory listener will auto-post them when `erp.inventory.accounting.events.enabled=true`; purchase invoicing later posts the purchase/AP journal and links the same receipt movements. | `GoodsReceiptService`, `InventoryAccountingEventListener`, `PurchaseInvoiceEngine`, `application.yml`, `application-prod.yml` | The same purchasing business event can hit GL twice unless production keeps the inventory-accounting listener disabled. |
| resolved | API / workflow drift | Direct period close is removed from the public controller surface; frontend close flows must use `/request-close` + `/approve-close` and the service-level maker-checker path. | `AccountingController`, `AccountingPeriodService` | The public route no longer competes with the approved maker-checker workflow. |
| high | reporting correctness | `ReportQuerySupport.resolveWindow(...)` back-fills period start/end dates for `AS_OF` requests when a period exists, while `BalanceSheetReportQueryService` and `TrialBalanceReportQueryService` aggregate `summarizeByAccountWithin(...)` for non-snapshot windows instead of always aggregating up to `asOfDate`. | `ReportQuerySupport`, `BalanceSheetReportQueryService`, `TrialBalanceReportQueryService` | Once period rows are seeded, `?date=` report calls have a latent risk of behaving like period activity instead of cumulative as-of balances. |
| resolved | audit hard-cut cleanup | Canonical audit feeds are now role-scoped on `GET /api/v1/accounting/audit/events`, `GET /api/v1/admin/audit/events`, and `GET /api/v1/superadmin/audit/platform-events`; the broken shared business-events route is removed. | `AccountingAuditController`, `AdminAuditController`, `SuperAdminAuditController`, `AuditAccessService` | Finance/compliance audit readers now have explicit tenant/accounting/platform surfaces instead of a broken shared endpoint. |
| high | segregation of duties | `processPayrollBatchPayment(...)` creates payroll runs, journals, and a paid state in one call, bypassing the stronger `calculate -> approve -> post -> mark-paid` HR workflow. | `PayrollController`, `AccountingCoreEngineCore.processPayrollBatchPayment(...)`, `PayrollBatchPaymentIT` | Finance convenience can undercut maker/checker expectations for payroll if the shortcut becomes the default operational path. |
| medium | export governance gap | Export approval is optional, and even when enabled it only gates the explicit request/download flow; direct report JSON endpoints and admin-only PDF/CSV finance exports bypass it. | `ExportApprovalService`, `ReportController`, `AccountingController`, `ReportExportApprovalIT`, `AccountingExportGovernanceIT` | “Require export approval” is not a universal exfiltration control for privileged users. |
| medium | audit consistency / eventual visibility | Enterprise business audit persistence is async and retry-based; persistent failures are retried, but the system does not block the originating business action waiting for audit durability. | `EnterpriseAuditTrailService`, `EnterpriseAuditTrailServiceTest` | Fresh actions may not appear immediately in audit queries, and a persistent outage can still create audit gaps after retries exhaust. |
| medium | observability gap | Local runtime exposes the app on `8081`, but the expected actuator health endpoint is not present there. | `curl -i http://localhost:8081/actuator/health` response, `application-prod.yml` management config | Reviewers/operators cannot use the usual local health probe for quick confidence and may over-trust static analysis or stale test results. |

## Security, privacy, protocol, performance, and observability notes

### Strengths

- Core finance, report, and audit routes are role-gated to admin/accounting users, with ML-event query restricted further to admin only.
- Journal posting and payroll settlement are aggressively fail-closed around balance, partner context, and liability parity.
- Period close combines maker/checker, checklist, discrepancy, uninvoiced-GRN, and snapshot controls rather than relying on a single status flag.
- Closed-period reporting and temporal queries prefer stored snapshots when available.
- Admin-only PDF/CSV finance exports are still audit logged even when they bypass the export-approval workflow.
- ML telemetry uses consent-aware anonymization backed by a required HMAC key.

### Hotspots

- The strongest finance control in this area is configuration, not code separation: production must keep raw-material inventory auto-posting disabled.
- Generic report APIs and temporal/as-of APIs are not the same implementation, which increases the chance of “two truths” if query-window logic drifts.
- Business audit durability is eventually consistent, not synchronous with the originating transaction.
- Runtime observability for this review session was limited because the expected actuator health route was absent on the live local surface.

## Evidence notes

- `CriticalAccountingAxesIT` proves GST output/input tax movement, sub-ledger reconciliation parity, and reference-alias idempotency across sales journals.
- `ReconciliationControlsIT` proves zero-variance AR/AP and inventory reconciliation in the seeded-company happy path.
- `PayrollBatchPaymentIT` proves the accounting batch-payroll shortcut creates both payroll-run rows and a posted journal in one flow.
- `ReportExportApprovalIT` proves export request approval gating and also proves the deliberate bypass when `exportApprovalRequired=false`.
- `AccountingExportGovernanceIT` proves admin-only access and export-audit metadata for direct finance CSV/PDF endpoints.
- `EnterpriseAuditTrailServiceTest` proves blank audit HMAC key rejection, async retry persistence, retry expiry after max attempts, and exclusive upper-bound date filtering.
- `CR_Reports_AsOfBalancesStable_AfterLatePostingIT` proves the temporal as-of trial-balance path remains stable after a later posting, but it does not eliminate the generic report-query window risk described above.
