# Migration Runbook

## 2026-03-08 — `V159__sales_order_payment_mode.sql`

- **Purpose:** persist explicit commercial payment mode on `sales_orders` so credit, cash, and hybrid proforma behavior stays durable across create, update, confirm, and dispatch flows.
- **Forward plan:** add nullable `payment_mode`, backfill legacy rows to `CREDIT`, then enforce the default and non-null constraint after data is normalized.
- **Dry-run command:** `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='DealerServiceTest,SalesServiceTest,ErpInvariantsSuiteIT' test`
- **Rollback strategy:** if the packet must be reverted before dependent features land, deploy the previous application build and execute `ALTER TABLE public.sales_orders DROP COLUMN IF EXISTS payment_mode;` in the same maintenance window after confirming no newer code depends on the column.

## 2026-03-13 — `V161__manual_journal_attachments_and_closed_period_exceptions.sql`

- **Purpose:** extend journal-entry storage for manual-attachment references and add the closed-period posting exception ledger used by the period-close correction workflow.
- **Forward plan:** add nullable `journal_entries.attachment_references`, create `closed_period_posting_exceptions`, then build the company/public-id unique index plus the document and expiry lookup indexes before enabling the new controller and service paths.
- **Dry-run command:** `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/pr99-corrections-repair/erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Dtest='AccountingControllerJournalEndpointsTest,AccountingPeriodServiceTest,ClosedPeriodPostingExceptionRepositoryTest,ClosedPeriodPostingExceptionServiceTest,CR_PeriodCloseAtomicityTest,CR_PeriodCloseDriftScansTest' test`
- **Rollback strategy:** if this packet must be reverted before downstream features depend on it, deploy the previous backend build first, then execute `DROP INDEX IF EXISTS idx_closed_period_posting_exceptions_company_expiry; DROP INDEX IF EXISTS idx_closed_period_posting_exceptions_company_document; DROP INDEX IF EXISTS ux_closed_period_posting_exceptions_company_public; DROP TABLE IF EXISTS closed_period_posting_exceptions; ALTER TABLE journal_entries DROP COLUMN IF EXISTS attachment_references;` in the same maintenance window after confirming no live code is writing exception approvals.
