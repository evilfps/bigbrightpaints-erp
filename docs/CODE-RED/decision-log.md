# Decision Log (CODE-RED)

## 2026-02-17 - Release Gate Verifies Rollback Evidence + Traceability Artifacts
Decision:
- `gate_release` is the canonical closure gate for rollback rehearsal evidence.
- The gate must fail closed when required artifacts are missing.
- The gate writes a release traceability manifest that includes the release SHA and artifact hashes.

Rationale:
- Staging readiness must be auditable and reproducible from one immutable release anchor.
- Rollback rehearsal without artifact proof is treated as incomplete.

Enforcement:
- `scripts/gate_release.sh` validates required artifacts and emits `artifacts/gate-release/release-gate-traceability.json`.
- `scripts/release_migration_matrix.sh` emits rollback rehearsal evidence, including upgrade-seed scan proof.
- Runbook expectations are aligned in `docs/runbooks/migrations.md` and `docs/runbooks/rollback.md`.

## 2026-02-17 - P2P/GST Truth Contracts Are Semantic, Not Variable-Name-Coupled
Decision:
- Tax and settlement truthsuite contracts must validate deterministic rounding/settlement semantics, not local variable names.
- Contract updates must remain consistent across GST and P2P truth tests to avoid cross-lane false blockers.

Rationale:
- Source-string tests are useful as drift sentinels, but brittle variable-name coupling creates non-functional failures.
- Deterministic replay and settlement guards are the invariant; symbol names are not.

Enforcement:
- `TS_GstRoundingDeterminismContractTest` and `TS_P2PPurchaseJournalLinkageTest` were reconciled to keep strict-lane gate compatibility.
- `TS_P2PPurchaseSettlementBoundaryTest` now asserts supplier settlement fail-closed guards (on-account adjustment restrictions, over-allocation cap, non-negative remaining clamp).

## 2026-02-05 - Period Close Is Atomic + Uses Canonical Posting
Decision:
- Period close acquires a DB lock on the accounting period row; concurrent postings block until the close commits.
- Closing/reopen journals are posted via `AccountingService` (canonical posting boundary), not direct balance mutation.

Rationale:
- Eliminates race windows and ensures close/reopen runs through the same validation/audit path as other postings.

Enforcement:
- `AccountingPeriodService.closePeriod(...)` locks the period and uses `AccountingService.createJournalEntry(...)`.
- `AccountingPeriodService.reopenPeriod(...)` reverses closing entries via `AccountingService.reverseJournalEntry(...)`.
- Tests: `CR_PeriodCloseAtomicityTest`.

## 2026-02-05 - Payroll Run Identity Uniqueness Is Deploy-Blocking
Decision:
- The `V125` unique index on `(company_id, run_type, period_start, period_end)` is intentional and must not auto-delete or merge data.
- Predeploy scan #6 in `scripts/db_predeploy_scans.sql` must return zero rows before applying V125; duplicates must be deduped in staging/prod snapshots first.

Rationale:
- Failing closed prevents ambiguous payroll history and preserves idempotency guarantees.

Enforcement:
- `V125__payroll_run_identity_unique.sql` creates the unique index.
- `scripts/db_predeploy_scans.sql` query #6 surfaces duplicates (NO-SHIP if any rows).

## 2026-02-05 - Legacy HR Payroll Run Creation Is Hard-Gated
Decision:
- `/api/v1/hr/payroll-runs` creation remains 410 (with canonicalPath) to prevent legacy runs without period identity.
- Any migration window should be feature-flagged aliasing in non-prod only; prod remains gated.

Rationale:
- Prevents schema-drift runs that violate period identity requirements.

Enforcement:
- `HrController.createPayrollRun(...)` returns 410.
- Tests: `CR_PayrollLegacyEndpointGatedIT`.

## 2026-02-05 - Payroll Run Idempotency Treats Remarks As Material
Decision:
- Payroll run idempotency signatures include `remarks`. Retries with different remarks fail closed (409).

Rationale:
- Aligns with mismatch-safe idempotency; remarks are part of the material payload.

Enforcement:
- `PayrollService.buildRunSignature(...)` includes remarks; mismatches raise a conflict.

## 2026-02-05 - Payroll Mark-Paid Is Immutable On Retries
Decision:
- `markAsPaid` does not update payment reference or paid lines on retries; corrections require a separate admin repair path.

Rationale:
- Prevents mutation of historical payroll lines under idempotent retries.

Enforcement:
- `PayrollService.markAsPaid(...)` skips already-PAID lines.
- Test: `CR_PayrollIdempotencyConcurrencyTest.markAsPaid_idempotent_doesNotDoubleApplyAdvances`.

## 2026-02-04 - Credit Note Idempotency Is Mandatory (Reference or Idempotency Key)
Decision:
- Credit note creation requires an idempotency key or explicit reference number; replays are mismatch-safe (409 on payload drift).
- Credit note idempotency keys are mapped via `journal_reference_mappings` for auditability.

Rationale:
- Prevents duplicate credit journals under retries/concurrency and preserves a single auditable credit entry.

Enforcement:
- `AccountingService.postCreditNote(...)` reserves idempotency before posting and validates replay payloads.
- Tests: `CR_SalesReturnCreditNoteIdempotencyTest`, `CreditDebitNoteIT`.

## 2026-02-03 - Orchestrator Audit/Outbox Identifiers + Mismatch-Safe Idempotency
Decision:
- Orchestrator outbox and audit records persist `traceId`, `requestId`, and `idempotencyKey` as first-class columns.
- Orchestrator command idempotency is mismatch-safe: same key + different payload fails closed with 409.
- `X-Request-Id` is accepted for external orchestration calls; when absent, `requestId` defaults to the idempotency key.

Rationale:
- Enables postmortems without parsing payload JSON blobs and guarantees stable end-to-end identifiers.
- Prevents replay-based double posting or conflicting side effects under retries.

Enforcement:
- `V121__orchestrator_audit_outbox_identifiers.sql` adds columns + indexes to `orchestrator_outbox` and `orchestrator_audit`.
- `DomainEvent` includes `traceId/requestId/idempotencyKey`; `CommandDispatcher` normalizes `requestId` and passes identifiers
  to `TraceService` and outbox.
- Tests: `OrchestratorControllerIT.approve_order_is_idempotent_and_audited`,
  `OrchestratorControllerIT.approve_order_rejects_idempotency_mismatch`.

## 2026-02-03 - COGS Slip-Scoped Truth + Movement Linkage
Decision:
- COGS journals are slip-scoped only (`COGS-<slipNumber>`); order-level COGS posting is disabled.
- Dispatch inventory movements link to slips via `inventory_movements.packing_slip_id` and are linked to the slip’s COGS journal.

Rationale:
- Prevents duplicate COGS journals across order-level helpers and enforces correct linkage when multiple slips exist.

Enforcement:
- `SalesService.confirmDispatch(...)` links movements by slip id and only sets order-level COGS for single-slip orders.
- `SalesFulfillmentService` disables order-level COGS posting and resolves COGS journals from slips.
- `V120__inventory_movements_packaging_slip_id.sql` adds the slip linkage column; tests: `OrderFulfillmentE2ETest.dispatchConfirm_idempotent_andRestoresArtifacts`, `OrderFulfillmentE2ETest.partialDispatch_invoicesShippedQty_andCreatesBackorderSlip`, `SalesFulfillmentServiceTest.forcesOrderLevelCogsPostingDisabled`.

## 2026-02-03 - Sales Journal Canonical Reference + Alias Mapping
Decision:
- Dispatch-truth AR/Revenue journals use `INV-<orderNumber>` when a single slip exists, and
  `INV-<orderNumber>-<slipNumber>` once multiple slips exist, preserving slip-level idempotency on backorders.
- Invoice-number references are treated as aliases and mapped via `journal_reference_mappings`.
- Idempotency is mismatch-safe across canonical/alias references (replay with different payload fails closed).

Rationale:
- Prevents duplicate AR/Revenue journals across reference namespaces while avoiding collisions across multiple slips.
- Ensures a single, auditable source of financial truth per dispatch slip.

Enforcement:
- `SalesService.confirmDispatch(...)` derives a slip-scoped order key and passes it to `AccountingFacade.postSalesJournal(...)`.
- `AccountingFacade.postSalesJournal(...)` canonicalizes the reference and maps invoice aliases.
- CODE-RED tests enforce idempotency across reference variants.

## 2026-01-27 - Dispatch-Truth Invoicing + Posting
Decision:
- Canonical path for shipping/invoicing/posting is `SalesService.confirmDispatch(...)`.
- Invoice quantity + AR/Revenue/Tax + COGS are derived from shipped quantities (dispatch truth).

Rationale:
- Eliminates order-truth revenue recognition and double-post risks.
- Aligns postings with actual stock movements and packaging slip idempotency.

Enforcement:
- Order-truth posting paths are disabled or fail closed.
- Slipless invoice issuance fails closed unless an invoice already exists.

## 2026-01-27 - Payroll Single Canonical Path
Decision:
- Canonical payroll computation lives in HR service layer; posting is owned by Accounting via `AccountingFacade`.
- Alternate/legacy payroll creation/posting paths are routed or disabled.

Rationale:
- Prevents duplicate payroll runs and inconsistent idempotency/posting logic.

Enforcement:
- Payroll run idempotency is enforced by scope (company + runType + period).

## 2026-01-27 - Manual Journal Reference Namespace
Decision:
- Manual journal idempotency keys must not use system-reserved reference prefixes.
- Manual journal idempotency keys should use the `MANUAL-` prefix (recommended) and must include a memo/reason.

Rationale:
- Prevents collisions with system-generated references (SALE-/INV-/COGS-/RMP-/etc).
- Improves auditability and replay/idempotency safety.

Enforcement:
- Reserved prefix list is centralized and validated on manual journal creation (idempotency key only).

## 2026-01-28 - Manual Journal References Are System-Generated Only
Decision:
- Manual journal entry API treats caller-supplied `referenceNumber` as an idempotency key only.
- Canonical reference numbers for manual journals are always system-generated.

Rationale:
- Prevents any collision with system reference namespaces (including company-prefixed invoice numbers).
- Eliminates audit/log integrity risks from user-selected references.

Enforcement:
- Manual journal creation fails closed if the idempotency key matches a system-reserved namespace
  (including company-prefixed invoice numbers like `*-INV-*`).

## 2026-01-27 - CompanyClock Is Canonical For Business Dates
Decision:
- Business date and timezone handling uses `CompanyClock` everywhere (company timezone), never server timezone.

Rationale:
- Prevents month-boundary and period-close errors caused by server timezone drift.

Enforcement:
- ZoneId.systemDefault() is forbidden in business logic (gate via review + scan).

## 2026-01-28 - Manufacturing & Packaging Canonical Flow (Bulk -> Size SKUs)
Decision:
- Bulk batches (SKU-BULK) are the only source for size SKUs.
- Packing is batch-based and uses per-product variants + BOM (no legacy size-only mappings).
- Packing is deterministic, idempotent, and posts conversion journals via AccountingFacade.

Rationale:
- Removes ambiguous bulk/size mapping and ensures traceable, auditable cost flow.
- Aligns inventory and accounting with real factory operations.

Status (important):
- This is a *target* model (Plan B) and is not fully implemented in the current codebase.
- CODE-RED stabilization work must preserve existing packing behavior and focus on safety hardening first.

Enforcement:
- Stabilization (Scope A): keep current packing flows, but harden them (idempotency + deterministic references +
  AccountingFacade-only posting).
- Full canonicalization (Scope B): when explicitly approved, hard cutover to the canonical flow in
  `docs/CODE-RED/packaging-flow.md` and fail closed if variant/BOM configuration is missing.

## 2026-01-30 - AP Reconciliation Sign Convention
Decision:
- AP reconciliation compares "what we owe" consistently by normalizing the GL liability balance to match the supplier
  ledger sign convention (credit - debit) before comparing.

Rationale:
- `Account.balance` is maintained with a debit-positive convention; liability "credit balances" show as negative numbers.
  Without normalization, reconciliations appear "off by 2x" even when both sides agree.

Enforcement:
- `ReconciliationService` applies the sign normalization for AP reconciliations; CODE-RED reconciliation tests must pass.

## 2026-01-30 - Audit Logging Must Not Participate In Business Transactions
Decision:
- Audit logging is non-blocking: audit writes run `@Async` and in `REQUIRES_NEW`.
- Any public wrapper method in `AuditService` must call the proxied bean (not `this`) so Spring AOP actually applies the
  async/transactional behavior.

Rationale:
- Prevents SERIALIZABLE business transactions (dispatch) from taking unnecessary locks/contention and failing under
  concurrency due to audit table writes.

Enforcement:
- `AuditService` uses self-proxy injection for wrapper -> `logEvent(...)` calls; CODE-RED dispatch concurrency tests must pass.

## 2026-01-30 - CODE-RED Scope Split: Stabilization vs Full Canonicalization
Decision:
- CODE-RED work is split into:
  - Scope A: stabilize current behavior ("betterment only": idempotency, determinism, locks, invariants, migrations)
  - Scope B: full canonicalization (higher-risk behavior changes; only after stabilization)

Rationale:
- Prevents scope creep and accidental behavior changes while the priority is deploy safety.

Enforcement:
- Scope is documented in `docs/CODE-RED/scope.md`.
- New behavior changes require an explicit decision entry + QA sign-off before merge.

## 2026-01-30 - Inventory->GL Auto-Posting Must Be Enterprise-Grade
Decision:
- Inventory->GL auto-posting is allowed, but only if it is enterprise-grade:
  - durable (outbox + retry), observable (no silent swallow), and exactly-once (reserve-first references)
  - posts via `AccountingFacade` (period locks, date validation, cache invalidation)
  - uses the true business date of the operation (never defaults to UTC "today")

Rationale:
- The existing listener behavior can leave inventory and GL out of sync when posting fails or dates are wrong.

Enforcement:
- Implement an outbox table + worker; block period close/deploy if any inventory->GL outbox rows are FAILED/PENDING.

## 2026-01-30 - Sales Returns Use Separate Dealer Credit Balance (Redeem On Future Dispatch)
Decision:
- Sales returns create a dealer credit balance (credit note) and do NOT reduce the original invoice outstanding at return time.
- Dealer credit must be linked to dealerId, visible in portal, and redeemable on future dispatch confirmations (reduces the
  new invoice outstanding without a cash journal).

Rationale:
- Matches operational reality: returns create credits that can be used on future purchases.

Enforcement:
- Add `dealer_credit_notes` (or equivalent) open-item tracking and idempotent credit allocation on dispatch.

## 2026-01-30 - Invoice Numbering Uses Indian Financial Year From Issue Date
Decision:
- Invoice numbering derives its year bucket from invoice issueDate (dispatch date), not "today" at generation time.
- For India, the financial year boundary is 1 April -> 31 March, so invoice numbering is fiscal-year based.

Rationale:
- Prevents backdated invoices from being numbered into the wrong year.
- Aligns invoice numbering with Indian FY boundaries used in accounting.

Enforcement:
- Invoice number generator must accept issueDate (or dispatch date) as input and derive fiscal year from it.

FY label format:
- Use a two-year label `YYYY-YY` (e.g., `2025-26`). (See 2026-02-01 decision.)

## 2026-01-30 - Dealer Credit Redemption + Credit Limit Visibility
Decision:
- Dealer credit redemption is manual by amount: user provides an `applyCreditAmount` at dispatch confirmation.
- Credit allocation is system-selected and deterministic (recommended: FIFO by oldest OPEN credit notes).
- Credit limit visibility:
  - show "available credit" and "remaining headroom" at proforma stage (before dispatch)
  - after dispatch is posted, show invoice outstanding + credit applied (not credit-limit headroom) on that invoice

Rationale:
- Keeps sales workflow simple (they type how much to use).
- Keeps posting invariant stable: dispatch creates invoice + journals once, and credit reduces outstanding without new cash journals.
- Prevents credit-limit confusion after the invoice is finalized and posted.

## 2026-01-31 - Dealer (and Supplier) Onboarding Creates Subledger Accounts
Decision:
- If a dealer is not found, the salesperson creates the dealer explicitly (directory/search → create), before creating a sales order.
- Creating a dealer must automatically create and link:
  - a receivable account (code pattern: `AR-<dealerCode>`)
  - a dealer portal user (if the email is not already registered), with `ROLE_DEALER`
- Creating a supplier must automatically create and link:
  - a payable account (code pattern: `AP-<supplierCode>`)

Rationale:
- Avoids silent duplicate dealers and duplicate receivable/payable accounts caused by typos, retries, or partial failures.
- Ensures every order/invoice/journal can be traced back to a stable partner id and the correct subledger account.

Enforcement:
- Dealer creation already enforces this via `DealerService.createDealer(...)`.
- Supplier creation already enforces this via `SupplierService.createSupplier(...)`.
- Dealer onboarding must not call the legacy helper `SalesService.createDealer(...)` (it does not match canonical onboarding behavior).

## 2026-01-31 - Orchestrator Company Isolation + Health Endpoint Security
Decision:
- Orchestrator must derive the active `companyId` from the authenticated company context (JWT + `CompanyContextHolder`).
- Any mismatch between authenticated company context and a caller-provided `X-Company-Id` must fail closed (403).
- Orchestrator health endpoints must not be public in non-dev environments (require auth/ops role or be moved under secured actuator health).

Rationale:
- Prevents cross-company actions via header spoofing (tenant isolation).
- Prevents exposing internal operational details via unauthenticated health endpoints.

## 2026-02-02 - Query Count Reductions (Inventory Adjustments + Payroll Auto-Calculation)
Decision:
- Apply low-risk query-count reductions that do not change business semantics.
  - Inventory adjustments lock all referenced finished goods up front (deterministic pessimistic lock) and avoid redundant per-line `save(...)` calls.
  - Payroll auto-calculation prefetches attendance in one query and bulk inserts payroll run lines.

Rationale:
- Reduces DB round-trips and lock overhead under load without changing accounting/inventory outcomes.

Enforcement:
- Must pass `bash scripts/verify_local.sh` and existing CODE-RED test suite.

Enforcement:
- Add method-level authorization on orchestrator health endpoints.
- Add tests: header mismatch rejected; anonymous health endpoints rejected.

---

## 2026-02-01 - Period Close Uses Period-End Snapshots (As-Of Truth)
Decision:
- Period close and reporting use **period-end snapshots** as the source-of-truth for closed periods:
  - trial balance
  - inventory valuation
  - subledger totals (AR/AP)
- Open periods may continue to support “as-of” views, but **closed means closed**: closed period numbers must not change
  when “today” changes or when later postings occur.

Rationale:
- Strongest “enterprise” guarantee for auditability and correctness under backdated/late postings.
- Makes FIFO/WAC inventory valuation safer and cheaper to report for historical periods.

Enforcement:
- Closing a period must persist the snapshot atomically (company + period).
- Reports and reconciliation must read snapshot results for CLOSED periods.

## 2026-02-05 - Report Responses Carry Snapshot Metadata (Fail Closed Without Snapshot)
Decision:
- Report responses must include metadata identifying the source of truth:
  - `source` = `LIVE`, `AS_OF`, or `SNAPSHOT`
  - `asOfDate`, `accountingPeriodId`, and `snapshotId` when applicable
- If a period is CLOSED, report endpoints must fail closed unless a matching snapshot exists
  (no fallback to live balances).

Rationale:
- Auditability requires that every financial report can be traced to its data source.
- Prevents silent drift when late postings or missing snapshots exist.

Enforcement:
- Report selection is centralized and blocks closed-period reads without snapshots.
- Tests cover snapshot selection + metadata on closed periods.

## 2026-02-05 - Cash-Flow Uses Cash/Bank Movements Only (Direct Method)
Decision:
- Cash-flow is a direct-method cash movement summary.
- Only cash/bank/wallet/UPI asset accounts are included; non-cash lines are excluded.

Rationale:
- Summing all journal lines nets to ~0 on balanced books and masks real cash movement.
- Cash-flow must reflect actual cash movement, not balanced ledger totals.

Enforcement:
- Cash-flow report filters to cash/bank accounts only.
- Test: `CR_Reports_CashFlow_NotZeroByConstructionIT`.

## 2026-02-01 - AccountingEventStore Is Not Accounting Truth
Decision:
- `accounting_events` / `AccountingEventStore` is **not** a source-of-truth for temporal accounting.
- Journals + period locks + period snapshots are the accounting truth.
- If `accounting_events` remains in the schema, it is treated as an internal audit/diagnostic log only (no correctness guarantees).

Rationale:
- A partially wired event store is worse than none: it creates a false sense of as-of correctness.
- CODE-RED favors deploy safety and audit clarity over theoretical elegance.

Enforcement:
- Remove “temporal truth” claims from docs and any reporting code paths.
- Do not build critical reports on `accounting_events`.

## 2026-02-01 - Invoice FY Label Format Uses Two-Year Label
Decision:
- Invoice numbers print the fiscal year as a two-year label: `YYYY-YY` (e.g., `2025-26`).

Rationale:
- Minimizes ambiguity and matches common Indian FY expectations in audit/compliance contexts.

Enforcement:
- Invoice numbering must derive the FY bucket from invoice `issueDate` (dispatch date) and render the label as `YYYY-YY`.

---

## 2026-02-02 - Orchestrator Cannot Set SHIPPED/DISPATCHED (Fail-Closed)
Decision:
- Orchestrator fulfillment/status endpoints must **not** set `SHIPPED`/`DISPATCHED` directly.
- Dispatch truth must come from the canonical dispatch confirmation path: `POST /api/v1/sales/dispatch/confirm`.

Rationale:
- Prevents “status says shipped” while inventory/journals/ledger are missing or inconsistent.
- Keeps orchestrator as a **strong arm** (automation + orchestration), not a parallel truth source.

Enforcement:
- Orchestrator fulfillment update rejects `SHIPPED/DISPATCHED` with a business invalid-state error and canonicalPath hint.
- Add/keep tests ensuring orchestrator cannot bypass dispatch truth.

## 2026-02-02 - Release Scans Can Be Enforced (FAIL_ON_FINDINGS)
Decision:
- CODE-RED scan scripts support `FAIL_ON_FINDINGS=true` for release commits (fail deployment if drift/overlaps are detected).
- The local+CI pipeline must not swallow scan failures (scan infra failures must fail the gate).

Rationale:
- Prevents shipping with known schema drift risks unless explicitly waived.
- Keeps the release gate deterministic and repeatable.

Enforcement:
- On release commits, run `FAIL_ON_FINDINGS=true bash scripts/verify_local.sh` (or record an explicit waiver).

## 2026-02-05 - Schema Drift Scan Waiver File (Legacy Migrations)
Decision:
- Legacy migrations containing `CREATE TABLE/INDEX IF NOT EXISTS` or deterministic
  `UPDATE ... FROM` backfills are explicitly waived via a reviewed allowlist.

Rationale:
- Historical migrations cannot be edited; waivers keep `FAIL_ON_FINDINGS` strict
  for new work while acknowledging legacy patterns.

Enforcement:
- Allowed files are listed in `scripts/schema_drift_scan_allowlist.txt`.
- Waiver rationale is documented in `docs/CODE-RED/SCHEMA_DRIFT_SCAN_WAIVER.md`.

## 2026-02-05 - Flyway Overlap Scan Waiver (Convergence Migrations)
Decision:
- Known duplicate table/constraint/index names created by convergence migrations
  are explicitly waived via a reviewed allowlist.

Rationale:
- Convergence migrations intentionally re-declare constraints/indexes to align
  drifted schemas without editing historical migrations.

Enforcement:
- Allowed overlap names are listed in `scripts/flyway_overlap_allowlist.txt`.
- Waiver rationale is documented in `docs/CODE-RED/FLYWAY_OVERLAP_SCAN_WAIVER.md`.

## 2026-02-06 - Packing Record Idempotency Key Is Mandatory
Decision:
- `POST /api/v1/factory/packing-records` requires an idempotency key
  (header `Idempotency-Key` or `request.idempotencyKey`).
- Replays with the same key must return the same outcome, and payload mismatches
  fail closed with conflict.

Rationale:
- Packing retries must never double-consume packaging materials or double-post
  conversion journals.

Enforcement:
- Controller rejects requests missing idempotency key material.
- `PackingRequestRecord` reserves key usage per `(company_id, idempotency_key)`
  before side effects.

## 2026-02-02 - Orchestrator Command Idempotency Is Mandatory
Decision:
- Every orchestrator write command requires `Idempotency-Key` and must be exactly-once under retries.
- Orchestrator persists a scope reservation in `orchestrator_commands` with `(company_id, command_name, idempotency_key)` and a `request_hash`.

Rationale:
- Orchestrator sits at the integration boundary; retries/double-clicks are normal and must never double-post inventory/accounting side effects.

Enforcement:
- Missing `Idempotency-Key` fails validation.
- Same key + different payload hash returns a concurrency conflict (409).

## 2026-02-02 - Payroll Payments Clear Salary Payable (Not Expense)
Decision:
- Payroll payment recording is a **liability clearing** entry: **Dr SALARY-PAYABLE / Cr CASH** (not a second payroll expense).
- Payment journals are linked separately on the payroll run via `payroll_runs.payment_journal_entry_id`.
- HR “mark paid” is blocked unless a payment journal exists (no “PAID with no evidence”).

Rationale:
- Prevents double-expensing and enforces a traceable audit chain: Run posting (expense/liability) → payment clearing (liability/cash).

Enforcement:
- `POST /api/v1/accounting/payroll/payments` requires a POSTED payroll run and validates the payment amount against the posted journal’s SALARY-PAYABLE credit.
- `POST /api/v1/payroll/runs/{id}/mark-paid` fails closed unless a payment journal exists.

---

## 2026-02-02 - AccountingFacade Is The Canonical Posting Boundary (“Posting Firewall”)
Decision:
- `AccountingFacade` is the canonical module-level posting boundary for journals in CODE-RED (sales, purchasing, inventory adjustments, payroll).

Rationale:
- Centralizes reference namespace rules, dedupe behavior, and posting invariants so we do not “accidentally” create parallel posting logic across services.
- Makes audits and incident response simpler: one place to reason about posting semantics and safety checks.

Enforcement:
- New module-level posting work must be added to `AccountingFacade` (or an explicitly reviewed alternative) instead of calling `createJournalEntry` ad-hoc.
- Any legacy/direct posting paths must either be aliased into `AccountingFacade` or prod-gated if they can double-post.

## 2026-02-02 - Idempotency Must Be Mismatch-Safe (Fail Closed On Conflicts)
Decision:
- If a retry/replay hits an existing idempotency scope (key or reference) but the payload differs materially (amount/accounts/lines), the system must fail closed with a conflict (409).

Rationale:
- Returning an “existing” document that doesn’t match the caller’s intent is silent corruption: it creates operational/financial drift without an obvious error.

Enforcement:
- Idempotency implementations must compare request hashes (or a normalized equivalent) and reject mismatches.
- Reference-based dedupe that cannot validate payload must be treated as unsafe and tightened or prod-gated.

## 2026-02-02 - Prod Feature Flags Must Be Enforced In Service Layer (Defense In Depth)
Decision:
- Any prod-gated capability must enforce gating in the service layer too (not only controller-level checks).

Rationale:
- Controllers are not a complete security boundary (internal calls, tests, refactors, or alternate invocations can bypass controller gates).
- Defense-in-depth reduces the risk of accidental production activation.

Enforcement:
- `CommandDispatcher` / `IntegrationCoordinator` must fail closed when orchestrator flags are off, even if invoked internally.
- Denied attempts should still be auditable (trace/audit record), without producing business side effects.

---

## 2026-02-03 - Tenant Context Uses companyCode (Not companyId)
Decision:
- The tenant context selector is `companyCode` (string) and must not be represented as `companyId` in APIs, headers, JWT claims, or thread-local context.

Rationale:
- Avoids a high-risk code smell where `companyId` sometimes means numeric DB id and sometimes means company code string.
- Reduces tenant isolation bugs caused by engineers passing the wrong identifier into repository lookups or security checks.

Enforcement:
- Canonical vocabulary lives in `docs/CODE-RED/IDENTITY_AND_NAMING.md`.
- Deprecate misleading legacy names (`X-Company-Id`, JWT `cid`, DTO fields named `companyId` for code strings) with a backward compatible parsing window.
- New code must use explicit `companyCode` naming for context.

## 2026-02-03 - Enterprise Observability Identifiers Are Standardized
Decision:
- The system standardizes identifiers across modules:
  - `requestId` (HTTP ingress), `traceId` (end-to-end business flow), `correlationId` (async fan-out), `idempotencyKey` (exactly-once), `referenceNumber` (business doc reference).

Rationale:
- Enables enterprise-grade audit (“what happened and who did it”) across sales/inventory/accounting/payroll/orchestrator without parsing payloads.

Enforcement:
- Canonical contract lives in `docs/CODE-RED/OBSERVABILITY_IDENTIFIERS.md`.
- Outbox/audit/event writers must persist identifiers in queryable columns (payload parsing is not an operations strategy).
- Exception handling must reuse existing identifiers; do not generate fresh traceIds when one exists.

---

## 2026-02-06 - Release Promotion Requires Five Confidence Lanes
Decision:
- Release promotion uses one authoritative confidence suite across five lanes:
  - `gate-fast`
  - `gate-core`
  - `gate-release`
  - `gate-reconciliation`
  - `gate-quality`

Rationale:
- A single `mvn verify` signal is not sufficient for enterprise release decisions.
- Lane separation makes regressions explicit: PR safety, mainline integration, strict release checks, reconciliation truth, and long-horizon quality durability.

Enforcement:
- CI workflow definitions must expose these lane jobs and publish lane evidence artifacts.
- `gate-release` and `gate-reconciliation` are mandatory for release SHA promotion.
- Latest `gate-quality` run must be green in the promotion window.

---

## Open Decisions (Must Be Resolved Before We Claim “Enterprise Deploy-Ready”)

These are intentionally listed here so new agents don’t implement conflicting behavior.

None currently (as of 2026-02-03). Add new items here if/when we hit true ambiguity that could cause conflicting implementations.
