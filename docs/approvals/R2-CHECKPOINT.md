# R2 Checkpoint

## Scope
- Feature: `ERP-34 hard-cut setup flow readiness`
- PR: `#131`
- PR branch: `feature/erp-34-hard-cut-setup-flow`
- Review candidate: current remediation packet on PR `#131`
- Why this is R2: this packet changes canonical tenant bootstrap and company-runtime ownership under `modules/company`, rewires catalog browse readiness exposure, hard-cuts opening stock away from hidden bootstrap/repair behavior, and ships `migration_v2/V46__opening_stock_import_results_json.sql` to persist row-level opening-stock results in the canonical import record.

## Risk Trigger
- Triggered by high-risk runtime changes under `modules/company` plus `migration_v2` schema changes and accounting-linked readiness behavior in the canonical setup flow.
- Contract surfaces affected: `POST /api/v1/superadmin/tenants/onboard`, `GET|PUT /api/v1/companies/{id}/tenant-runtime/policy`, `POST|GET /api/v1/catalog/products`, `POST|GET /api/v1/inventory/opening-stock`, and readiness returned from catalog browse/read for non-accounting roles.
- Failure mode if wrong: bootstrap route ownership drifts again, non-accounting catalog readers see accounting-only blocker detail, opening stock silently repairs missing mirrors/accounts, or the import history loses row-level results and replay truth.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Canary owner: `Anas ibn Anwar`
- Approval status: `pending green CI and explicit merge approval`
- Basis: the focused ERP-34 proof, changed-files coverage, OpenAPI/accounting guards, and `git diff --check` are green locally after closing the live review findings on PR `#131`.

## Escalation Decision
- Human escalation required: yes
- Reason: this packet changes tenant-runtime authority, opening-stock accounting-linked behavior, and migration-backed import history, so merge remains gated on explicit human approval after CI settles on PR `#131`.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: revert the ERP-34 remediation commit(s), redeploy the previous backend build, drop `opening_stock_imports.results_json` after the reverted build is live, then rerun the focused ERP-34 proof and coverage gate before re-exposing the pre-remediation head.
- Rollback trigger:
  - any operator-facing path other than `/api/v1/superadmin/tenants/onboard` becomes current-state bootstrap again
  - catalog browse/read leaks detailed accounting blockers to non-accounting roles
  - opening stock recreates or repairs missing raw-material / finished-good mirrors
  - opening-stock history loses row-level `results[]` persistence or replay correctness

## Expiry
- Valid until: `2026-03-29`
- Re-evaluate if: any additional high-risk company/auth/accounting/schema change lands above this ERP-34 packet, CI reruns against a different candidate than PR `#131`, or scope grows beyond tenant bootstrap, stock-bearing product readiness, and strict opening-stock behavior.

## Verification Evidence
- ERP-34 proof suite:
  - `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; mvn -B -ntp -Dtest='OpenApiSnapshotIT,AccountingCatalogControllerSecurityIT,CompanyControllerIT,TenantOnboardingControllerTest,AuthTenantAuthorityIT#admin_cannot_bootstrap_new_tenant+super_admin_can_bootstrap_new_tenant+super_admin_can_bootstrap_new_tenant_with_first_admin_credentials_provisioning,GlobalExceptionHandlerTest,OpeningStockPostingRegressionIT,OpeningStockImportControllerTest,OpeningStockImportServiceTest,TenantOnboardingServiceTest,CatalogServiceCanonicalCoverageTest,CatalogServiceProductCrudTest,ProductionCatalogServiceCanonicalEntryTest,ProductionCatalogServiceBulkVariantRaceTest,SkuReadinessServiceTest' test`
  - result: `BUILD SUCCESS`
  - tests: `153 run, 0 failures, 0 errors, 0 skipped`
- Focused review-fix proof:
  - `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock; mvn -B -ntp -Dtest='SkuReadinessServiceTest,OpeningStockImportServiceTest' test`
  - result: `BUILD SUCCESS`
  - tests: `38 run, 0 failures, 0 errors, 0 skipped`
- Changed-files coverage:
  - `python3 scripts/changed_files_coverage.py --jacoco erp-domain/target/site/jacoco/jacoco.xml --diff-base origin/main`
  - result: `passes=true`
  - result summary: `212/214 changed lines covered (99.07%), 79/86 changed branches covered (91.86%); unmapped changed lines remain non-blocking`
- Contract/hygiene guards:
  - `bash scripts/guard_openapi_contract_drift.sh`
  - result: `OK`
  - `bash scripts/guard_accounting_portal_scope_contract.sh`
  - result: `OK`
  - `git diff --check`
  - result: clean
