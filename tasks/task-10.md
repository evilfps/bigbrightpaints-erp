# Epic 10 — Cross-Module Linkage & Traceability Verification (No New Features)

## Objective
Prove and enforce that the current ERP’s modules are correctly connected:
- every downstream artifact (movements/journals/ledgers) is linked to its source document
- every upstream document can find its derived artifacts
- reversals preserve an auditable chain

This epic is specifically about **verification + fixing linkage gaps** in existing flows (not adding new business features).

## Likely touch points (exact)
- Cross-module domains:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/**`
- High-signal migrations already present (examples to validate/align with):
  - `erp-domain/src/main/resources/db/migration/V22__dealer_ledger_entries.sql`
  - `erp-domain/src/main/resources/db/migration/V26__invoice_journal_link.sql`
  - `erp-domain/src/main/resources/db/migration/V29__inventory_movement_journal_link.sql`
  - `erp-domain/src/main/resources/db/migration/V47__partner_settlement_allocations.sql`
  - `erp-domain/src/main/resources/db/migration/V70__accounting_event_store.sql`
  - `erp-domain/src/main/resources/db/migration/V88__journal_reference_mappings.sql`
- Tests (where linkage is best enforced):
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/smoke/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/test/**`

## Step-by-step implementation plan
1) Produce a linkage matrix (docs + test expectations) for current flows:
   - O2C: order → dispatch/slip → inventory movements → invoice → journal → dealer ledger.
   - P2P: purchase/intake → inventory movements → settlement/payment → journal → supplier ledger.
   - Production: production log → packing → FG batches → inventory movements → (optional) journal.
   - Payroll: payroll run → journal → mark-paid/payment → reports.
2) For each chain, identify the actual “link keys” currently used (ids/refs) by reading:
   - entities/repositories and Flyway migrations that introduced link columns.
3) Add invariant checks (prefer tests over new runtime features):
   - fail if any created document is missing its required links
   - fail if a link points cross-company
   - fail if a reversal is missing a reference to the original
4) Backfill/fix link gaps safely (only if missing links exist in current behavior):
   - forward Flyway migrations for constraints/indexes (avoid rewriting old migrations)
   - data backfills as bounded, safe migrations (or an ops-script + documented one-time run)
5) Add “repeat request” protection checks where linkage errors are caused by retries:
   - verify idempotency markers prevent duplicate movements/journals for the same business event.

## Acceptance criteria
- Golden-path E2E scenarios prove that:
  - every expected link in each chain exists (no null/missing links)
  - journals are balanced and reference their source documents
  - subledgers (dealer/supplier) reference the same events that hit GL control accounts
  - reversal chains are complete and auditable
- Linkage tests fail fast with actionable messages when any chain breaks.

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- Focused (example): `mvn -f erp-domain/pom.xml -Dtest=*FullCycle* test`

