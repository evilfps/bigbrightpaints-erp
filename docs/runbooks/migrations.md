# Migration Runbook

Last reviewed: 2026-03-29

## 2026-04-06 — `erp-domain/src/main/resources/db/migration_v2/V177__backfill_packaging_slip_invoice_links.sql`

- **Purpose:** move the packaging-slip invoice-link backfill onto the active Flyway v2 track so upgraded tenants recover persisted `packaging_slips.invoice_id` values from canonical sales-order and invoice truth after the dispatch hard cut.
- **Release-guard posture:** the legacy-track V167 packaging-slip backfill copy is deleted in the same packet. The active v2 track already uses `V167` for the ERP-37 tenant-control migration, so this backfill is reissued as `V177` to preserve Flyway history/checksum safety instead of mutating an already-active v2 version.
- **Forward plan:** apply `V177__backfill_packaging_slip_invoice_links.sql`, then keep the current dispatch/invoice runtime packet live so invoice/read-model code continues reading the persisted `packaging_slips.invoice_id` link without reviving any implicit matcher fallback. Confirm the only remaining packaging-slip invoice-link backfill file lives under `erp-domain/src/main/resources/db/migration_v2`.
- **Dry-run commands:**
  - `find erp-domain/src/main/resources -name '*backfill_packaging_slip_invoice_links.sql'`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='TS_PackagingSlipInvoiceLinkV2MigrationContractTest,CR_PackingRouteHardCutIT,OrderFulfillmentE2ETest,InvoiceServiceTest' test`
  - `bash ci/check-enterprise-policy.sh`
  - `bash scripts/gate_fast.sh`
- **Rollback strategy:** treat `V177` as a forward-only upgrade-path backfill. If rollout must be abandoned after the migration is applied, keep the hard-cut-compatible backend live or restore the database from a pre-`V177` snapshot/PITR before reverting application code. Do not null out `packaging_slips.invoice_id` by hand after the backfill has converged on canonical invoice ownership.

## 2026-03-29 — `erp-domain/src/main/resources/db/migration_v2/V176__opening_stock_content_fingerprint.sql`

- **Purpose:** add a canonical `content_fingerprint` to `opening_stock_imports` so the ERP-39 opening-stock replay guard can detect the same imported content even when callers change `opening_stock_batch_key` or `idempotency_key`.
- **Release-guard posture:** this is a forward-only normalization companion to the ERP-39 hard-cut replay protection. Runtime code remains single-path and relies on the persisted fingerprint instead of compatibility fallbacks.
- **Forward plan:** apply `V176__opening_stock_content_fingerprint.sql` together with the ERP-39 packet, backfill existing rows from the current canonical import keys, enforce `NOT NULL`, and keep the company-plus-fingerprint index live before promoting the stricter replay protection to production.
- **Dry-run commands:**
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Dtest=OpeningStockImportServiceTest test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Dtest=AccountingServiceTest,AccountingAuditTrailServiceTest,SettlementServiceTest,TruthRailsSharedDtoContractTest,LandedCostRevaluationIT,AccountingControllerJournalEndpointsTest,AccountingControllerExceptionHandlerTest test`
  - `ENTERPRISE_DIFF_BASE=53873362b0f9e10ab9e7b587ee6aa79163023e7a bash ci/check-enterprise-policy.sh`
- **Rollback strategy:** treat `V176` as a coordinated app-and-schema cut. If rollout must be abandoned after the migration is applied, keep the ERP-39-compatible backend live or restore the database from a pre-`V176` snapshot/PITR before reverting application code. Do not drop or null out `content_fingerprint` under mixed runtime behavior.

## 2026-03-29 — `erp-domain/src/main/resources/db/migration_v2/V175__canonicalize_company_gst_accounts.sql`

- **Purpose:** normalize company GST account bindings on upgrade paths so GST-mode tenants do not retain a null `gst_payable_account_id`, and non-GST tenants do not keep stale GST input/output/payable account IDs after the strict health checks ship.
- **Release-guard posture:** this is another ERP-48 data canonicalization migration. It keeps the current fail-closed GST health/runtime rules intact by repairing old tenant rows instead of adding runtime fallbacks.
- **Forward plan:** apply `V175__canonicalize_company_gst_accounts.sql` together with ERP-48. Non-GST tenants are cleared of `gstInputTaxAccountId`, `gstOutputTaxAccountId`, and `gstPayableAccountId`; GST-mode tenants backfill those fields from canonical GST/TDS/default-tax accounts where possible.
- **Dry-run commands:**
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -f erp-domain/pom.xml -B -ntp -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=GstConfigurationRegressionIT,ConfigurationHealthServiceTest test`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -f erp-domain/pom.xml -B -ntp -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Derp.openapi.snapshot.verify=true -Dtest=OpenApiSnapshotIT test`
  - `bash ci/check-enterprise-policy.sh`
- **Rollback strategy:** treat `V175` as forward-only normalization inside ERP-48. If rollout must be abandoned after the migration is applied, keep the ERP-48-compatible backend live or restore the database from a pre-`V175` snapshot/PITR before reverting application code. Do not selectively repopulate GST account columns by hand.

## 2026-03-29 — `erp-domain/src/main/resources/db/migration_v2/V174__backfill_default_discount_accounts.sql`

- **Purpose:** backfill `companies.default_discount_account_id` for pre-hard-cut tenants so the canonical finished-good/account-default contract can remain fail-closed without breaking upgraded companies that still have a null discount default.
- **Release-guard posture:** this is a data canonicalization migration, not a compatibility bridge. It seeds one real `DISC` expense account per company only when needed, then binds `default_discount_account_id` to `DISC` or an existing `SALES-RETURNS` account. Runtime code remains single-path and still fails closed if a tenant is missing a discount default after the migration.
- **Forward plan:** apply `V174__backfill_default_discount_accounts.sql` together with the ERP-48 packet. Do not deploy the stricter default-account validation without this backfill on upgrade paths.
- **Dry-run commands:**
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -f erp-domain/pom.xml -B -ntp -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -f erp-domain/pom.xml -B -ntp -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=CompanyDefaultAccountsServiceTest,SalesControllerIdempotencyHeaderTest,InventoryAdjustmentControllerTest,RawMaterialControllerTest test`
  - `bash ci/check-enterprise-policy.sh`
- **Rollback strategy:** treat `V174` as forward-only data normalization inside ERP-48. If rollout must be abandoned after the migration is applied, keep the ERP-48-compatible backend live or restore the database from a pre-`V174` snapshot/PITR before reverting application code. Do not null out `default_discount_account_id` by hand after the packet is deployed.

## 2026-03-28 — `erp-domain/src/main/resources/db/migration_v2/V173__company_lifecycle_constraint_hard_cut.sql`

- **Purpose:** finalize the tenant lifecycle hard-cut by rewriting any lingering `HOLD` or `BLOCKED` values to `SUSPENDED` or `DEACTIVATED`, dropping the legacy lifecycle constraints, and installing the canonical `chk_companies_lifecycle_state_v173` constraint on `companies.lifecycle_state`.
- **Release-guard posture:** this packet also hardens the release harness itself. `scripts/verify_local.sh`, `scripts/gate_release.sh`, and `scripts/release_migration_matrix.sh` were fixed in the same cut so fresh-path and upgrade-path Flyway v2 proofs are real and hermetic instead of depending on local helper-path quirks.
- **Forward plan:** apply `V173__company_lifecycle_constraint_hard_cut.sql`, deploy the ERP-48 hard-cut packet together with the canonical auth/accounting/control-plane runtime changes, and keep `ACTIVE`, `SUSPENDED`, and `DEACTIVATED` as the only supported lifecycle vocabulary. Do not preserve or reintroduce the pre-hard-cut constraint names or state values.
- **Dry-run commands:**
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_fast.sh`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_core.sh`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_reconciliation.sh`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_release.sh`
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 PGHOST=127.0.0.1 PGPORT=55432 PGUSER=erp PGPASSWORD=erp PGDATABASE=postgres MIGRATION_SET=v2 bash scripts/release_migration_matrix.sh --artifact-dir artifacts/gate-release`
- **Rollback strategy:** treat `V173` as a coordinated app-and-schema cut. If rollout must be abandoned after the migration is applied, keep the ERP-48-compatible backend live or restore the affected database from a pre-`V173` snapshot/PITR before reopening traffic with older code. Do not attempt an ad hoc reverse-SQL rollback that leaves mixed legacy and current lifecycle constraints in place.

## 2026-03-27 — `erp-domain/src/main/resources/db/migration_v2/V171__drop_finished_good_batch_legacy_bulk_flag.sql`

- **Purpose:** remove the retired `finished_good_batches.is_bulk` flag and its supporting index so FG batch storage no longer carries the legacy BULK/semi-finished marker in the canonical inventory model.
- **Release-guard posture:** this is a hard-cut cleanup migration tied to ERP-23; runtime code in the same packet no longer depends on `is_bulk`, and the change is intentionally fail-fast (no compatibility bridge column retained).
- **Forward plan:** apply `V171__drop_finished_good_batch_legacy_bulk_flag.sql`, deploy the ERP-23 hard-cut packet together with this migration, and keep the canonical catalog item + FG stock-truth paths as the only live model.
- **Dry-run commands:**
  - `cd erp-domain && mvn -Dtest=GlobalExceptionHandlerTest,TS_RuntimeGlobalExceptionHandlerExecutableCoverageTest,OpeningStockPostingRegressionIT,ProductionCatalogFinishedGoodInvariantIT,ProductionCatalogRawMaterialInvariantIT,ProductionCatalogDiscountDefaultRegressionIT,CR_CatalogImportDeterminismIT test`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-23-fg-stock-truth && bash scripts/guard_openapi_contract_drift.sh`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-23-fg-stock-truth && bash scripts/guard_legacy_migration_freeze.sh`
  - `cd erp-domain && mvn -Pgate-fast -Djacoco.skip=true test`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-23-fg-stock-truth && git diff --check`
- **Rollback strategy:** treat `V171` as a coordinated app-and-schema cut. Do not redeploy pre-ERP-23 code against a database where `is_bulk` is dropped. If rollback is required, prefer restoring a pre-`V171` snapshot/PITR; emergency SQL backfill to re-add `is_bulk` is only for short-term recovery when snapshot restore is unavailable.

## 2026-03-27 — `erp-domain/src/main/resources/db/migration_v2/V168__auth_v2_scoped_accounts.sql` + `erp-domain/src/main/resources/db/migration_v2/V169__auth_v2_single_company_account.sql`

- **Purpose:** hard-cut auth identity to one scoped account per `(normalized_email, auth_scope_code)`, split shared-email multi-company users into independent scoped credentials, migrate refresh-token attribution onto scoped accounts, and enforce one company binding per tenant-scoped login while keeping the platform scope as the only superadmin realm.
- **Release-guard posture:** this packet was rebased after ERP-37 and renumbered from the earlier draft `V167`/`V168` pair to `V168`/`V169` so it lands after the merged superadmin control-plane schema. The migration fails fast on platform-code collisions, normalized email collisions, and ambiguous refresh-token backfills instead of carrying compatibility shims.
- **Forward plan:** apply `V168__auth_v2_scoped_accounts.sql`, then `V169__auth_v2_single_company_account.sql`, then deploy the auth-v2 backend packet from branch `auth-v2-hard-cut` so runtime code, reset flows, MFA scope, and refresh-token/session semantics all match the new schema in one cut. Keep the ERP-37 canonical superadmin control plane live with the same deployment so platform-scoped tenant control continues to work against the rebased auth contract.
- **Dry-run commands:**
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest=TS_AuthV2ScopedAccountsMigrationContractTest test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest=AuthPlatformScopeCodeIT,AuthTenantAuthorityIT,AuthPasswordResetPublicContractIT,AdminUserServiceTest,CompanyControllerIT,CompanyContextFilterControlPlaneBindingTest,SuperAdminControllerTest,SuperAdminTenantControlPlaneServiceTest,CompanyServiceTest,TenantAdminProvisioningServiceTest,TenantOnboardingServiceTest,PasswordResetServiceTest,TenantRuntimeEnforcementServiceTest,TS_RuntimePasswordResetServiceExecutableCoverageTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeCompanyControllerExecutableCoverageTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_AuthV2ScopedAccountsMigrationContractTest test`
  - `bash ci/check-enterprise-policy.sh`
- **Rollback strategy:** treat `V168` and `V169` as a coordinated app-and-schema cut. Once applied, do not redeploy the pre-auth-v2 backend against that database. If rollout must be abandoned after execution, keep the auth-v2-compatible backend live or restore the tenant/database from a pre-`V168` snapshot/PITR before attempting any broader rollback.

## 2026-03-26 — `erp-domain/src/main/resources/db/migration_v2/V167__erp37_superadmin_control_plane_hard_cut.sql`

- **Purpose:** hard-cut tenant control onto one canonical the canonical superadmin tenant control-plane route family family by rewriting lifecycle storage to `ACTIVE`, `SUSPENDED`, and `DEACTIVATED`, renaming the concurrency quota column to `quota_max_concurrent_requests`, persisting main-admin/support/onboarding truth on `companies`, and creating first-class `tenant_support_warnings` plus `tenant_admin_email_change_requests`.
- **Release-guard posture:** `V167` now uses strict DDL for the new tables/indexes/columns and direct column rename semantics; the only `schema_drift_scan` v2 allowlist entry for this migration is the reviewed deterministic ranked-admin `UPDATE ... FROM` backfill that seeds `main_admin_user_id` and onboarding admin truth.
- **Forward plan:** apply `V167__erp37_superadmin_control_plane_hard_cut.sql`, then deploy the ERP-37 backend packet that serves the canonical superadmin tenant detail/control plane, authenticated changelog reads, and superadmin-only changelog writes. Refresh `openapi.json`, `docs/endpoint-inventory.md`, `.factory/library/frontend-handoff.md`, and the ERP-37 review docs in the same packet before claiming contract parity.
- **Dry-run commands:**
  - `cd erp-domain && export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
  - `cd erp-domain && export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 && MIGRATION_SET=v2 mvn -Dtest=CompanyControllerIT,SuperAdminControllerIT,TenantOnboardingControllerTest,ChangelogControllerSecurityIT,AuthPasswordResetPublicContractIT,AdminUserSecurityIT,TenantRuntimeEnforcementServiceTest,TenantRuntimeEnforcementAuthIT,SuperAdminTenantWorkflowIsolationIT,PortalInsightsControllerIT,ReportControllerSecurityIT,AuthTenantAuthorityIT,CompanyContextFilterControlPlaneBindingTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeTenantRuntimeEnforcementTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TenantAdminProvisioningServiceTest test`
  - `bash ci/check-enterprise-policy.sh`
- **Rollback strategy:** treat `V167` as a coordinated app-and-schema cut. Once applied, do not redeploy a pre-ERP-37 backend against that database. If rollout must be abandoned after execution, keep the ERP-37-compatible backend live or restore the tenant/database from a pre-`V167` snapshot/PITR before attempting any broader rollback.

## 2026-03-24 — `erp-domain/src/main/resources/db/migration_v2/V166__opening_stock_batch_key_contract_alignment.sql`

- **Purpose:** align the v2 migration track with the already-merged ERP-36 opening-stock hard cut by rewriting legacy replay-backed rows onto the explicit `opening_stock_batch_key` contract, preserving newer v2 rows that already use batch keys, and dropping the obsolete `replay_protection_key`.
- **Forward plan:** apply `V166__opening_stock_batch_key_contract_alignment.sql` after the existing `V46`/`V47` opening-stock migrations, make sure the migration drops the legacy partial batch-key/replay indexes before rewriting `opening_stock_batch_key = idempotency_key`, verify every `opening_stock_imports` row now has a non-null batch key, confirm any collision cleanup preferred newer `replay_protection_key IS NULL` rows over legacy rows, then keep the ERP-36 hard-cut backend build live because it already serves the non-null batch-key contract.
- **Dry-run commands:**
  - `mvn -f "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup/erp-domain/pom.xml" -s "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup/erp-domain/.mvn/settings.xml" -Djacoco.skip=true -Dtest=com.bigbrightpaints.erp.truthsuite.inventory.TS_OpeningStockBatchKeyV2MigrationContractTest test`
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup" && bash ci/check-enterprise-policy.sh`
- **Rollback strategy:** treat `V166` as forward-only once executed against a tenant database because it rewrites historical batch keys and drops replay-protection data. If rollout must be abandoned after execution, keep a hard-cut-compatible backend deployed and restore the tenant from a pre-`V166` snapshot/PITR before attempting any broader ERP-36 rollback.

## 2026-03-24 — `erp-domain/src/main/resources/db/migration_v2/V165__pause_hr_payroll_module.sql`

- **Purpose:** default `HR_PAYROLL` off for new tenants and remove it from existing tenant `enabled_modules` so ERP-33 can hard-cut payroll/admin/portal/orchestrator HR surfaces behind the canonical tenant module gate.
- **Forward plan:** apply `V165__pause_hr_payroll_module.sql` on both migration tracks, deploy the ERP-33 backend packet that gates `/api/v1/payroll/**`, `/api/v1/accounting/payroll/**`, admin approvals, portal workforce/dashboard HR metrics, orchestrator HR snapshots, and accounting-period payroll diagnostics, then verify the super-admin re-enable path before declaring the cut complete. This migration was renumbered to `V165` during the latest `main` merge because `V164` is now occupied by the ERP-32 credit-request requester-identity packet.
- **Dry-run commands:**
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-33-merge-fix/erp-domain" && DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn clean -Dtest=AccountingPeriodServiceTest,IntegrationCoordinatorTest,ModuleGatingInterceptorTest,ModuleGatingServiceTest,AdminSettingsControllerApprovalsContractTest,AdminSettingsControllerTenantRuntimeContractTest,AdminApprovalRbacIT,HrPayrollModulePauseIT test`
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-33-merge-fix" && bash ci/check-enterprise-policy.sh`
- **Rollback strategy:** if this packet must be reverted before merge, redeploy the previous backend build first, then execute `UPDATE companies SET enabled_modules = CASE WHEN enabled_modules ? 'HR_PAYROLL' THEN enabled_modules ELSE enabled_modules || '\"HR_PAYROLL\"'::jsonb END; ALTER TABLE companies ALTER COLUMN enabled_modules SET DEFAULT '[\"MANUFACTURING\",\"PURCHASING\",\"PORTAL\",\"REPORTS_ADVANCED\",\"HR_PAYROLL\"]'::jsonb;` in the same maintenance window after confirming the reverted build is live.

## 2026-03-23 — `erp-domain/src/main/resources/db/migration_v2/V164__credit_request_requester_identity.sql`

- **Purpose:** persist dealer-portal submitter identity on `credit_requests` so ERP-32 durable credit-limit approvals no longer surface anonymous maker rows when dealer users submit permanent limit requests.
- **Forward plan:** add nullable `credit_requests.requester_user_id` and `credit_requests.requester_email`, deploy the backend packet that writes requester identity from the authenticated dealer portal principal, and keep existing historical rows nullable because legacy sales/admin-created durable requests intentionally remain without portal maker metadata.
- **Dry-run commands:**
  - `cd erp-domain && mvn -B -ntp -Dtest='CreditLimitRequestServiceTest,DealerPortalServiceTest,DealerPortalControllerExportAuditTest,AdminSettingsControllerApprovalsContractTest' test`
  - `cd erp-domain && mvn -B -ntp -Ppr-fast -Dtest='com.bigbrightpaints.erp.modules.sales.service.DealerPortalServiceTest,com.bigbrightpaints.erp.modules.sales.controller.DealerPortalControllerExportAuditTest,com.bigbrightpaints.erp.modules.sales.service.CreditLimitRequestServiceTest,com.bigbrightpaints.erp.modules.admin.controller.AdminSettingsControllerApprovalsContractTest,com.bigbrightpaints.erp.modules.accounting.service.StatementServiceTest,com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestCreateRequestTest,com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDecisionRequestTest,com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDtoTest,com.bigbrightpaints.erp.modules.sales.dto.DealerPortalCreditLimitRequestCreateRequestTest' test`
  - `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; cd erp-domain && mvn -B -ntp -Dtest='AdminApprovalRbacIT,DealerPortalReadOnlySecurityIT' test`
- **Rollback strategy:** if this packet must be reverted before merge, redeploy the previous backend build first so runtime code stops reading or writing credit-request maker identity, then execute `ALTER TABLE public.credit_requests DROP COLUMN IF EXISTS requester_email; ALTER TABLE public.credit_requests DROP COLUMN IF EXISTS requester_user_id;` in the same maintenance window.

## 2026-03-22 — `erp-domain/src/main/resources/db/migration_v2/V46__opening_stock_import_results_json.sql`

- **Purpose:** persist canonical row-level `results[]` on `opening_stock_imports` and add `replay_protection_key` so strict opening-stock imports retain replay truth even under fresh idempotency keys and concurrent duplicate submissions.
- **Forward plan:** add nullable `opening_stock_imports.results_json` and `opening_stock_imports.replay_protection_key`, backfill replay keys from `(company_code,file_hash)` while disambiguating historical duplicates, then create the partial unique `(company_id, replay_protection_key)` index before deploying the strict opening-stock packet that records row results/errors in the import record.
- **Dry-run commands:**
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -DskipTests test-compile`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest='RawMaterialControllerTest,OpeningStockImportServiceTest,ProductionCatalogServiceCanonicalEntryTest,CR_CatalogImportDeterminismIT,ProductionCatalogDiscountDefaultRegressionIT,ProductionCatalogFinishedGoodInvariantIT,ProductionCatalogRawMaterialInvariantIT,AccountingCatalogControllerSecurityIT,CatalogControllerCanonicalProductIT' test`
  - `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; mvn -B -ntp -Dtest='OpenApiSnapshotIT,AccountingCatalogControllerSecurityIT,CompanyControllerIT,TenantOnboardingControllerTest,AuthTenantAuthorityIT#admin_cannot_bootstrap_new_tenant+super_admin_can_bootstrap_new_tenant+super_admin_can_bootstrap_new_tenant_with_first_admin_credentials_provisioning,GlobalExceptionHandlerTest,OpeningStockPostingRegressionIT,OpeningStockImportControllerTest,OpeningStockImportServiceTest,TenantOnboardingServiceTest,ProductionCatalogServiceCanonicalEntryTest,ProductionCatalogServiceBulkVariantRaceTest,SkuReadinessServiceTest' test`
  - `python3 scripts/changed_files_coverage.py --jacoco erp-domain/target/site/jacoco/jacoco.xml --diff-base origin/main`
- **Rollback strategy:** if the packet must be reverted before merge, deploy the previous backend build first, then execute `DROP INDEX IF EXISTS uq_opening_stock_imports_company_replay_key; ALTER TABLE public.opening_stock_imports DROP COLUMN IF EXISTS replay_protection_key; ALTER TABLE public.opening_stock_imports DROP COLUMN IF EXISTS results_json;` in the same maintenance window so reverted code does not inherit persisted row-result payloads or replay keys it does not serve.

## 2026-03-21 — `erp-domain/src/main/resources/db/migration_v2/V163__catalog_variant_group_linkage.sql`

- **Purpose:** add canonical variant-group and product-family storage to `production_products` so consolidated catalog product creation can persist downstream-ready grouping metadata on the single `/api/v1/catalog/**` host.
- **Forward plan:** add nullable `variant_group_id` and `product_family_name` to `production_products`, create the `(company_id, variant_group_id)` lookup index, refresh the OpenAPI snapshot, and only then expose the canonical product/import paths that depend on the new schema.
- **Dry-run commands:**
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Djacoco.skip=true -Dtest=OpenApiSnapshotIT,CatalogControllerCanonicalProductIT,AccountingCatalogControllerSecurityIT test`
- **Rollback strategy:** if the packet must be reverted before merge, redeploy the previous backend build first, then execute `DROP INDEX IF EXISTS idx_production_products_company_variant_group; ALTER TABLE public.production_products DROP COLUMN IF EXISTS variant_group_id; ALTER TABLE public.production_products DROP COLUMN IF EXISTS product_family_name;` in the same maintenance window so reverted code does not inherit partially populated variant-group metadata.

## 2026-03-17 — `erp-domain/src/main/resources/db/migration_v2/V162__password_reset_token_delivery_tracking.sql`

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
