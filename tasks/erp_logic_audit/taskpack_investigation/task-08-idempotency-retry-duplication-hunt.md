# Task 08 — Idempotency / Retry / Duplication Hunt

## Scope
- Workflows: sales order creation, dispatch confirmation, settlements, purchases, payroll, outbox/event publication.
- Modules (primary): `sales`, `accounting`, `purchasing`, `hr`, `orchestrator`.

## ERP expectation
- Replays (client retries, at-least-once workers, UI double-click) do not duplicate:
  - journal entries
  - invoices
  - settlement allocations
  - inventory movements that drive valuation
- If the same business reference is reused with a different payload signature, the system rejects the request (fail-closed).

## Where to inspect in code
- Journal idempotency + signature matching:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` (`createJournalEntry`, duplicate matching)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/JournalReferenceResolver.java`
- Sales order idempotency:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`createOrder`)
- Settlement idempotency:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` (`settleDealerInvoices`, `settleSupplierInvoices`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/PartnerSettlementAllocation.java`
- Outbox:
  - DB: `orchestrator_outbox` (see migrations and `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/13_outbox_backlog_and_duplicates.sql`)

## Evidence to gather

### SQL probes
- Duplicates by idempotency key:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/11_idempotency_duplicates.sql`
- Outbox backlog and duplicate emission:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/13_outbox_backlog_and_duplicates.sql`

### Escalation probes (dev only; controlled POST)
- Concurrency replay harness (dev only):
  - parallel POST the same business request (same idempotency key/reference) and confirm:
    - single journal created and linked
    - single invoice created and linked
    - no duplicate allocation rows
  - repeat with a conflicting payload under the same business key and confirm rejection

## What counts as a confirmed flaw (LF)
- Any reproducible duplicate creation under the same intended business key/idempotency key.
- Any “idempotent” replay that silently returns success but with diverging linked artifacts (e.g., journal reused but allocations duplicated).

## Why tests might still pass
- Tests usually do not run concurrent request races.
- Retry semantics depend on network/client behavior not represented in unit tests.

## Deliverable
- Confirmed LF items with evidence.
- LEADs recorded in `tasks/erp_logic_audit/HUNT_NOTEBOOK.md` when concurrency proof is not yet collected.

