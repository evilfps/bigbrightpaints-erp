# Rollback Runbook

## 2026-03-27 — `erp-23.finished-good-bulk-flag-hard-cut`

- **Scope:** revert `migration_v2/V171__drop_finished_good_batch_legacy_bulk_flag.sql` and the ERP-23 hard-cut runtime packet that removes legacy BULK flag dependencies from FG stock-truth flows and catalog item setup internals.
- **Application rollback:** do not redeploy pre-ERP-23 runtime against a database where `finished_good_batches.is_bulk` is already dropped.
- **Database rollback:** preferred path is snapshot/PITR restore to pre-`V171`. If restore is unavailable and immediate compatibility is required, run:
  - `ALTER TABLE public.finished_good_batches ADD COLUMN IF NOT EXISTS is_bulk BOOLEAN NOT NULL DEFAULT FALSE;`
  - `CREATE INDEX IF NOT EXISTS idx_fg_batch_bulk ON public.finished_good_batches (company_id, is_bulk);`
  and redeploy only after pre-cut runtime compatibility is confirmed.
- **Guard note:** emergency SQL backfill is temporary recovery only; canonical target remains the ERP-23 hard-cut schema with no `is_bulk` flag.
- **Verification:** after rollback path selection, rerun:
  - `cd erp-domain && mvn -Pgate-fast -Djacoco.skip=true test`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-23-fg-stock-truth && bash scripts/guard_openapi_contract_drift.sh`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-23-fg-stock-truth && bash scripts/guard_legacy_migration_freeze.sh`

## 2026-03-27 — `auth-v2.scoped-account-hard-cut`

- **Scope:** revert `migration_v2/V168__auth_v2_scoped_accounts.sql`, `migration_v2/V169__auth_v2_single_company_account.sql`, and the auth-v2 runtime packet that hard-cuts identity to scoped accounts, scopes password reset/MFA/session state by auth scope, and keeps the ERP-37 superadmin control plane bound to the platform auth scope.
- **Application rollback:** do not redeploy a pre-auth-v2 backend against a database that has already applied `V168` or `V169`. Keep the auth-v2-compatible backend live unless the database is first restored to a pre-`V168` state.
- **Database rollback:** restore the affected tenant/database from a snapshot or point-in-time backup taken before `V168`. Ad hoc reverse SQL is intentionally unsupported because the packet normalizes emails, rewrites scoped identity ownership, reattributes refresh tokens, and removes the old shared multi-company auth shape.
- **Guard note:** the auth-v2 migration contract now fails fast on platform-code collisions, post-normalization email collisions, and ambiguous refresh-token mappings. If any of those guards already ran on a live tenant, treat the database as forward-only until a snapshot/PITR restore is available.
- **Verification:** after restore, rerun `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest=TS_AuthV2ScopedAccountsMigrationContractTest test` and `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest=AuthPlatformScopeCodeIT,AuthTenantAuthorityIT,AuthPasswordResetPublicContractIT,AdminUserServiceTest,CompanyControllerIT,CompanyContextFilterControlPlaneBindingTest,SuperAdminControllerTest,SuperAdminTenantControlPlaneServiceTest,CompanyServiceTest,TenantAdminProvisioningServiceTest,TenantOnboardingServiceTest,PasswordResetServiceTest,TenantRuntimeEnforcementServiceTest,TS_RuntimePasswordResetServiceExecutableCoverageTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeCompanyControllerExecutableCoverageTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_AuthV2ScopedAccountsMigrationContractTest test` against the reverted packet before reopening traffic. If rollback is aborted and the tenant stays on auth-v2, rerun `bash ci/check-enterprise-policy.sh` plus the same focused auth/superadmin pack to confirm the forward contract still holds.

## 2026-03-26 — `erp-37.superadmin-control-plane-hard-cut`

- **Scope:** revert `migration_v2/V167__erp37_superadmin_control_plane_hard_cut.sql` and the ERP-37 runtime packet that hard-cuts tenant control onto `/api/v1/superadmin/tenants/{id}/...`, rewrites lifecycle storage to `ACTIVE/SUSPENDED/DEACTIVATED`, renames the concurrency quota column to `quota_max_concurrent_requests`, persists main-admin/support/onboarding truth on `companies`, and adds `tenant_support_warnings` plus `tenant_admin_email_change_requests`.
- **Application rollback:** do not redeploy a pre-ERP-37 backend against a database that has already applied `V167`. Keep the ERP-37-compatible backend live unless the database is first restored to a pre-`V167` state.
- **Database rollback:** restore the affected tenant/database from a snapshot or point-in-time backup taken before `V167`. Ad hoc SQL rollback is intentionally unsupported because `V167` rewrites lifecycle values, renames the concurrency quota column, and introduces new persisted support/email-change truth that older code does not serve.
- **Guard note:** the only reviewed `schema_drift_scan` exception for `V167` is the deterministic ranked-admin backfill that seeds `main_admin_user_id` and onboarding admin truth; if that backfill has already run, treat the tenant as forward-only and use snapshot/PITR instead of hand-edited reverse SQL.
- **Verification:** after restore, rerun the ERP-37 contract packet against the reverted build to confirm the old route family and schema pair are back together; if rollback is aborted and the tenant stays on ERP-37, rerun `cd erp-domain && export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test` plus the focused ERP-37 integration batch before reopening traffic.

## 2026-03-24 — `erp-36.opening-stock-v2-contract-alignment`

- **Scope:** revert `migration_v2/V166__opening_stock_batch_key_contract_alignment.sql` and the v2 parity fix that now drops the legacy partial batch-key/replay indexes before rewriting legacy `opening_stock_imports` rows onto `opening_stock_batch_key`, enforces the non-null batch-key uniqueness contract, and removes `replay_protection_key`.
- **Application rollback:** do not redeploy a pre-hard-cut opening-stock build against a database that has already applied `V166`. Keep the ERP-36 batch-key backend active, or pair any broader ERP-36 app rollback with a pre-`V166` database restore.
- **Database rollback:** restore the affected tenant/database from a snapshot or point-in-time backup taken before `V166`. Ad hoc SQL rollback is intentionally unsupported because `V166` rewrites historical batch keys and drops replay fingerprints that cannot be reconstructed losslessly after the fact.
- **Verification:** after restore, confirm the old schema/runtime pair together by rerunning the pre-ERP-36 opening-stock replay regression packet before reopening traffic; if rollback is aborted and the tenant stays on the hard-cut build, rerun `mvn -f "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup/erp-domain/pom.xml" -s "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup/erp-domain/.mvn/settings.xml" -Djacoco.skip=true -Dtest=com.bigbrightpaints.erp.truthsuite.inventory.TS_OpeningStockBatchKeyV2MigrationContractTest test` to confirm the forward-compatible migration evidence still holds.

## 2026-03-24 — `erp-33.pause-hr-payroll-module`

- **Scope:** revert `migration_v2/V165__pause_hr_payroll_module.sql` and the ERP-33 hard-cut runtime behavior that removes `HR_PAYROLL` from tenant defaults/existing companies while hiding payroll/admin/portal/orchestrator HR surfaces behind the module gate.
- **Application rollback:** redeploy the previous backend build before reopening traffic so runtime code stops enforcing the hard payroll cut and resumes the pre-ERP-33 mixed-surface behavior.
- **Database rollback:** after the reverted build is live, execute `UPDATE public.companies SET enabled_modules = CASE WHEN enabled_modules ? 'HR_PAYROLL' THEN enabled_modules ELSE enabled_modules || '\"HR_PAYROLL\"'::jsonb END; ALTER TABLE public.companies ALTER COLUMN enabled_modules SET DEFAULT '[\"MANUFACTURING\",\"PURCHASING\",\"PORTAL\",\"REPORTS_ADVANCED\",\"HR_PAYROLL\"]'::jsonb;`.
- **Tested rollback path:** the forward packet was validated with `AccountingPeriodServiceTest`, `IntegrationCoordinatorTest`, `ModuleGatingInterceptorTest`, `ModuleGatingServiceTest`, `AdminSettingsControllerApprovalsContractTest`, `AdminSettingsControllerTenantRuntimeContractTest`, `AdminApprovalRbacIT`, and `HrPayrollModulePauseIT`; rerun the same packet against the reverted build to confirm payroll surfaces and diagnostics are restored only after the backend rollback is active.
- **Verification:** rerun `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-33-merge-fix/erp-domain" && DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn clean -Dtest=AccountingPeriodServiceTest,IntegrationCoordinatorTest,ModuleGatingInterceptorTest,ModuleGatingServiceTest,AdminSettingsControllerApprovalsContractTest,AdminSettingsControllerTenantRuntimeContractTest,AdminApprovalRbacIT,HrPayrollModulePauseIT test` against the reverted packet and confirm tenants again expose the pre-pause payroll paths only under the restored backend contract.

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
- **Verification:** rerun `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test` and `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Dtest=OpenApiSnapshotIT,CatalogControllerCanonicalProductIT,AccountingCatalogControllerSecurityIT test` against the reverted packet to confirm canonical catalog import/product behavior is back on the pre-PR-128 contract.

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
