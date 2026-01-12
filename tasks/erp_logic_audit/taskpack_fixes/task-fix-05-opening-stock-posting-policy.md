# Fix Taskpack — Opening Stock Must Produce an Opening GL Posting (or Fail-Closed)

Confirmed flaw: **LF-005**

Status: **DRAFT (planning only; no implementation in audit run)**

## Scope
- Define and enforce the opening balance posting policy for onboarding:
  - opening stock import must not silently create inventory without a corresponding GL opening entry.

## ERP expectation (what “correct” means)
- Opening balances must be auditable and reconcilable:
  - opening stock quantity/value → inventory batches/movements → opening journal → inventory control tie-out.

## Primary evidence (baseline + after)
- API:
  - `POST /api/v1/inventory/opening-stock` (dev-only) followed by inventory reconciliation report.
- SQL:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/08_period_integrity_backdating_and_post_close_edits.sql` (ensure opening entry respects period rules)

## Milestones (implementation plan)
### M1 — Decide the opening posting contract (hard gate)
- Decide which account(s) are used:
  - Debit: Inventory control (per item class / company default)
  - Credit: Opening balance equity / suspense / “opening stock clearing” (configurable)
- Document in `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md` and onboarding docs.

### M2 — Add onboarding E2E coverage
- Add an E2E/integration test:
  - import opening stock
  - assert an opening journal is created (or import is blocked without it)
  - assert inventory reconciliation ties within tolerance.

### M3 — Implement posting or fail-closed guard
- Option A: Post an idempotent opening journal as part of import.
- Option B: Require an explicit opening journal reference and block import until provided.
- Ensure idempotency and multi-company safety.

### M4 — Evidence + operational docs
- Add/extend a runbook section describing onboarding sequencing and required accounts/config.
- Capture drift query outputs before/after in `docs/ops_and_debug/EVIDENCE.md`.

## Verification gates (required when implementing)
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

## Definition of Done
- LF-005 eliminated: opening stock cannot create permanent inventory↔GL drift silently.
- Onboarding flow produces an auditable chain-of-evidence for opening balances.
- Evidence queries show no unexpected variances for seeded/golden onboarding scenario.

