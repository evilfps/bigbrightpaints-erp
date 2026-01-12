# Task 06 — Period Close / Lock / Reopen Integrity Hunt

## Scope
- Workflows: month-end checklist, close/lock rules, reopen controls, backdating protections, reversal rules.
- Portals: Accounting, Admin.
- Modules (primary): `accounting`, `reports`.

## ERP expectation
- Posting into locked/closed periods is blocked unless explicitly authorized and auditable.
- Period close gates on reconciliation and linkage checks (no unposted/unlinked/unbalanced artifacts).
- Reopen is controlled, reasoned, and leaves an auditable trail (including reversal of closing entries where appropriate).

## Where to inspect in code
- Period management:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java`
- Posting date enforcement:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` (`createJournalEntry`, `reverseJournalEntry`)
- System settings controlling enforcement:
  - locate period-lock config usage via `systemSettingsService.isPeriodLockEnforced()` in accounting services

## Evidence to gather

### SQL probes
- Backdating and post-close edits:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/08_period_integrity_backdating_and_post_close_edits.sql`
- Unlinked/unposted documents:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql`

### GET-only API probes
- Checklist + periods:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`

### Escalation probes (dev only; controlled POST)
- Attempt to post a journal entry dated into a closed/locked period:
  - without override (expect hard fail)
  - with override (expect auditable and consistent behavior)
- Attempt to reverse a journal from a locked period (expect fail-closed unless allowed by policy)

## What counts as a confirmed flaw (LF)
- Any reliable path that allows posting or modifying financially relevant artifacts inside a closed/locked period without required authorization/audit.
- Close can proceed while checklist is “not ready” in practice (e.g., linkage gaps not detected).
- Reopen/close produces unreconciled state (closing journal missing, reversal missing, or links broken) with reproducible steps.

## Why tests might still pass
- Tests often cover “close works” but not the adversarial paths (backdating, reopen, admin override misuse).
- Time-based logic is sensitive to timezone and clock; tests may not simulate real ops timelines.

## Deliverable
- Confirmed LF items in `tasks/erp_logic_audit/LOGIC_FLAWS.md` with evidence.
- LEADs in `tasks/erp_logic_audit/HUNT_NOTEBOOK.md` for ambiguous policy decisions.

