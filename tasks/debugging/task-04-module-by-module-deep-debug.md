# Task 04 — Module‑by‑Module Deep Debug (Failure Modes, Commands, Evidence, Go/No‑Go)

## Purpose
**Accountant-level:** prove that each module’s workflows produce correct, traceable, reconcilable financial outcomes and cannot silently drift under retries or misconfiguration.

**System-level:** run a structured deep‑debug checklist per module, capturing evidence and isolating failures quickly by running focused tests and API/DB assertions.

## Scope guard (explicitly NOT allowed)
- No new workflows or UI.
- No disabling validations or weakening invariants.
- No bulk refactors; only targeted fixes driven by failing tests/invariants (in later execution runs).

## Milestones

### M1 — Sales/O2C deep debug (orders → dispatch → invoice → settlement)
Deliverables:
- Run the Sales focused suite and confirm O2C linkage contracts (Task 03) hold.
- Capture API evidence for dealer statements/aging and invoice/journal linkage.

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused (Sales): `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,OrderFulfillmentE2ETest,DispatchConfirmationIT,DealerLedgerIT,SettlementE2ETest,GstInclusiveRoundingIT test`

Evidence to capture:
- Test logs + surefire summaries for focused suite.
- API responses: dealer ledger, dealer statement/aging, invoice list/detail.
- DB checks: no duplicate journals/movements after replay/idempotency tests.

Stop conditions + smallest decision needed:
- If O2C linkage fails (invoice/movement missing journal link): smallest decision is whether this is (A) a missing link bug requiring fix, or (B) an intentional “non-posting” mode; default to (A) unless explicitly documented in posting contract.

### M2 — Purchasing/P2P + Inventory deep debug (purchases/receipts/returns + adjustments)
Deliverables:
- Run purchasing and inventory focused suites; confirm P2P linkage contracts and inventory↔GL reconciliation.
- Capture evidence for supplier statements/aging and inventory valuation/reconciliation outputs.

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused (Purchasing): `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,ProcureToPayE2ETest,SupplierStatementAgingIT,ReconciliationControlsIT test`
- Focused (Inventory): `mvn -f erp-domain/pom.xml -Dtest=InventoryGlReconciliationIT,DispatchConfirmationIT,LandedCostRevaluationIT,RevaluationCogsIT,ReconciliationControlsIT test`

Evidence to capture:
- Supplier statement/aging outputs and reconciliation variance=0 proof.
- Inventory valuation and reconciliation endpoints output.
- SQL orphan checks for movements without journals (where posting is expected).

Stop conditions + smallest decision needed:
- If reconciliation variance is non‑zero: smallest decision is whether tolerance/rounding policy is inconsistent (fix policy) vs data/linkage missing (fix linkage). Default: linkage first.

### M3 — Factory/Production deep debug (production logs → packing → costing)
Deliverables:
- Run production focused suite and confirm production linkage and costing journals (where enabled).
- Capture evidence for movement references back to production logs and packing records.

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused (Production): `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,FactoryPackagingCostingIT,CompleteProductionCycleTest,WipToFinishedCostIT test`

Evidence to capture:
- Test logs and any EXPLAIN plans for hot production queries (if performance flags).
- DB checks for orphan batches/movements without references.

Stop conditions + smallest decision needed:
- If production creates movements without trace references: smallest decision is whether reference should be persisted (schema) or derived (service mapping). Prefer persisted link if it is an audit requirement.

### M4 — HR/Payroll deep debug (run → approve → post → mark-paid)
Deliverables:
- Run payroll focused suite and confirm payroll posting/linkage/reversal invariants.
- Capture evidence: payroll run state transitions and journal linkage.

Verification gates (run after M4):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused (Payroll): `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,PayrollBatchPaymentIT,PeriodCloseLockIT test`

Evidence to capture:
- API outputs for payroll run details and status transitions.
- Journal linkage proof (payroll run → journal entry id).

Stop conditions + smallest decision needed:
- If payroll “preview math” differs from posted journal: smallest decision is whether calculation service or posting service is authoritative. Default: posted journal must match authoritative payroll calculation; fix drift, do not accept mismatch.

### M5 — Admin/Auth/Dealer Portal deep debug (RBAC + tenancy + read-only guarantees)
Deliverables:
- Verify RBAC and company boundaries using focused auth/admin tests and portal matrix expectations (Task 02).
- Confirm dealer portal is read-only and cannot access cross-dealer or cross-company data.

Verification gates (run after M5):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused (Auth/Admin): `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AuthHardeningIT,MfaControllerIT,AdminUserSecurityIT test`

Evidence to capture:
- Unauthorized/forbidden responses for role mismatch cases (record endpoints + expected status).
- Cross-company access attempts and their rejection evidence.

Stop conditions + smallest decision needed:
- If an endpoint is accessible to “any authenticated user” but should be restricted: smallest decision is to add a fail‑closed `@PreAuthorize` guard consistent with current patterns and add a regression test.

### M6 — Orchestrator/outbox deep debug (idempotency + trace/audit safety)
Deliverables:
- Confirm orchestrator actions are idempotent and trace endpoints do not leak sensitive data.
- Confirm outbox processing health checks are meaningful for ops.

Verification gates (run after M6):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused (Orchestrator): `mvn -f erp-domain/pom.xml -Dtest=OrchestratorControllerIT,CommandDispatcherTest,IntegrationCoordinatorTest test`

Evidence to capture:
- Outbox table inspection (counts, stuck retries) + orchestrator health endpoint output.
- Trace endpoint output redaction review (store samples, redact secrets).

Stop conditions + smallest decision needed:
- If orchestrator health/trace endpoints lack method-level auth: smallest decision is to restrict them to Admin roles (or permitAll only for a safe subset) and add tests proving restriction.

---

## Deep-debug checklist (per module; use as Go/No‑Go criteria)

For each module below, capture:
- What can go wrong (failure modes)
- Commands/tests to run
- Evidence files to capture (append to `docs/ops_and_debug/EVIDENCE.md`, store logs under `docs/ops_and_debug/LOGS/`)
- Pass/fail criteria

### Accounting
- Failure modes: unbalanced journals; missing links; period lock bypass; subledger drift; rounding tolerance drift.
- Minimum tests: `ErpInvariantsSuiteIT`, `ReconciliationControlsIT`, `PeriodCloseLockIT`, `JournalEntryE2ETest`, `CriticalAccountingAxesIT`.
- Pass: variance=0 within tolerance; no missing links for posted artifacts; close/lock rules enforced.

### Sales / Invoice
- Failure modes: duplicate postings on retry; GST rounding drift; dispatch double-work divergence; invoice without journal link.
- Minimum tests: Sales suite in M1.
- Pass: idempotent replay; journals balanced+linked; dealer ledger metadata correct.

### Purchasing/AP
- Failure modes: purchase receipt without journal/movement link; supplier payment without allocations; AP control mismatch.
- Minimum tests: Purchasing suite in M2.
- Pass: supplier statements reconcile; AP control variance=0.

### Inventory
- Failure modes: movement without reference; movement without journal when required; negative stock; valuation mismatch.
- Minimum tests: Inventory suite in M2.
- Pass: inventory↔GL reconciliation variance=0; no orphans.

### Factory/Production
- Failure modes: orphan production logs/packing; incorrect costing; missing WIP/FG journals when enabled.
- Minimum tests: Production suite in M3.
- Pass: references and links present; costing journals balanced+linked.

### HR/Payroll
- Failure modes: state machine bypass; posting doesn’t match payroll math; advances clearing inconsistent; idempotency holes.
- Minimum tests: Payroll suite in M4.
- Pass: state transitions enforced; journals linked+balanced; reversals invert.

### Admin/Auth/Dealer portal
- Failure modes: cross-company leakage; authenticated-only access to privileged endpoints; dealer portal overreach.
- Minimum tests: Auth suite in M5.
- Pass: least privilege enforced; dealer portal read-only and scoped.

### Orchestrator/outbox
- Failure modes: duplicate downstream effects on retry; trace endpoints leak; health endpoints insecure.
- Minimum tests: Orchestrator suite in M6.
- Pass: idempotent event processing; trace safe; ops health meaningful.

