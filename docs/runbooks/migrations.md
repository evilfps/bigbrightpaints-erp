# Migration Runbook

## 2026-03-23 — `migration_v2/V164__credit_request_requester_identity.sql`

- **Purpose:** persist dealer-portal submitter identity on `credit_requests` so ERP-32 durable credit-limit approvals no longer surface anonymous maker rows when dealer users submit permanent limit requests.
- **Forward plan:** add nullable `credit_requests.requester_user_id` and `credit_requests.requester_email`, deploy the backend packet that writes requester identity from the authenticated dealer portal principal, and keep existing historical rows nullable because legacy sales/admin-created durable requests intentionally remain without portal maker metadata.
- **Dry-run commands:**
  - `cd erp-domain && mvn -B -ntp -Dtest='CreditLimitRequestServiceTest,DealerPortalServiceTest,DealerPortalControllerExportAuditTest,AdminSettingsControllerApprovalsContractTest' test`
  - `cd erp-domain && mvn -B -ntp -Ppr-fast -Dtest='com.bigbrightpaints.erp.modules.sales.service.DealerPortalServiceTest,com.bigbrightpaints.erp.modules.sales.controller.DealerPortalControllerExportAuditTest,com.bigbrightpaints.erp.modules.sales.service.CreditLimitRequestServiceTest,com.bigbrightpaints.erp.modules.admin.controller.AdminSettingsControllerApprovalsContractTest,com.bigbrightpaints.erp.modules.accounting.service.StatementServiceTest,com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestCreateRequestTest,com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDecisionRequestTest,com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDtoTest,com.bigbrightpaints.erp.modules.sales.dto.DealerPortalCreditLimitRequestCreateRequestTest' test`
  - `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; cd erp-domain && mvn -B -ntp -Dtest='AdminApprovalRbacIT,DealerPortalReadOnlySecurityIT' test`
- **Rollback strategy:** if this packet must be reverted before merge, redeploy the previous backend build first so runtime code stops reading or writing credit-request maker identity, then execute `ALTER TABLE public.credit_requests DROP COLUMN IF EXISTS requester_email; ALTER TABLE public.credit_requests DROP COLUMN IF EXISTS requester_user_id;` in the same maintenance window.

## 2026-03-22 — `migration_v2/V46__opening_stock_import_results_json.sql`

- **Purpose:** persist canonical row-level `results[]` on `opening_stock_imports` and add `replay_protection_key` so strict opening-stock imports retain replay truth even under fresh idempotency keys and concurrent duplicate submissions.
- **Forward plan:** add nullable `opening_stock_imports.results_json` and `opening_stock_imports.replay_protection_key`, backfill replay keys from `(company_code,file_hash)` while disambiguating historical duplicates, then create the partial unique `(company_id, replay_protection_key)` index before deploying the strict opening-stock packet that records row results/errors in the import record.
- **Dry-run commands:**
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -DskipTests test-compile`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest='RawMaterialControllerTest,OpeningStockImportServiceTest,ProductionCatalogServiceCanonicalEntryTest,CR_CatalogImportDeterminismIT,ProductionCatalogDiscountDefaultRegressionIT,ProductionCatalogFinishedGoodInvariantIT,ProductionCatalogRawMaterialInvariantIT,AccountingCatalogControllerSecurityIT,CatalogControllerCanonicalProductIT' test`
  - `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; mvn -B -ntp -Dtest='OpenApiSnapshotIT,AccountingCatalogControllerSecurityIT,CompanyControllerIT,TenantOnboardingControllerTest,AuthTenantAuthorityIT#admin_cannot_bootstrap_new_tenant+super_admin_can_bootstrap_new_tenant+super_admin_can_bootstrap_new_tenant_with_first_admin_credentials_provisioning,GlobalExceptionHandlerTest,OpeningStockPostingRegressionIT,OpeningStockImportControllerTest,OpeningStockImportServiceTest,TenantOnboardingServiceTest,CatalogServiceCanonicalCoverageTest,CatalogServiceProductCrudTest,ProductionCatalogServiceCanonicalEntryTest,ProductionCatalogServiceBulkVariantRaceTest,SkuReadinessServiceTest' test`
  - `python3 scripts/changed_files_coverage.py --jacoco erp-domain/target/site/jacoco/jacoco.xml --diff-base origin/main`
- **Rollback strategy:** if the packet must be reverted before merge, deploy the previous backend build first, then execute `DROP INDEX IF EXISTS uq_opening_stock_imports_company_replay_key; ALTER TABLE public.opening_stock_imports DROP COLUMN IF EXISTS replay_protection_key; ALTER TABLE public.opening_stock_imports DROP COLUMN IF EXISTS results_json;` in the same maintenance window so reverted code does not inherit persisted row-result payloads or replay keys it does not serve.

## 2026-03-21 — `migration_v2/V163__catalog_variant_group_linkage.sql`

- **Purpose:** add canonical variant-group and product-family storage to `production_products` so consolidated catalog product creation can persist downstream-ready grouping metadata on the single `/api/v1/catalog/**` host.
- **Forward plan:** add nullable `variant_group_id` and `product_family_name` to `production_products`, create the `(company_id, variant_group_id)` lookup index, refresh the OpenAPI snapshot, and only then expose the canonical product/import paths that depend on the new schema.
- **Dry-run commands:**
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Dtest=OpenApiSnapshotIT,CatalogServiceProductCrudTest,CatalogControllerCanonicalProductIT,AccountingCatalogControllerSecurityIT test`
- **Rollback strategy:** if the packet must be reverted before merge, redeploy the previous backend build first, then execute `DROP INDEX IF EXISTS idx_production_products_company_variant_group; ALTER TABLE public.production_products DROP COLUMN IF EXISTS variant_group_id; ALTER TABLE public.production_products DROP COLUMN IF EXISTS product_family_name;` in the same maintenance window so reverted code does not inherit partially populated variant-group metadata.

## 2026-03-17 — `migration_v2/V162__password_reset_token_delivery_tracking.sql`

- **Purpose:** track whether a password-reset token was actually delivered so the forgot-password fallback path can restore only the last delivered token after dispatch or marker failures.
- **Forward plan:** hard-cut invalidate legacy reset-token rows with unknown delivery state by deleting `password_reset_tokens`, add nullable `delivered_at`, then enable the delivered-only restore and delivery-marker rollback flow in `PasswordResetService`.
- **Dry-run commands:**
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest='PasswordResetServiceTest,AuthPasswordResetPublicContractIT,TS_RuntimePasswordResetServiceExecutableCoverageTest' test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp test -Pgate-fast -Djacoco.skip=true`
- **Rollback strategy:** if the packet must be reverted before merge, deploy the previous backend build first, then run `DELETE FROM public.password_reset_tokens; ALTER TABLE public.password_reset_tokens DROP COLUMN IF EXISTS delivered_at;` in the same maintenance window so the reverted code does not inherit mixed delivery-state rows from the new schema.

## 2026-03-08 — `V159__sales_order_payment_mode.sql`

- **Purpose:** persist explicit commercial payment mode on `sales_orders` so credit, cash, and hybrid proforma behavior stays durable across create, update, confirm, and dispatch flows.
- **Forward plan:** add nullable `payment_mode`, backfill legacy rows to `CREDIT`, then enforce the default and non-null constraint after data is normalized.
- **Dry-run command:** `cd erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='DealerServiceTest,SalesServiceTest,ErpInvariantsSuiteIT' test`
- **Rollback strategy:** if the packet must be reverted before dependent features land, deploy the previous application build and execute `ALTER TABLE public.sales_orders DROP COLUMN IF EXISTS payment_mode;` in the same maintenance window after confirming no newer code depends on the column.

## 2026-03-13 — `V161__manual_journal_attachments_and_closed_period_exceptions.sql`

- **Purpose:** extend journal-entry storage for manual-attachment references and add the closed-period posting exception ledger used by the period-close correction workflow.
- **Forward plan:** add nullable `journal_entries.attachment_references`, create `closed_period_posting_exceptions`, then build the company/public-id unique index plus the document and expiry lookup indexes before enabling the new controller and service paths.
- **Dry-run command:** `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Dtest='AccountingControllerJournalEndpointsTest,AccountingPeriodServiceTest,ClosedPeriodPostingExceptionRepositoryTest,ClosedPeriodPostingExceptionServiceTest,CR_PeriodCloseAtomicityTest,CR_PeriodCloseDriftScansTest' test`
- **Rollback strategy:** if this packet must be reverted before downstream features depend on it, deploy the previous backend build first, then execute `DROP INDEX IF EXISTS idx_closed_period_posting_exceptions_company_expiry; DROP INDEX IF EXISTS idx_closed_period_posting_exceptions_company_document; DROP INDEX IF EXISTS ux_closed_period_posting_exceptions_company_public; DROP TABLE IF EXISTS closed_period_posting_exceptions; ALTER TABLE journal_entries DROP COLUMN IF EXISTS attachment_references;` in the same maintenance window after confirming no live code is writing exception approvals.
