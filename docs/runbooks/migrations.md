# Migration Runbook

## 2026-03-08 — `V159__sales_order_payment_mode.sql`

- **Purpose:** persist explicit commercial payment mode on `sales_orders` so credit, cash, and hybrid proforma behavior stays durable across create, update, confirm, and dispatch flows.
- **Forward plan:** add nullable `payment_mode`, backfill legacy rows to `CREDIT`, then enforce the default and non-null constraint after data is normalized.
- **Dry-run command:** `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='DealerServiceTest,SalesServiceTest,ErpInvariantsSuiteIT' test`
- **Rollback strategy:** if the packet must be reverted before dependent features land, deploy the previous application build and execute `ALTER TABLE public.sales_orders DROP COLUMN IF EXISTS payment_mode;` in the same maintenance window after confirming no newer code depends on the column.

## 2026-03-08 — `V161__manual_journal_attachments_and_closed_period_exceptions.sql`

- **Purpose:** add additive schema support for corrections/control governance by persisting manual-journal attachment references on `journal_entries` and recording one-hour closed-period posting exceptions in a dedicated table.
- **Forward plan:** apply `V161` as part of the corrections/control packet after confirming the branch is using Flyway v2 only, then verify the recovery packet against the targeted accounting/control suites that exercise manual-journal evidence, period-close approvals, and replay-safe correction flows.
- **Dry-run command:** `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='SalesReturnServiceTest,AccountingPeriodServicePolicyTest,AccountingServiceTest,CR_DealerReceiptSettlementAuditTrailTest' test`
- **Rollback strategy:** if rollback happens before deploy, revert the recovery packet and skip applying `V161`; if rollback happens after deploy, deploy the previous application build and execute `DROP TABLE IF EXISTS public.closed_period_posting_exceptions;` plus `ALTER TABLE public.journal_entries DROP COLUMN IF EXISTS attachment_references;` in the same maintenance window after confirming no later packet depends on those schema objects.
