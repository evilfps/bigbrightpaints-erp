# Rollback Runbook

## 2026-03-08 — `V161__manual_journal_attachments_and_closed_period_exceptions.sql`

- **Scope:** revert the closed-period posting exception table and manual-journal attachment-reference column introduced for the corrections/control packet.
- **Application rollback:** redeploy the previous backend build before re-enabling traffic so the application no longer reads or writes `closed_period_posting_exceptions` or `journal_entries.attachment_references`.
- **Database rollback:** after the reverted build is active and no later packet depends on the new schema objects, run `DROP TABLE IF EXISTS public.closed_period_posting_exceptions;` and `ALTER TABLE public.journal_entries DROP COLUMN IF EXISTS attachment_references;` in the same maintenance window.
- **Verification:** rerun `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='AccountingServiceTest,AccountingPeriodServicePolicyTest,SalesReturnServiceTest,PurchaseReturnServiceTest' test` against the reverted packet to confirm manual-journal controls, period-close approvals, and linked correction flows are healthy again.

## 2026-03-08 — `o2c-truth.dealer-credit-and-proforma-boundary`

- **Scope:** revert `V159__sales_order_payment_mode.sql` and the commercial-boundary code that depends on `sales_orders.payment_mode`.
- **Application rollback:** redeploy the previous backend build before re-enabling traffic so application code stops reading or writing the new column.
- **Database rollback:** run `ALTER TABLE public.sales_orders DROP COLUMN IF EXISTS payment_mode;` only after confirming the reverted build is active and no pending migration step depends on the column.
- **Verification:** rerun `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='DealerServiceTest,SalesServiceTest,ErpInvariantsSuiteIT' test` against the reverted packet to confirm commercial create/update/dispatch flows are healthy again.
