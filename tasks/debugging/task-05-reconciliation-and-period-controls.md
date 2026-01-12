# Task 05 — Reconciliation + Period Controls (GL/Subledger/Inventory Tie‑Out, Close/Lock Safety)

## Purpose
**Accountant-level:** prove the ERP can be closed and audited: AR/AP subledgers reconcile to control accounts, inventory valuation reconciles to GL, and period lock/close controls prevent back-dated corruption.

**System-level:** harden and verify reconciliation endpoints, period close workflows, and linkage assertions with tests and evidence.

## Scope guard (explicitly NOT allowed)
- No new reconciliation “features” (new report UIs, new workflows).
- Do not weaken period close validations.
- No manual data fixes without an auditable script/runbook and explicit evidence.

## Milestones

### M1 — Reconciliation controls: inventory ↔ GL, AR/AP ↔ control accounts
Deliverables:
- Confirm reconciliation endpoints/report outputs match the reconciliation contracts (`erp-domain/docs/RECONCILIATION_CONTRACTS.md`).
- Add/extend assertions so reconciliation cannot report “reconciled” while variances exist beyond tolerance.

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=ReconciliationControlsIT,InventoryGlReconciliationIT test`

Evidence to capture:
- Reconciliation endpoints output (JSON) showing variance=0 (or bounded tolerance) for seeded scenarios.
- SQL checks proving subledgers sum to control accounts as-of date.

Stop conditions + smallest decision needed:
- If reconciliation fails due to missing account defaults: smallest decision is whether to block posting until defaults are configured (fail‑closed) vs allow but flag; default to fail‑closed for financially significant postings.

### M2 — Period lock/close/reopen controls (no posting into locked/closed)
Deliverables:
- Verify:
  - lock prevents posting
  - close produces a closing journal entry and sets status `CLOSED`
  - reopen requires reason and restores posting ability safely
- Verify correct behavior across month boundaries (avoid future-date drift).

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=PeriodCloseLockIT test`

Evidence to capture:
- Period state transitions and closing journal linkage evidence (API/DB).
- Rejection evidence for posting attempts during lock/close.

Stop conditions + smallest decision needed:
- If period logic relies on server local timezone: smallest decision is whether to standardize to company timezone (preferred) vs UTC; document the chosen policy and enforce via tests.

### M3 — Reconciliation evidence pack (ops SQL + API checklist)
Deliverables:
- Create a reusable reconciliation evidence checklist (commands + expected outputs) for a candidate prod DB:
  - orphan journals
  - orphan movements
  - subledger vs control tie-out
  - inventory valuation vs GL inventory control tie-out
- Ensure it fits the evidence standard (`docs/ops_and_debug/EVIDENCE.md`).

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

Evidence to capture:
- The checklist itself and sample outputs on a local compose DB.

Stop conditions + smallest decision needed:
- If prod schema differs from local: smallest decision is whether to (A) maintain versioned SQL per schema version, or (B) add runtime endpoints that expose necessary invariants. Prefer (A) to avoid new endpoints.

