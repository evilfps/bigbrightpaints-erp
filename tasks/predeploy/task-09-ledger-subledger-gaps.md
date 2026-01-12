# Task 09 — Ledger/Subledger Integrity (AR/AP/Inventory Reconciliation Gaps)

## Purpose
**Accountant-level:** guarantee that AR/AP subledgers reconcile to their GL control accounts and that inventory valuation reconciles to the inventory control account, within the documented tolerance.

**System-level:** ensure reconciliation is not “happy path only” by hardening the reporting + close checklist logic and by adding regression tests for known edge cases (partial settlements, returns, write-offs, FX/rounding).

## Scope guard (explicitly NOT allowed)
- No new financial products (no new cash/bank module, no new allocation UI).
- No new report UIs; only correctness fixes and tests for existing reports/endpoints.
- No schema rewrites; only forward migrations if required for integrity constraints/indexes.

## Milestones

### M1 — Prove reconciliation contracts on golden scenarios (zero variance)
Deliverables:
- Ensure these reconciliation contracts hold (tolerance ≤ 0.01):
  - Inventory valuation ↔ inventory control account
  - Dealer ledger totals ↔ AR control accounts
  - Supplier ledger totals ↔ AP control accounts
- Ensure close checklist blocks close/lock when variances exist or links are missing.

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=ReconciliationControlsIT test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=InventoryGlReconciliationIT test`

Evidence to capture:
- Output (or assertions) showing variance = 0.00 (within tolerance) on golden fixtures.
- Any variance breakdowns explaining deltas (must be empty/zero after fixes).

Stop conditions + smallest decision needed:
- If reconciliation depends on missing default accounts: choose “fail closed” (require defaults) rather than auto-picking accounts.

### M2 — Audit and neutralize “unallocated payment/receipt” drift risks
Target endpoints (high risk if used without allocation support):
- `POST /api/v1/accounting/receipts/dealer` (cash/AR journal without invoice allocations)
- `POST /api/v1/accounting/suppliers/payments` (AP/cash journal without purchase allocations)

Deliverables:
- Decide (with evidence) one of:
  - **Remove** the endpoint(s) if verified unused, or
  - **Deprecate + guard** to prevent use in ways that break invoice/purchase outstanding tracking (must be test-proven).
- Add tests that demonstrate the chosen policy prevents “books reconcile but invoice/purchase outstanding is wrong” scenarios.

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Statement* test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Aging* test`

Evidence to capture:
- Proof of usage/non-usage (repo search, tests, docs references; if available, logs).
- If removed: OpenAPI diff showing removal and any failing consumers updated.
- If guarded: failing test before + passing test after (no silent drift).

Stop conditions + smallest decision needed:
- If endpoint usage cannot be determined: default to **keep but deprecated** and do not remove; document the risk and require a product owner decision for removal.

### M3 — Period close/lock discipline: prevent posting into closed/locked periods
Deliverables:
- Confirm every financially-impacting flow honors period rules (postings and reversals):
  - invoice issue/credit note/debit note
  - settlements
  - payroll post/payment journals
  - inventory adjustments and valuation journals
- Add/extend tests that attempt posting/reversal into a locked period and assert rejection.

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=PeriodCloseLockIT test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Accounting* test`

Evidence to capture:
- Test names + assertions showing period enforcement for each flow.
- Any documented exceptions (should be none without explicit admin override).

Stop conditions + smallest decision needed:
- If a flow currently bypasses accounting service period validation, decide whether to (A) route through existing accounting posting contract, or (B) block the flow until period enforcement is added.
