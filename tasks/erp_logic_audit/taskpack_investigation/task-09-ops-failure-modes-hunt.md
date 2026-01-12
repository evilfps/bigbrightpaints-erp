# Task 09 — Ops Failure Modes Hunt (Fail-Closed, Drift, “Health That Lies”)

## Scope
- Workflows: config health, smoke checks, drift detection, backup/restore readiness.
- Perspective: SRE/prod readiness.

## ERP expectation
- The system must fail closed:
  - missing critical account mappings prevents posting (no silent skips)
  - partial operations roll back and do not leave orphaned artifacts
- Drift should be detectable via read-only probes.
- Outbox backlogs/poison messages are visible and actionable.

## Where to inspect in code
- Configuration health evaluation:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/health/ConfigurationHealthService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/health/ConfigurationHealthIndicator.java`
- Transaction boundaries for multi-step operations:
  - dispatch confirmation: `SalesService#confirmDispatch` (and nested calls)
  - purchase creation: `PurchasingService#createPurchase`
- Outbox schema and publisher behavior:
  - migrations: `erp-domain/src/main/resources/db/migration/V2__orchestrator_tables.sql`, `V40__outbox_retry_backoff.sql`

## Evidence to gather

### GET-only API probes
- Health endpoints:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/00_health_gets.sh`
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/03_orchestrator_health_gets.sh`
- Finance/ops report probes:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`

### SQL probes (drift dashboard building blocks)
- Orphans/unlinked docs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/04_journals_without_document_link.sql`
- Period integrity:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/08_period_integrity_backdating_and_post_close_edits.sql`
- Outbox:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/13_outbox_backlog_and_duplicates.sql`

## What counts as a confirmed flaw (LF)
- “Health is UP” while config health reports missing critical defaults required for safe posting.
- A reproducible partial commit scenario where failure leaves orphans/unlinked docs.
- Outbox backlog/duplication patterns that can cause repeat side effects in downstream integrations.

## Why tests might still pass
- Integration tests often run in ideal environments with seeded defaults.
- Failure/rollback paths require fault injection (timeouts, exceptions) not present in tests.

## Deliverable
- Confirmed LF items with evidence.
- A prioritized list of “ops drift dashboards” to run daily (read-only).

