# ERP-48 Parallel Worker Worklog

## Baseline

- Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee`
- Branch: `packet/erp-48-canonical-hardcut-d2df29ee`
- Pinned base and `origin/main`: `d2df29eeb58c6d74b932a7be2d76b90eb310b419`
- Runtime for container-backed verification: Colima

## Lane Topology

| Lane | Worker | Status | Notes |
|---|---|---|---|
| auth / control-plane / portal boundary | `Dalton` | shutdown without final payload | main lane picked up approval-boundary cleanup locally |
| accounting foundations / COA / default accounts | `Carver` | shutdown without final payload | no worker evidence returned |
| accounting journals / corrections | `Raman` | shutdown without final payload | no worker evidence returned |
| accounting period / reconciliation / reports | `Mill` | shutdown without final payload | no worker evidence returned |
| p2p / ap / inventory setup | `Einstein` | shutdown without final payload | no worker evidence returned |
| o2c / factory / manufacturing | `Descartes` | completed | doc drift found and normalized around canonical `POST /api/v1/dispatch/confirm` |
| dead code / legacy / CI | `Popper` | shutdown without final payload | no worker evidence returned |
| frontend handoff docs | `Meitner` | completed with cross-lane findings | returned useful accounting-foundation fixes plus the shared-target-tree blocker; no frontend-doc audit payload was produced |

## Main-Lane Fixes Landed

### 1. Tenant-admin approval boundary hardened

The controller layer no longer advertises superadmin access to tenant-admin approval workflows:

- `[AdminSettingsController.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsController.java)`
  - `GET /api/v1/admin/approvals` now requires `ROLE_ADMIN|ROLE_ACCOUNTING`
  - `PUT /api/v1/admin/exports/{requestId}/approve|reject` now require `ROLE_ADMIN`
- `[PortalRoleActionMatrix.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/PortalRoleActionMatrix.java)`
  - removed the now-dead `ADMIN_ACCOUNTING_SUPER_ADMIN` constant
- `[AdminSettingsControllerTenantRuntimeContractTest.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsControllerTenantRuntimeContractTest.java)`
  - added reflection assertions for the tightened approval/export annotations
- `[SuperAdminTenantWorkflowIsolationIT.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/SuperAdminTenantWorkflowIsolationIT.java)`
  - added tenant-attached superadmin denial coverage for `GET /api/v1/admin/approvals`
- `[docs/accounting-portal-endpoint-map.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/accounting-portal-endpoint-map.md)`
- `[AccountingPortalScopeGuardScriptTest.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/regression/AccountingPortalScopeGuardScriptTest.java)`
  - removed stale text claiming the backend also allowed `ROLE_SUPER_ADMIN`

### 2. Accounting foundation and GST health hardened

One late worker payload surfaced real accounting-foundation drift that is now present in this worktree:

- `[ConfigurationHealthService.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/main/java/com/bigbrightpaints/erp/core/health/ConfigurationHealthService.java)`
  - GST mode now requires `gstInputTaxAccountId`, `gstOutputTaxAccountId`, and `gstPayableAccountId`
  - non-GST mode now fails closed when GST tax accounts remain configured
- `[CriticalFixtureService.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/main/java/com/bigbrightpaints/erp/core/service/CriticalFixtureService.java)`
  - seeded companies now receive canonical default inventory, COGS, revenue, discount, tax, GST-input, GST-output, and GST-payable account pointers
- `[GstConfigurationRegressionIT.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/regression/GstConfigurationRegressionIT.java)`
  - covers missing `GST_PAYABLE` in GST mode and invalid retained GST accounts in non-GST mode

### 3. Validation-time test hygiene fixes

While pushing the narrowed proof run, two stale test issues surfaced and were corrected in the main worktree:

- `[TenantOnboardingServiceTest.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/service/TenantOnboardingServiceTest.java)`
  - changed ambiguous `assertThat(ReflectionTestUtils.invokeMethod(...))` calls into explicit boolean assertions so test compilation is stable again
- `[ReportExportApprovalIT.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/modules/reports/ReportExportApprovalIT.java)`
  - normalized approval/download payload assertions to `Map<String, Object>` so AssertJ map assertions compile cleanly

### 4. O2C / factory internal contract docs aligned

The completed O2C worker found no runtime defect, but it did find internal doc drift around dispatch ownership. The following docs were normalized around one public dispatch write, `POST /api/v1/dispatch/confirm`, with downstream sales/accounting consequences staying behind that write:

- `[13-catalog-sku-and-product-flows.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/developer/accounting-flows/13-catalog-sku-and-product-flows.md)`
- `[02-target-accounting-product-entry-flow.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/developer/catalog-consolidation/02-target-accounting-product-entry-flow.md)`
- `[03-definition-of-done-and-parallel-scope.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/developer/catalog-consolidation/03-definition-of-done-and-parallel-scope.md)`
- `[04-update-hygiene.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/developer/catalog-consolidation/04-update-hygiene.md)`
- `[03-target-simplified-user-flow.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/developer/onboarding-stock-readiness/03-target-simplified-user-flow.md)`
- `[05-definition-of-done-and-update-hygiene.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/developer/onboarding-stock-readiness/05-definition-of-done-and-update-hygiene.md)`

## Verification Performed

### Main lane

- `git diff --check`
  - passed after the current main-lane edits
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_fast.sh`
  - guard layer passed
  - heavy truthsuite phase was later killed by the host with exit `137`
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -B -ntp -DforkCount=0 -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=AdminSettingsControllerTenantRuntimeContractTest,SuperAdminTenantWorkflowIsolationIT,AdminApprovalRbacIT,AccountingPortalScopeGuardScriptTest,ReportExportApprovalIT,TenantOnboardingServiceTest test`
  - compile blockers were cleared
  - `AccountingPortalScopeGuardScriptTest` passed
  - later failures split into harness issues and test-owned instability; see blocker table below
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -B -ntp -DforkCount=0 -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=GstConfigurationRegressionIT test`
  - passed
  - `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`
  - `BUILD SUCCESS`
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -B -ntp -DforkCount=0 -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=AdminSettingsControllerTenantRuntimeContractTest,AccountingPortalScopeGuardScriptTest,SuperAdminTenantWorkflowIsolationIT test`
  - passed
  - `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`
  - `BUILD SUCCESS`

### Completed worker lane

- `Descartes` ran:
  - `git diff --check -- docs/developer/accounting-flows/13-catalog-sku-and-product-flows.md docs/developer/catalog-consolidation/03-definition-of-done-and-parallel-scope.md docs/developer/catalog-consolidation/02-target-accounting-product-entry-flow.md docs/developer/catalog-consolidation/04-update-hygiene.md docs/developer/onboarding-stock-readiness/03-target-simplified-user-flow.md docs/developer/onboarding-stock-readiness/05-definition-of-done-and-update-hygiene.md`
    - passed
  - `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -B -ntp -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=TS_O2CDispatchCanonicalPostingTest,TS_O2CDispatchProvenanceAndRetiredRouteBoundaryTest,ProductionLogPackingStatusRegressionIT test`
    - passed
    - `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`

## Blockers Found During This Workflow

| Class | Blocker | Shape |
|---|---|---|
| full fast gate | `gate_fast` reached truthsuite execution and was later killed with exit `137` | host resource / environment |
| forked narrowed Maven run | Surefire fork bootstrap could not access its generated booter jar | test harness / environment |
| parallel worker execution model | overlapping Maven runs against the same `erp-domain/target` tree produced false negatives such as missing booter jars and missing compiled outputs | workflow / isolation defect |
| in-process narrowed onboarding run | Mockito inline could not build certain service mocks such as `AccountingPeriodService` | test harness mode; not a production defect |
| in-process mixed Spring run | `ReportExportApprovalIT` surfaced an application-context startup failure around `jwtAuthenticationFilter`, but it was not cleanly isolated into its own report during the blended run | unresolved; isolate before claiming lane green |

## Current State

- The tenant-admin approval boundary is now aligned across controller, runtime contract, and portal docs.
- The O2C/factory doc set is aligned around the canonical dispatch write path.
- Current narrowed validation uncovered and removed stale test-compilation defects.
- The remaining blockers are not yet a clean “all lanes green” result; they need isolated reruns or harness cleanup before final gate claims.

## Serial-Only Recovery And Additional Fixes

After the worker swarm reintroduced shared-`target/` corruption, the packet was
switched back to a single Maven writer. From that point onward, all proof runs
were serial-only from the main lane.

### 5. Product/account default gating corrected for catalog creation

The first real product failure from the resumed serial proof was in the
finished-good account mapping contract: catalog creation was failing closed, but
it surfaced a generic default-account error too early and hid explicit
cross-company account mistakes.

- `[CompanyDefaultAccountsService.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/CompanyDefaultAccountsService.java)`
  - `requireDefaults()` now fails with field-specific messages:
    `fgValuationAccountId`, `fgCogsAccountId`, `fgRevenueAccountId`,
    `fgDiscountAccountId`, and `fgTaxAccountId`
- `[ProductionCatalogService.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogService.java)`
  - finished-good account metadata is now company-scope validated before
    company defaults are consulted, so explicit foreign account ids fail with
    the correct invalid-account error instead of being masked by unrelated
    missing defaults
- `[CompanyDefaultAccountsServiceTest.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/CompanyDefaultAccountsServiceTest.java)`
  - assertions now pin the new field-specific fail-closed messages
- `[ProductionCatalogDiscountDefaultRegressionIT.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/regression/ProductionCatalogDiscountDefaultRegressionIT.java)`
  - now passes against the corrected contract without weakening the expectation
- `[TS_RuntimeCompanyDefaultAccountsExecutableCoverageTest.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeCompanyDefaultAccountsExecutableCoverageTest.java)`
- `[TS_RuntimeModuleExecutableCoverageTest.java](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeModuleExecutableCoverageTest.java)`
  - truthsuite expectations now pin the new specific fail-closed messages

### 6. Frontend contract docs normalized as the only authority

One completed worker returned a useful static-only payload after the stop
instruction. That output was integrated into the canonical frontend handoff:

- `[docs/frontend-update-v2/README.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/frontend-update-v2/README.md)`
  - now explicitly demotes `frontend-update-v2` to historical delta context
- `[docs/frontend-portals/README.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/frontend-portals/README.md)`
  - now states that `docs/frontend-portals/` plus `docs/frontend-api/` are the
    only authoritative frontend contract
- `[docs/frontend-api/accounting-reference-chains.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/frontend-api/accounting-reference-chains.md)`
- `[docs/frontend-portals/accounting/workflows.md](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/docs/frontend-portals/accounting/workflows.md)`
  - now spell out the onboarding -> COA bootstrap -> default accounts -> SKU
    readiness chain more explicitly for frontend consumers

## Static-Only Closeout After User Stop

- The user later instructed: stop all Maven or test commands immediately and do
  not run builds in this shared worktree.
- All worker agents for this round were closed after that instruction.
- A follow-up process sweep found no live `mvn`, Surefire, or worktree-tied JVM
  processes for this packet lane.
- Post-stop work stayed doc-only:
  - `docs/frontend-update-v2/README.md` now explicitly defers to the canonical
    portal/API pack.
  - `docs/frontend-portals/README.md` now states that `frontend-update-v2`
    notes are history/delta references only.
  - `docs/frontend-api/accounting-reference-chains.md` now names the exact
    onboarding/default-account/product-readiness chain more explicitly.
  - `docs/frontend-portals/accounting/workflows.md` now names the exact
    onboarding/default-account endpoints and fail-closed readiness boundary.

## Verification Added After Serial-Only Recovery

- `mvn -B -ntp -DskipTests test-compile`
  - passed after the shared-`target/` interference was removed
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -B -ntp -DforkCount=0 -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=TenantOnboardingServiceTest,CompanyDefaultAccountsServiceTest,GstConfigurationRegressionIT,AdminSettingsControllerTenantRuntimeContractTest,AdminSettingsControllerApprovalsContractTest,AdminApprovalRbacIT,RawMaterialControllerSecurityIT,ReportExportApprovalIT test`
  - passed
  - `Tests run: 70, Failures: 0, Errors: 0, Skipped: 0`
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -B -ntp -DforkCount=0 -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=CompanyDefaultAccountsServiceTest,ProductionCatalogDiscountDefaultRegressionIT test`
  - passed
  - `Tests run: 21, Failures: 0, Errors: 0, Skipped: 0`
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -B -ntp -DforkCount=0 -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=TS_RuntimeCompanyDefaultAccountsExecutableCoverageTest,TS_RuntimeModuleExecutableCoverageTest test`
  - passed
  - `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 mvn -B -ntp -DforkCount=0 -Dspring.profiles.active=test,flyway-v2 -Dspring.flyway.locations=classpath:db/migration_v2 -Dspring.flyway.table=flyway_schema_history_v2 -Dtest=AuthControllerIT,AccountingControllerJournalEndpointsTest,JournalEntryE2ETest,PeriodCloseLockIT,OpenApiSnapshotIT,ProductionCatalogDiscountDefaultRegressionIT,TS_RuntimeCompanyDefaultAccountsExecutableCoverageTest,CompanyServiceTest,InventoryAdjustmentControllerTest,RawMaterialControllerTest test`
  - passed
  - `Tests run: 155, Failures: 0, Errors: 0, Skipped: 0`
- `git diff --check`
  - passed after the additional serial-only fixes and worklog update
- `rg -n "rag-mcp-sidecar|rag_mcp_server\\.sh|scripts/rag/mcp_server\\.py|Task00_async_verify|scripts/task00_async_verify\\.sh" .`
  - no remaining live references found

## Release-Gate Blockers Fixed In This Run

### 1. Release harness pathing defect

- `[scripts/verify_local.sh](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/scripts/verify_local.sh)`
- `[scripts/gate_release.sh](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/scripts/gate_release.sh)`
- `[scripts/gate_fast.sh](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/scripts/gate_fast.sh)`
- `[scripts/gate_core.sh](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/scripts/gate_core.sh)`
- `[scripts/gate_reconciliation.sh](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/scripts/gate_reconciliation.sh)`
  - the gate entrypoints now treat `scripts/maven_memory_defaults.sh` as optional and fall back to the same bounded Maven heap/metaspace defaults when the helper is absent
  - this fixed the hermetic `guard_flyway_guard_contract.sh` temp-copy harness, which had been failing before the intended fail-closed DB-mismatch assertions ran

### 2. Migration ownership and lifecycle hard-cut cleanup

- `[V173__company_lifecycle_constraint_hard_cut.sql](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/erp-domain/src/main/resources/db/migration_v2/V173__company_lifecycle_constraint_hard_cut.sql)`
  - the new migration is now tracked in git and no longer appears as an orphan `migration_v2` file
  - the lifecycle hard-cut now drops both legacy lifecycle constraint names and installs one canonical `chk_companies_lifecycle_state_v173` constraint instead of silently stacking a second equivalent constraint on top of `v167`
  - this removed the `flyway_overlap_scan` duplicate-constraint finding while preserving the hard-cut state vocabulary rewrite

### 3. Release migration matrix SQL templating defect

- `[scripts/release_migration_matrix.sh](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/scripts/release_migration_matrix.sh)`
  - the scan-file rewrite now replaces the whole `flyway_schema_history(_v2)?` token instead of blindly appending `_v2`
  - this fixed the fresh/upgrade matrix failure where the generated scan SQL queried the impossible table name `flyway_schema_history_v2_v2`

### 4. Repo formatting gate cleared

- `mvn -B -ntp spotless:apply`
  - applied after `gate_release` exposed 53 formatting-only violations in `erp-domain`
  - no behavior change was introduced here; this was purely to satisfy the release formatting gate after the packet’s accumulated edits

## Final Gate Proof

- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_fast.sh`
  - passed
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_core.sh`
  - passed
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_reconciliation.sh`
  - passed
  - `Tests run: 267, Failures: 0, Errors: 0, Skipped: 0`
- `env DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 GATE_CANONICAL_BASE_REF=origin/main bash scripts/gate_release.sh`
  - passed
  - release manifest written under `[artifacts/gate-release](/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-48-canonical-hardcut-d2df29ee/artifacts/gate-release)`
  - canonical base verified against `origin/main` at `d2df29eeb58c6d74b932a7be2d76b90eb310b419`

## Current Reality

- The gate chain is now green on the pinned head in this isolated worktree.
- The worktree is still intentionally dirty because this packet has not yet been curated into a single commit set or pushed.
- The parallel-worker record is accurate for this run: most useful execution ended up being main-lane serial repair after the early shared-`target/` Maven interference, while the best worker contribution was the frontend-contract authority normalization and the early cross-lane accounting-foundation findings.
