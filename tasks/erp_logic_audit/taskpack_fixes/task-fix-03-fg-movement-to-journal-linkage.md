# Fix Taskpack — Finished-Goods Movement → Journal Linkage (Chain-of-Evidence)

Confirmed flaw: **LF-003**

Status: **DRAFT (planning only; no implementation in audit run)**

## Scope
- Ensure finished-goods inventory movements created during:
  - reservation / release
  - dispatch confirm
  - adjustments (as applicable)
  carry a reliable journal linkage (direct `journal_entry_id` and/or a stable mapping table), per ERP auditability contract.

## ERP expectation (what “correct” means)
- From a posted/confirmed document, an auditor can trace:
  - source document → inventory movements → journal entry → ledger/subledger
  using stable, queryable links (not “best-effort inference”).

## Primary evidence (baseline + after)
- SQL:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/04_journals_without_document_link.sql`
- API:
  - Dispatch-confirm flow; then verify movement rows have journal linkage.

## Milestones (implementation plan)
### M1 — Decide the linkage policy (hard gate)
- Decide which journal(s) should link from FG movements:
  - COGS journal only, or
  - AR/sales journal only, or
  - both via a mapping.
- Update the linkage contract docs:
  - `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`
  - `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`

### M2 — Add invariant tests
- Add an E2E/integration test:
  - create order → dispatch confirm
  - assert at least one `inventory_movements` row for the slip has `journal_entry_id` set (or mapping exists)
  - assert the linked journal belongs to the same company and is balanced.

### M3 — Implement linkage write path
- Update `FinishedGoodsService.recordMovement(...)` / dispatch confirmation flow to set `journal_entry_id` (or mapping) when journals are created.
- Ensure idempotency: replays do not change links incorrectly.

### M4 — Backfill strategy (if required)
- Provide a safe backfill for existing movements:
  - only where deterministic reference matching is possible
  - produce an audit report of rows that cannot be linked automatically.

## Verification gates (required when implementing)
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

## Definition of Done
- LF-003 eliminated: newly created FG movements have a stable journal linkage.
- `SQL/02_orphans_movements_without_journal.sql` shows no new orphans for confirmed/posted flows.
- Focused tests added and passing; evidence captured in `docs/ops_and_debug/EVIDENCE.md`.

