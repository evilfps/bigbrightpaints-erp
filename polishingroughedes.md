# v1 Deployment Hardening - Polishing Rough Edges (Design + Execution Log)

Owner: _TBD_

Last updated: 2026-01-21

Scope: Close remaining gaps/bugs identified before v1 deployment. Focus on correctness + guardrails + predictable status transitions + minimal surprises for end users.

---

## 0) Ground Rules / Non-rushed Mode Checklist

- **No silent behavior changes** without tests and release notes.
- **Prefer small, isolated changes** with clear acceptance criteria.
- **All flows must be verifiable end-to-end** (service -> DB -> ledger/journal -> status/outstanding).
- **Idempotency**: operations like "reverse" and "post credit note" must either be idempotent or safely reject duplicates.
- **Observability**: add logs/metrics on settlement + reversals and config validation failures.
- **Backwards compatibility**: avoid DB migrations unless required; if required, make them additive and safe.

---

## 1) AR/AP Journal Context Mismatch

### Problem

User could post an **AR** or **AP** transaction without specifying its **counterparty** (dealer/supplier), creating ambiguous subledger semantics and potentially breaking downstream reporting/reconciliation.

### Desired Behavior

- **AR** journal entries must include **dealer/customer** context.
- **AP** journal entries must include **supplier/vendor** context.
- Disallow creating entries where AR/AP accounts are involved but counterparty is missing.
- Guardrail should exist **even if UI already prevents it**.

### Proposed Fix (Minimal)

Add validation in `createJournalEntry` (or equivalent service layer entry point):

- If any line uses **Accounts Receivable**: require `dealerId`/`customerId`.
- If any line uses **Accounts Payable**: require `supplierId`.
- If both AR and AP appear in a single journal: either reject or require both contexts (decide policy). For v1, **reject** unless there is an explicit business case.

### Acceptance Criteria

- Creating AR/AP journal without the appropriate counterparty **fails with a clear error**.
- Existing non-AR/AP journal entries remain unaffected.
- Validation is covered by unit tests.

### Tests

**Unit**

- `createJournalEntry_AR_withoutDealer_throws`
- `createJournalEntry_AP_withoutSupplier_throws`
- `createJournalEntry_nonARAP_withoutCounterparty_ok`
- Optional: `createJournalEntry_AR_and_AP_in_one_entry_rejected`

**Integration**

- API/UI-level (if available): attempt to post AR/AP without counterparty and verify 4xx + message.

### Implementation Notes

- Prefer validation based on **account type/category** (e.g., `AccountCode.AR`, `AccountGroup.RECEIVABLES`) rather than account name.
- If the system supports **subledger dimensions**, ensure the counterparty dimension is set for AR/AP lines.

### Status

- [ ] Not started
- [ ] In progress
- [ ] Done
- Notes/decisions:

---

## 2) Invoice / Credit Note Status + Settlement Consistency

### Problem

After posting a **Credit Note** (sales) or **Debit Note** (purchase), ledger may be correct but **internal invoice/purchase status** may stay misleading (e.g., still "UNPAID" or "PARTIAL" when it is effectively reversed/settled).

### Desired Behavior

When a credit note is applied to an invoice:

- `Invoice.outstanding` reduces accordingly (can reach 0).
- Invoice status transitions:
  - Outstanding = 0 and fully offset by credit: **PAID** or **REVERSED/VOID** depending on policy.
  - Partial offset: **PARTIAL**.
- If a credit note **voids** the invoice (full reversal), the model supports `VOID` - decide whether:
  - Use `VOID` when invoice is entirely reversed by a credit note referencing it, OR
  - Use `REVERSED` (new status) if present.
  - If neither exists, use `PAID` but add a flag like `settledByCredit=true` (less ideal).

### Proposed Fix

- In the service handling credit/debit notes, after posting journals:
  - Call `InvoiceSettlementPolicy.applyPayment(...)` **or** an analogous method that recalculates:
    - outstanding
    - status (`PARTIAL`, `PAID`, `VOID/REVERSED`)
- Ensure the settlement logic supports **non-cash settlements** (credit note as "payment method").

### Acceptance Criteria

- Full credit note against an invoice results in outstanding = 0 and status updated predictably.
- Partial credit note produces PARTIAL and correct outstanding.
- Settlements are recorded in a way that reconciliation can distinguish cash vs credit note settlement.
- End-to-end tests verify behavior.

### Tests (End-to-End)

1) Issue invoice -> confirm status UNPAID + outstanding = total

2) Post credit note referencing invoice:

   - Full amount -> status becomes VOID/REVERSED (or PAID per decision) + outstanding = 0

   - Partial amount -> status PARTIAL + outstanding decreases

3) Ensure double-applying the same credit note is prevented or idempotent.

### Design Decision Needed

- **What status should a fully credited invoice become?**
  - Option A: `VOID` when credit note fully offsets and references invoice.
  - Option B: `REVERSED` (if exists) for audit clarity.
  - Option C: `PAID` with settlement type "CREDIT_NOTE" (least semantically precise).

Record decision here:

- Decision: Use `VOID` when a credit note fully offsets an invoice.
- Rationale: Credit note represents full reversal of the original invoice; VOID preserves audit intent and prevents further settlement activity.
- Implications (reporting/UI): Invoice shows VOID, dealer ledger payment status resolves to PAID; credit notes remain idempotent via reference tracking.

### Status

- [ ] Not started
- [ ] In progress
- [ ] Done
- Notes/decisions:

---

## 3) Purchase Status on Full Payment / Return

### Problem

`RawMaterialPurchase.status` may not update when outstanding becomes zero (or partially settles). Users may see stale status compared to how invoices behave via `InvoiceSettlementPolicy`.

### Proposed Fix

Mirror invoice behavior:

- When settlements are applied to purchases:
  - If outstanding == 0 -> `PAID`
  - If 0 < outstanding < total -> `PARTIAL`
  - If outstanding == total and no settlement -> `UNPAID` (or equivalent)
- If returns/credit notes can void a purchase, decide whether a `VOID/REVERSED` status exists.

### Acceptance Criteria

- Purchase status transitions match numeric outstanding.
- Covered by unit tests + one integration test for settlement.

### Tests

- `purchaseSettlement_full_setsStatusPAID`
- `purchaseSettlement_partial_setsStatusPARTIAL`
- `purchaseSettlement_none_remainsUNPAID`

### Status

- [ ] Not started
- [ ] In progress
- [ ] Done
- Notes:

---

## 4) Cascade Reverse Implementation (Invoice -> COGS + Payment)

### Problem

Reversing an invoice ideally reverses its linked **COGS entry** and any **payment/settlement** journals to keep bookkeeping and internal state aligned. If not implemented, users must do manual reversals, which is error-prone.

### Options

**Option A (Nice-to-have for v1):** Document manual process

- Clearly document which entries must be reversed in what order.
- Add UI hints.

**Option B (Recommended if feasible):** Implement cascade reversal

- Introduce a single operation:
  - `reverseInvoiceCascade(invoiceId, reason)`
- Behavior:
  1) Reverse invoice journal entry (if posted).
  2) Reverse linked COGS journal entry (if exists).
  3) Reverse settlements/payments associated with the invoice (if posted), or mark them reversed.
  4) Update invoice status to `REVERSED/VOID` and outstanding appropriately.
- Ensure reversals are **auditable**: use reversal entries, not deletes.

### Safety Requirements

- Prevent cascading reversal if dependent objects are in an incompatible state (e.g., already reversed).
- Idempotent behavior: calling twice should not duplicate reversals.
- Concurrency protection: lock invoice during reversal.

### Acceptance Criteria

- Reversing invoice triggers reversal of all linked journals OR returns a deterministic failure with guidance.
- Post-reversal, invoice status is consistent and outstanding returns to expected state.
- Covered by integration tests.

### Tests

- End-to-end: create invoice -> post COGS -> settle payment -> reverse cascade -> verify:

  - All related journals reversed.
  - Invoice state is reversed/voided.
  - No dangling "PAID" settlement remains.

### Status

- [ ] Not started
- [ ] In progress
- [ ] Done
- Notes/decision (A vs B):
  - Option A for v1: document manual cascade steps; use `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse` and include related COGS/payment entry IDs (COGS via `sales_orders.cogs_journal_entry_id` or `packaging_slips.cogs_journal_entry_id`). Payments/settlements must be reversed separately if not reference-linked.
---

## 5) Data Setup / Config Validation (Prod Readiness)

### Problem

Critical configs must exist in production:

- Dispatch accounts for COGS (env vars / settings)
- Base currency per company (defaults to "INR" if not set)
- FX rates required for foreign currency entries (throws if missing)
- Default currency and tax account mappings

### Proposed Fixes

1) **Startup validation** (best):

   - On application start (or per-tenant bootstrap), validate required configuration:
     - base currency exists for each company/tenant
     - dispatch/COGS accounts configured
     - tax account mappings configured (if required)
   - If missing: fail fast (or degrade gracefully with admin-only warnings) depending on your deployment philosophy.

2) **Admin diagnostics endpoint** (optional):

   - `GET /admin/diagnostics/accounting-config` lists missing configs.

### Acceptance Criteria

- Misconfigurations are caught before first real posting in prod.
- Error messages are actionable (which key/tenant missing).
- Smoke test confirms configured prod values.

### Tests

- Unit: validation errors on missing keys
- Integration: boot with missing config triggers expected failure/warning

### Status

- [ ] Not started
- [ ] In progress
- [ ] Done
- Notes:

---

## 6) End-to-End Test Matrix (Minimum for v1)

### Sales

- Invoice creation -> post -> status/outstanding correct
- Apply payment -> status PAID/PARTIAL correct
- Apply credit note (full) -> status VOID/REVERSED correct, outstanding 0
- Apply credit note (partial) -> status PARTIAL correct
- Attempt AR posting without dealer -> rejected

### Purchases

- Purchase creation -> post -> status/outstanding correct
- Apply settlement (full/partial) -> status PAID/PARTIAL correct
- Apply debit note/return -> outstanding adjusts, status correct
- Attempt AP posting without supplier -> rejected

### Reversal

- Reverse invoice only (if supported) -> journal reversed
- Reverse invoice cascade (if implemented) -> invoice + COGS + settlements reversed

---

## 7) Execution Log (Hydrate as You Work)

### 2026-01-21

- Notes:
  - Started from identified gaps list.
  - Waiting on repo access to implement concrete diffs.

Add entries here as work progresses:

### Step 1: Initialize v1 hardening log
- **Timestamp:** 2026-01-21 19:42
- **Context:** kickoff, documentation
- **Description:** Bootstrapped the v1 hardening plan/log and began repo scan for accounting gaps.
- **Changes Made:** Created `polishingroughedes.md` baseline content in repo root.
- **Test Results:** Not run (documentation/setup only).
- **Next Actions:** Review current implementations for AR/AP validation, credit/debit note settlement, purchase status, cascade reversal, and config health checks; implement fixes with tests.

### Step 2: Implement accounting hardening changes
- **Timestamp:** 2026-01-21 19:42
- **Context:** fixes, accounting flows
- **Description:** Enforced AR/AP counterparty context, applied credit/debit note settlements, synced invoice status, and added purchase status updates plus config health checks.
- **Changes Made:** Updated `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/health/ConfigurationHealthService.java`, `erp-domain/docs/PROCURE_TO_PAY_STATE_MACHINES.md`, `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`.
- **Test Results:** Not run yet.
- **Next Actions:** Add and run unit/integration tests for new validations and status updates; verify credit/debit note flows and config health checks.

### Step 3: Add coverage and fixture adjustments
- **Timestamp:** 2026-01-21 20:05
- **Context:** tests, verification
- **Description:** Added unit and e2e coverage for AR/AP validation, credit/debit note settlement status updates, purchase settlement status, and config health checks; adjusted regression fixture to avoid AR/AP-only posting.
- **Changes Made:** Updated `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CreditDebitNoteIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/ProcureToPayE2ETest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/ErpInvariantsSuiteIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/core/health/ConfigurationHealthServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/HighImpactRegressionIT.java`.
- **Test Results:** `mvn -Dtest=AccountingServiceTest,ConfigurationHealthServiceTest test` (pass).
- **Next Actions:** Run full `mvn -B -ntp verify` to confirm end-to-end coverage.

### Step 4: Full verification run
- **Timestamp:** 2026-01-21 20:19
- **Context:** tests, CI parity
- **Description:** Ran the full CI entrypoint to validate end-to-end flows after the hardening changes.
- **Changes Made:** None.
- **Test Results:** `mvn -B -ntp verify` (pass; 247 tests, 4 skipped).
- **Next Actions:** Review diffs for merge readiness and confirm prod config checklist with ops.

### Step 5: Coverage gate resolution + final verification
- **Timestamp:** 2026-01-21 20:59
- **Context:** verification, coverage gate
- **Description:** Parked unrelated local mods, hit JaCoCo branch coverage gate for `com.bigbrightpaints.erp.orchestrator.service`, then restored the minimal test change to satisfy coverage and re-verified. Confirmed decision points: full credit note -> `VOID`, mixed AR/AP journal entries rejected, config health checks include base currency + default accounts.
- **Changes Made:** Stashed unrelated local mods via `git stash push -m "wip unrelated local mods" -- erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/SalesServiceTest.java erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CriticalAccountingAxesIT.java`; restored `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java` via `git checkout stash@{0} -- erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java`; committed hardening changes as `414a47a`.
- **Test Results:** `mvn -B -ntp clean verify` (fail: JaCoCo branch coverage 0.30 < 0.31 for `com.bigbrightpaints.erp.orchestrator.service`); re-run `mvn -B -ntp clean verify` (pass; 245 tests, 4 skipped).
- **Next Actions:** None pending; ready for review.

- Date:
- What changed:
- Tests added/updated:
- Outcomes:
- Follow-ups:

---

## 8) "Real Blockers" Checklist

Stop only if:

- Cannot locate relevant service methods / policies after repo search.
- Status enums/DB constraints prevent required transitions without migration.
- Credit note semantics conflict with accounting requirements (needs product/accounting decision).

If blocked, record:

- Blocker description:
- Impact:
- Proposed resolution paths:
- Owner:
- Links/PRs/issues:

---

## 9) Codex / Agent Prompt (Copy-Paste)

> You are GPT-5.2 Codex acting as a senior engineer. Your task is to harden v1 accounting flows and fully verify changes with tests. Do not rush. Do not stop until all items are implemented and verified, unless you hit a real blocker-then document it clearly in `polishingroughedes.md` with next steps.
>
> **Scope (must complete):**
>
> 1) **AR/AP Journal Context Validation**: In `createJournalEntry` (or equivalent), prevent posting AR/AP lines without required counterparty context: AR requires dealer/customer id; AP requires supplier/vendor id. Add unit tests and at least one integration test.
>
> 2) **Invoice + Credit Note Status Consistency**: When applying credit notes (and debit notes for purchases), ensure original invoice/purchase status and outstanding amounts update correctly via `InvoiceSettlementPolicy.applyPayment` or equivalent settlement logic. Decide and implement whether full credit sets invoice to `VOID` or `REVERSED` (prefer existing enum) and document decision. Add end-to-end tests: issue invoice -> credit it (full/partial) -> verify status/outstanding.
>
> 3) **Purchase Status on Settlement**: Mirror invoice behavior for `RawMaterialPurchase.status` (`PAID` when outstanding==0, `PARTIAL` when partial). Add tests.
>
> 4) **Cascade Reverse**: Verify and, if feasible, implement cascade reversal for invoice reversal to also reverse linked COGS and settlements. If not feasible for v1, document manual reversal process and add guardrails/logs. Add at least one integration test if implemented.
>
> 5) **Config Validation**: Add prod readiness validation for required configs: base currency per company, dispatch/COGS accounts, and necessary tax account mappings. Make error messages actionable. Add tests.
>
> **Implementation requirements:**
>
> - Run full test suite locally; add new tests where gaps exist.
> - Keep changes minimal and isolated; no broad refactors unless needed.
> - Ensure idempotency and safe failure modes for reverse/settlement actions.
> - Update `polishingroughedes.md` continuously with decisions, progress, and links to PRs/commits.
>
> **Search steps (do first):**
>
> - Locate `createJournalEntry` and identify how account types (AR/AP) are detected.
> - Locate credit/debit note posting flow and how it links to invoices/purchases.
> - Locate `InvoiceSettlementPolicy` and purchase settlement equivalents.
> - Locate reversal flow and COGS posting/linking.
> - Locate config loading for base currency, dispatch/COGS accounts, FX rates.
>
> **Deliverables:**
>
> - Code changes implementing all feasible items.
> - Tests (unit + integration/e2e) covering key flows.
> - Updated documentation in `polishingroughedes.md` including acceptance criteria and decisions.
>
> If you encounter a blocker, stop and write:
>
> - what is blocked, why, what you tried, and 2-3 concrete next steps.
