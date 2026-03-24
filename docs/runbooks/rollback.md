# Rollback Runbook

## 2026-03-23 — `erp-32.credit-request-requester-identity`

- **Scope:** revert `migration_v2/V164__credit_request_requester_identity.sql` and the ERP-32 dealer-portal durable credit-request path that now records `credit_requests.requester_user_id` and `credit_requests.requester_email`.
- **Application rollback:** redeploy the previous backend build before reopening traffic so runtime code stops reading or writing requester identity on durable credit-limit requests and admin approvals return to the pre-identity contract.
- **Database rollback:** after the reverted build is live, execute `ALTER TABLE public.credit_requests DROP COLUMN IF EXISTS requester_email; ALTER TABLE public.credit_requests DROP COLUMN IF EXISTS requester_user_id;`.
- **Verification:** rerun `cd erp-domain && mvn -B -ntp -Dtest='CreditLimitRequestServiceTest,DealerPortalServiceTest,DealerPortalControllerExportAuditTest,AdminSettingsControllerApprovalsContractTest' test` and `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; cd erp-domain && mvn -B -ntp -Dtest='AdminApprovalRbacIT,DealerPortalReadOnlySecurityIT' test` against the reverted packet to confirm dealer-originated durable requests no longer depend on the removed requester-identity columns.

## 2026-03-22 — `opening-stock-results-json`

- **Scope:** revert `migration_v2/V46__opening_stock_import_results_json.sql` and the strict opening-stock history/replay path that now depends on both `opening_stock_imports.results_json` and `opening_stock_imports.replay_protection_key`.
- **Application rollback:** redeploy the previous backend build before reopening traffic so runtime code stops reading or writing row-level `results[]` payloads and replay-protection fingerprints on opening-stock imports.
- **Database rollback:** after the reverted build is live, execute `DROP INDEX IF EXISTS uq_opening_stock_imports_company_replay_key; ALTER TABLE public.opening_stock_imports DROP COLUMN IF EXISTS replay_protection_key; ALTER TABLE public.opening_stock_imports DROP COLUMN IF EXISTS results_json;`.
- **Tested rollback path:** this rollback was validated against the V46 forward plan by replaying the opening-stock regression packet with the previous backend contract, ensuring the removed replay key/index are not required once the reverted build is active.
- **Verification:** rerun `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; mvn -B -ntp -Dtest='OpeningStockPostingRegressionIT,OpeningStockImportControllerTest,OpeningStockImportServiceTest' test` plus `python3 scripts/changed_files_coverage.py --jacoco erp-domain/target/site/jacoco/jacoco.xml --diff-base origin/main` against the reverted packet to confirm opening-stock replay and history are back on the pre-ERP-34 contract.

## 2026-03-21 — `catalog-surface-consolidation.variant-group-linkage`

- **Scope:** revert `migration_v2/V163__catalog_variant_group_linkage.sql` and the canonical catalog write/import flow that depends on `production_products.variant_group_id` and `production_products.product_family_name`.
- **Application rollback:** redeploy the previous backend build before reopening traffic so runtime code stops reading or writing canonical variant-group/product-family fields through the consolidated `/api/v1/catalog/**` surface.
- **Database rollback:** after the reverted build is live, execute `DROP INDEX IF EXISTS idx_production_products_company_variant_group; ALTER TABLE public.production_products DROP COLUMN IF EXISTS variant_group_id; ALTER TABLE public.production_products DROP COLUMN IF EXISTS product_family_name;`.
- **Verification:** rerun `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test` and `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Dtest=OpenApiSnapshotIT,CatalogServiceProductCrudTest,CatalogControllerCanonicalProductIT,AccountingCatalogControllerSecurityIT test` against the reverted packet to confirm canonical catalog import/product behavior is back on the pre-PR-128 contract.

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
