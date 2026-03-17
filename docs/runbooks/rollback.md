# Rollback Runbook

## 2026-03-17 — `auth-merge-gate-hardening.password-reset-delivery-tracking`

- **Scope:** revert `migration_v2/V162__password_reset_token_delivery_tracking.sql` and the delivered-only password-reset rollback flow that depends on `password_reset_tokens.delivered_at`.
- **Application rollback:** redeploy the previous backend build before reopening traffic so runtime code stops reading or writing delivery-state markers on password-reset tokens.
- **Database rollback:** after the reverted build is live, execute `DELETE FROM public.password_reset_tokens; ALTER TABLE public.password_reset_tokens DROP COLUMN IF EXISTS delivered_at;` so the old code resumes from a clean canonical token state instead of inheriting mixed delivery-state rows.
- **Verification:** rerun `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest='PasswordResetServiceTest,AuthPasswordResetPublicContractIT,TS_RuntimePasswordResetServiceExecutableCoverageTest' test` and `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp test -Pgate-fast -Djacoco.skip=true` against the reverted packet to confirm forgot-password masking and reset-token issuance still behave correctly without the delivery marker column.

## 2026-03-08 — `o2c-truth.dealer-credit-and-proforma-boundary`

- **Scope:** revert `V159__sales_order_payment_mode.sql` and the commercial-boundary code that depends on `sales_orders.payment_mode`.
- **Application rollback:** redeploy the previous backend build before re-enabling traffic so application code stops reading or writing the new column.
- **Database rollback:** run `ALTER TABLE public.sales_orders DROP COLUMN IF EXISTS payment_mode;` only after confirming the reverted build is active and no pending migration step depends on the column.
- **Verification:** rerun `cd erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='DealerServiceTest,SalesServiceTest,ErpInvariantsSuiteIT' test` against the reverted packet to confirm commercial create/update/dispatch flows are healthy again.

## 2026-03-13 — `corrections-and-control.closed-period-exception-ledger`

- **Scope:** revert `V161__manual_journal_attachments_and_closed_period_exceptions.sql` and the closed-period exception flow that depends on `journal_entries.attachment_references` plus `closed_period_posting_exceptions`.
- **Application rollback:** redeploy the previous backend build before reopening traffic so no runtime path attempts to read or write closed-period exception approvals or manual journal attachment references.
- **Database rollback:** after the reverted build is live, execute `DROP INDEX IF EXISTS idx_closed_period_posting_exceptions_company_expiry; DROP INDEX IF EXISTS idx_closed_period_posting_exceptions_company_document; DROP INDEX IF EXISTS ux_closed_period_posting_exceptions_company_public; DROP TABLE IF EXISTS closed_period_posting_exceptions; ALTER TABLE journal_entries DROP COLUMN IF EXISTS attachment_references;`.
- **Verification:** rerun `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Dtest='AccountingControllerJournalEndpointsTest,AccountingPeriodServiceTest,ClosedPeriodPostingExceptionRepositoryTest,ClosedPeriodPostingExceptionServiceTest,CR_PeriodCloseAtomicityTest,CR_PeriodCloseDriftScansTest' test` against the reverted packet and confirm period-close workflows fail closed without referencing the removed exception ledger.
