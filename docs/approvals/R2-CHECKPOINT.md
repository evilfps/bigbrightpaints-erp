# R2 Checkpoint (Active Approval Record)

Last reviewed: 2026-02-26
Owner: Security & Governance Agent
Status: template-initialized

Update this file in every high-risk change set.

## Change Record (2026-02-19, TKT-ERP-STAGE-088)
- Branch / PR: PR 18 (admin-only accounting export governance + deterministic audit evidence)
- Paths touched:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingControllerExportGovernanceContractTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/reports/TS_AccountingExportAuditGovernanceContractTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/test/AbstractIntegrationTest.java`
- Risk rationale: high-risk accounting controller behavior and export-audit evidence contract changed; strict-lane verification required.
- Approval mode: orchestrator
- Human escalation required: no
- Verification commands:
  - `bash ci/check-architecture.sh` -> pass
  - `bash ci/check-enterprise-policy.sh` -> pass
  - `cd erp-domain && mvn -B -ntp -Dtest='AccountingControllerExportGovernanceContractTest,TS_AccountingExportAuditGovernanceContractTest' test` -> pass
  - `bash scripts/verify_local.sh` -> pass

## Scope
- Branch / PR: `async-loop-predeploy-audit` (M16-S3 retry durability hardening)
- Paths touched: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/audittrail/*`, `erp-domain/src/main/resources/db/migration_v2/V14__audit_action_event_retry_queue.sql`, `docs/approvals/R2-CHECKPOINT.md`
- Business capability affected: enterprise audit retry durability + Flyway v2 migration governance evidence

## Risk Trigger
- Trigger(s): persistent retry storage semantics and scheduler transaction boundaries for accounting audit trails; new Flyway v2 migration artifact
- Why this is R2: change modifies high-risk accounting-adjacent durability behavior and introduces new migration requiring proof-backed validation

## Approval Authority
- Mode: orchestrator
- Approver identity: orchestrator (proof-first autonomous mode)
- Timestamp (UTC): 2026-02-15T00:00:00Z

## Escalation Decision
- Human escalation required: no
- Reason: no irreversible production action executed in this change set

## Rollback Owner
- Name: Release & Ops owner (designated by orchestrator)
- Role: release governance
- Rollback decision SLA: immediate for policy, retry-durability, or migration regressions

## Expiry
- Approval valid until (UTC): 2026-02-22T00:00:00Z
- Re-approval condition: any additional high-risk semantic delta or production action

## Verification Evidence
- Commands run: `cd erp-domain && mvn -B -ntp -Dtest=EnterpriseAuditTrailServiceTest test`; `FAIL_ON_FINDINGS=true bash scripts/schema_drift_scan.sh --migration-set v2`; `FAIL_ON_FINDINGS=true bash scripts/flyway_overlap_scan.sh --migration-set v2`
- Result summary: targeted retry durability suite passed (`5` tests, `0` failures), v2 drift/overlap guards passed (`findings=0`)
- Artifacts/links: `erp-domain/src/test/java/com/bigbrightpaints/erp/core/audittrail/EnterpriseAuditTrailServiceTest.java`; `erp-domain/src/main/resources/db/migration_v2/V14__audit_action_event_retry_queue.sql`; `scripts/schema_drift_scan.sh`; `scripts/flyway_overlap_scan.sh`

## Test Waiver (Only if no tests changed)
- Waiver not used: runtime behavior changed and targeted tests were executed.
- Compensating controls: N/A (covered by executed test + Flyway v2 guard evidence above).

## Migration Addendum (2026-02-15, V14)
- Migration artifact: `erp-domain/src/main/resources/db/migration_v2/V14__audit_action_event_retry_queue.sql`
- Risk class: R2 (new persistent retry queue for enterprise audit durability)
- Validation evidence:
  - `cd erp-domain && mvn -B -ntp -Dtest=EnterpriseAuditTrailServiceTest test` -> pass (`5` tests, `0` failures, `0` errors)
  - `FAIL_ON_FINDINGS=true bash scripts/schema_drift_scan.sh --migration-set v2` -> pass (`findings=0`)
  - `FAIL_ON_FINDINGS=true bash scripts/flyway_overlap_scan.sh --migration-set v2` -> pass (`findings=0`)
- Rollback/forward-fix strategy:
  - If pre-release failure occurs before rollout, drop pending V14 from deploy set and ship with in-memory retry fallback.
  - If V14 is applied and rollback is needed, use forward-fix only (do not edit applied migration); disable persisted path via service fallback behavior while issuing compensating migration in next version.

## Migration Addendum (2026-02-15, V15)
- Migration artifact: `erp-domain/src/main/resources/db/migration_v2/V15__accounting_audit_read_model_hotspot_indexes.sql`
- Risk class: R2 (accounting audit read-model performance indexes on hot tables)
- Deployment safety control:
  - index rollout is sliced into sequential migrations (`V15`..`V18`) with one index per migration to reduce blast radius on partial failure.
  - execute during controlled low-write window because index creation is transactional (no concurrent DDL).
- Validation evidence:
  - `cd erp-domain && mvn -B -ntp -Dtest=AccountingAuditTrailServiceTest,AccountingControllerActivityContractTest test` -> pass (`6` tests, `0` failures, `0` errors)
  - `FAIL_ON_FINDINGS=true bash scripts/schema_drift_scan.sh --migration-set v2` -> pass (`findings=0`)
  - `FAIL_ON_FINDINGS=true bash scripts/flyway_overlap_scan.sh --migration-set v2` -> pass (`findings=0`)
  - `bash scripts/guard_flyway_guard_contract.sh` -> pass
- Rollback/forward-fix strategy:
  - if index-plan regression is detected post-apply, use forward-fix migration to adjust planner-facing index set; do not rewrite applied migration files.
  - if emergency rollback is required, drop newly added indexes with `DROP INDEX CONCURRENTLY` in a compensating migration under maintenance governance.
  - if an environment reports checksum mismatch for `V15` due pre-convergence local variants, run the v2-scoped repair workflow (`migration_v2` + `flyway_schema_history_v2`) from `docs/db/FLYWAY_V2_TRANSIENT_CHECKSUM_REPAIR.md` under approved migration change control before continue.

## STAGE-089 Addendum (2026-02-19, SLICE-01 accounting-domain)
- Branch / PR: stage-089 accounting-domain / PR #22 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/22)
- High-risk paths: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
- Why this is R2: accounting payroll-payment posting contract and run-token resolution are fail-closed financial controls and require checkpointed proof.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + accounting owner
- Verification evidence:
  - Commands run: `cd erp-domain && mvn -B -ntp -Dtest='*Accounting*' test`
  - Result summary: `BUILD SUCCESS` (`Tests run: 255, Failures: 0, Errors: 0, Skipped: 2`)
  - Artifacts/links: `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacadeTest.java`

## STAGE-089 Addendum (2026-02-19, SLICE-02 hr-domain)
- Branch / PR: stage-089 hr-domain / PR #23 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/23)
- High-risk paths: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java`
- Why this is R2: payroll mark-paid boundary controls accounting reference integrity and salary-payable clearing safety.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + payroll owner
- Verification evidence:
  - Commands run: `cd erp-domain && mvn -B -ntp -Dtest='*Payroll*' test`; `bash scripts/verify_local.sh`
  - Result summary: `BUILD SUCCESS` (`*Payroll*` suite passed; `verify_local` passed with `Tests run: 1264, Failures: 0, Errors: 0, Skipped: 4`)
  - Artifacts/links: `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/payroll/TS_PayrollLiabilityClearingPolicyTest.java`

## STAGE-091 Addendum (2026-02-19, SLICE-01 accounting-domain)
- Branch / PR: stage-091 accounting-domain branch / PR #27 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/27)
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingExportGovernanceIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeAccountingControllerExportCoverageTest.java`
- Why this is R2: accounting export endpoints enforce admin-only policy and audit metadata semantics for financial data egress.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + accounting owner
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -B -ntp -Dtest='AccountingExportGovernanceIT' test`
    - `cd erp-domain && mvn -B -ntp -Dtest='*Accounting*' test`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
    - `bash scripts/verify_local.sh`
    - `DIFF_BASE=50f271db3f1a37df7874ffdea6271677533cecbc bash scripts/gate_fast.sh`
  - Result summary: all commands above passed on branch head; changed-file coverage for `AccountingController.java` met gate-fast thresholds (line 18 of 18, branch 4 of 4).
  - Artifacts/links:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingExportGovernanceIT.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeAccountingControllerExportCoverageTest.java`
    - `docs/CODE-RED/confidence-suite/TEST_CATALOG.json`

## STAGE-095 Addendum (2026-02-20, SLICE-01 accounting-domain)
- Branch / PR: stage-095 accounting-domain branch / PR #33 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/33)
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
- Why this is R2: idempotency header parity on accounting money-movement endpoints is a fail-closed financial control boundary.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + accounting owner
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -B -ntp -Dtest=AccountingControllerIdempotencyHeaderParityTest test`
    - `cd erp-domain && mvn -B -ntp -Dtest='*AccountingController*' test`
    - `cd erp-domain && mvn -B -ntp -Dtest='*Accounting*' test`
    - `bash ci/check-architecture.sh`
    - `bash scripts/verify_local.sh`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary: targeted deterministic suites passed; `*Accounting*` failures were limited to missing local Docker/Testcontainers runtime (6 integration errors); `verify_local` failed on macOS bash portability (`mapfile` not found in `scripts/guard_flyway_v2_migration_ownership.sh`); architecture and enterprise policy checks passed after this checkpoint update.
  - Artifacts/links:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingControllerIdempotencyHeaderParityTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingControllerExceptionHandlerTest.java`

## STAGE-102 Addendum (2026-02-21, release-ops)
- Branch / PR: ticket branch `tkt-erp-stage-102` (release-ops lane) / PR #35 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/35)
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/CompanyController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/CompanyService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
- Why this is R2: super-admin tenant update authorization and accounting purchase idempotency dedupe are fail-closed control boundaries and require proof-backed governance evidence.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + company/accounting owners
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -B -ntp -Dtest=CompanyServiceTest,AccountingFacadeTest test`
    - `bash ci/check-enterprise-policy.sh`
    - `cd erp-domain && mvn -B -ntp -Dtest=CompanyControllerIT test`
  - Result summary: targeted service/unit suites passed (`Tests run: 25, Failures: 0, Errors: 0`); enterprise policy guard passed after this checkpoint update; integration test remained blocked on local environment runtime (`Could not find a valid Docker environment`, client API `1.32` vs daemon minimum `1.44`) with JaCoCo + JDK `25` instrumentation incompatibility (`Unsupported class file major version 69`).
  - Artifacts/links:
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/service/CompanyServiceTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacadeTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/CompanyControllerIT.java`

## STAGE-103 Addendum (2026-02-22, orchestrator-runtime-hotfix)
- Branch / PR: `tickets-tkt-erp-stage-103-orchestrator-runtime-hotfix` / PR #44 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/44)
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrchestratorIdempotencyService.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeOrchestratorExecutableCoverageTest.java`
- Why this is R2: orchestrator idempotency hash/fallback and canonical runtime decision wiring are high-risk reliability controls on order workflow execution.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + orchestrator owner
- Verification evidence:
  - Commands run:
    - `mvn -B -ntp -f erp-domain/pom.xml -s /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/chore/codex-cloud-testing/.mvn/settings.xml -Dtest='TS_RuntimeOrchestratorExecutableCoverageTest,TS_RuntimeOrchestratorIdempotencyExecutableCoverageTest' test`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary: targeted orchestrator truthsuite lane passed (`Tests run: 20, Failures: 0, Errors: 0`) and architecture/policy checks passed after this checkpoint update.
- Artifacts/links:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrchestratorIdempotencyService.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeOrchestratorExecutableCoverageTest.java`

## STAGE-101 Addendum (2026-02-23, hr-domain)
- Branch / PR: hr-domain branch for TKT-ERP-STAGE-101 / PR #49 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/49)
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java`
- Why this is R2: payroll posting metadata completeness and period-boundary controls are fail-closed controls that protect accounting liability-clearing integrity.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + payroll/accounting owners
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -B -ntp -Dtest='*Payroll*' test`
    - `bash scripts/verify_local.sh`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary: targeted payroll suite passed and `verify_local` passed in the branch validation lane; enterprise-policy check updated to include this checkpoint evidence for high-risk payroll module changes.
  - Artifacts/links:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/payroll/TS_PayrollLiabilityClearingPolicyTest.java`

## STAGE-015 Addendum (2026-02-23, harness-fixes)
- Branch / PR: `tickets-tkt-erp-stage-015-harness-fixes` / PR #45 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/45)
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/CompanyController.java`
- Why this is R2: tenant-runtime admission bypass rules, lifecycle-recovery authorization, and accounting idempotency replay checks are fail-closed control paths.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + security/company/accounting owners
- Verification evidence:
  - Commands run:
    - `mvn -B -ntp -f erp-domain/pom.xml -Dtest=TS_RuntimeTenantRuntimeEnforcementTest,TenantRuntimeEnforcementServiceTest,TenantRuntimeEnforcementInterceptorTest,AccountingFacadeTest test`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary: targeted runtime/accounting test lane passed (`Tests run: 64, Failures: 0, Errors: 0, Skipped: 0`) and enterprise policy guard passed after this checkpoint update.
  - Artifacts/links:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeTenantRuntimeEnforcementTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementServiceTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacadeTest.java`

## STAGE-102 Addendum (2026-02-23, auth-rbac-company-repair-v2)
- Ticket path / PR: `tickets/TKT-ERP-STAGE-102` / PR #65 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/65)
- Source branch: `tickets-tkt-erp-stage-102-auth-rbac-company-repair-v2`
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/CompanyController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/CompanyService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementService.java`
- Why this is R2: super-admin runtime policy control, tenant lifecycle bypass semantics, and fail-closed runtime admission are high-risk auth/runtime boundaries.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + company/auth owners
- Verification evidence:
  - Commands run:
    - `mvn -B -ntp -f erp-domain/pom.xml -s /Users/anas/Documents/orchestrator_erp/bigbrightpaints-erp_worktrees/chore/codex-cloud-testing/.mvn/settings.xml -Dtest=TS_RuntimeTenantRuntimeEnforcementTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest test`
    - `DIFF_BASE=584989c317a3b50361d49b212ff19b6eeddb7d50 bash scripts/gate_fast.sh`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary: targeted truthsuite lane passed (`Tests run: 18, Failures: 0, Errors: 0`); gate-fast passed with changed-files coverage above thresholds (`line_ratio=0.995`, `branch_ratio=0.9301`).
- Artifacts/links:
  - `docs/CODE-RED/confidence-suite/TEST_CATALOG.json`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeTenantRuntimeEnforcementTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeTenantPolicyControlExecutableCoverageTest.java`

## STAGE-106 Addendum (2026-02-23, orchestrator-runtime)
- Ticket / PR: `TKT-ERP-STAGE-106` / PR #75 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/75)
- Source branch: `tkt-erp-stage-106-orchestrator-runtime`
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingServiceTest.java`
- Why this is R2: idempotent accounting journal posting conflict handling is a fail-closed financial control path that impacts payroll/manual/reversal posting flows.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + accounting owner
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -B -ntp -Dtest='AccountingServiceTest#createJournalEntry_dataIntegrityConflictReturnsExistingOnSaveRace,AccountingServiceTest#createJournalEntry_dataIntegrityConflictRethrowsForRetryBoundary' test`
    - `bash scripts/gate_fast.sh`
    - `bash scripts/gate_core.sh`
    - `bash scripts/gate_reconciliation.sh`
    - `bash scripts/gate_release.sh`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary: conflict-save duplicate recovery restored for self-invoked `createJournalEntry` callers while preserving retry-boundary rethrow when no duplicate row exists; gate ladder remained green on current branch evidence.
- Artifacts/links:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingServiceTest.java`

## STAGE-102 Addendum (2026-02-23, release-ops-land-v1)
- Branch / PR: branch `release-ops-land-v1` (ticket-102 release lane) / PR #72 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/72)
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/CompanyService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/service/TenantRuntimePolicyService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/service/TenantRuntimeEnforcementConfig.java`
- Why this is R2: tenant-runtime policy-control authority, control-plane state mutations, and fail-closed runtime admission boundaries are high-risk auth/runtime controls.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + company/admin/security owners
- Verification evidence:
  - Commands run:
    - `bash ci/check-architecture.sh`
    - `cd erp-domain && mvn -B -ntp -Dtest='TenantRuntimeEnforcementServiceTest,CompanyServiceTest,TenantRuntimePolicyServiceTest,TenantRuntimeEnforcementConfigTest' test`
    - `cd erp-domain && mvn -B -ntp -Dtest='CompanyControllerIT,AdminUserSecurityIT,PortalInsightsControllerIT,TS_RuntimeTenantRuntimeEnforcementTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_RuntimeTenantControlPlaneEnforcementTest' test`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary: all listed validation lanes passed on branch head; runtime policy control and cross-module runtime truthsuite coverage remained green after landing fixes.
- Artifacts/links:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/CompanyControllerIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementServiceTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/portal/PortalInsightsControllerIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeTenantPolicyControlExecutableCoverageTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeTenantControlPlaneEnforcementTest.java`

## STAGE-108 Addendum (2026-02-24, refactor-techdebt-gc)
- Ticket path / commit: `tickets/TKT-ERP-STAGE-108` / `06c931e0`
- Source branch: tickets/tkt-erp-stage-108/refactor-techdebt-gc
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingCatalogController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/dto/BulkVariantResponse.java`
- Why this is R2: accounting catalog bulk-variant mutation flow now enforces fail-closed duplicate handling and dry-run preview behavior; this is a runtime write-path contract change that impacts deterministic SKU creation and conflict semantics.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + accounting/catalog owners
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -B -ntp -Dtest=ProductionCatalogServiceBulkVariantRaceTest,ProductionCatalogServiceRetryPolicyTest test`
    - `bash ci/lint-knowledgebase.sh`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary:
    - production catalog service unit/race suites passed (`Tests run: 17, Failures: 0, Errors: 0`).
    - knowledgebase + architecture guards passed.
    - enterprise-policy guard passed after this checkpoint update.
    - integration lane `RawMaterialAndProductUpdateIT` is environment-blocked locally when Docker/Testcontainers is unavailable; runtime code/tests are prepared for CI/docker validation.
- Artifacts/links:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingCatalogController.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogServiceBulkVariantRaceTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/RawMaterialAndProductUpdateIT.java`
  - `openapi.json`

## STAGE-109 Addendum (2026-02-25, refactor-techdebt-gc)
- Ticket / PR: `TKT-ERP-STAGE-109` / PR #81 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/81)
- Source branch: `tkt-erp-stage-109-refactor-techdebt-gc`
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/CompanyService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/CompanyController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/TenantAdminProvisioningService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java`
- Why this is R2: super-admin tenant bootstrap, tenant-admin credential reset, and auth/company control-plane flows are high-risk authority and recovery boundaries that must remain fail-closed with deterministic support recovery evidence.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + auth/company owners
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -B -ntp -Dtest=EmailServiceTest,TenantAdminProvisioningServiceTest,CompanyServiceTest,AdminUserServiceTest,DealerServiceTest,DataInitializerTest test`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
    - `bash ci/check-orchestrator-layer.sh`
  - Result summary:
    - targeted auth/company/notification/data-seed suites passed (`Tests run: 48, Failures: 0, Errors: 0, Skipped: 0`).
    - enterprise policy gate updated with this checkpoint addendum for the same high-risk change set.
    - local Docker/Testcontainers runtime is unavailable for integration lanes; GitHub Actions is the authoritative environment for full integration execution and merge gating.
  - Artifacts/links:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/CompanyService.java`
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/TenantAdminProvisioningService.java`
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/DataInitializer.java`
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/notification/EmailService.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/service/CompanyServiceTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/service/TenantAdminProvisioningServiceTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/notification/EmailServiceTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/DataInitializerTest.java`

## STAGE-111 Addendum (2026-02-25, auth-rbac-company)
- Ticket / PR: `TKT-ERP-STAGE-111` / pending PR creation
- Source branch: `tickets/TKT-ERP-STAGE-111`
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/controller/AuthController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java`
- Why this is R2: superadmin password recovery and public reset endpoint admission changed across auth and tenant-boundary controls; these are high-risk identity-recovery and access-enforcement paths.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + auth/security owners
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -Dtest=PasswordResetServiceTest,ForgotPasswordRequestCompatibilityTest,CompanyContextFilterPasswordResetBypassTest test`
    - `cd erp-domain && mvn -Dtest=EmailServiceTest,TenantAdminProvisioningServiceTest,CompanyServiceTest,AdminUserServiceTest,DealerServiceTest,DataInitializerTest,PasswordResetServiceTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest test`
    - `ssh asus-tuf-tail-ip 'cd /home/realnigga/tmp/tkt-erp-stage-111-auth-rbac-company/erp-domain && mvn -Dtest=AuthTenantAuthorityIT test'`
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary:
    - superadmin forgot-password flow now supports frontend `userid` payload alias, exposes a dedicated public endpoint, and fails closed when reset email delivery is disabled or SMTP dispatch fails.
    - company-header enforcement now bypasses only the three public password-reset POST routes to prevent false 403/invalid-token outcomes.
    - targeted unit/runtime suites passed (`Tests run: 99, Failures: 0, Errors: 0, Skipped: 0` across both Maven commands).
    - Docker/Testcontainers integration lane validated on SSH host `asus-tuf-tail-ip` (`AuthTenantAuthorityIT`: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`).
  - Artifacts/links:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/web/ForgotPasswordRequest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/web/ForgotPasswordRequestCompatibilityTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/CompanyContextFilterPasswordResetBypassTest.java`

## STAGE-111 Addendum (2026-02-25, auth-rbac-company follow-up)
- Ticket / PR: `TKT-ERP-STAGE-111` / PR #84 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/84)
- Source branch: `tickets/TKT-ERP-STAGE-111`
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/PasswordResetTokenRepository.java`
- Why this is R2: superadmin password-reset recovery cleanup semantics were changed to delete only the just-persisted token on failed delivery; this is a high-risk identity-recovery control path.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + auth/security owners
- Verification evidence:
  - Commands run:
    - `cd erp-domain && mvn -B -ntp -Dtest=PasswordResetServiceTest test`
    - `bash ci/check-enterprise-policy.sh`
  - Result summary:
    - targeted password-reset unit suite passed with the token-specific cleanup behavior (`Tests run: 8, Failures: 0, Errors: 0`).
    - enterprise-policy gate passed after recording this follow-up checkpoint evidence.
  - Artifacts/links:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/PasswordResetTokenRepository.java`
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetService.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetServiceTest.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeCompanyContextFilterExecutableCoverageTest.java`

## STAGE-113 Addendum (2026-02-26, blocker-remediation-orchestrator integration)
- Ticket / PR: `TKT-ERP-STAGE-113` / PR #85 (https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/85)
- Source branch: tickets/tkt-erp-stage-113/blocker-remediation-orchestrator
- High-risk paths:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/`
- Why this is R2: the integrated recovery set touches superadmin credential reset hardening, RBAC authority behavior, and orchestrator correlation/idempotency controls; these are fail-closed security and runtime-governance boundaries.
- Approval mode: orchestrator
- Human escalation required: no
- Rollback owner: release governance + auth/rbac/orchestrator owners
- Verification evidence:
  - Commands run:
    - `bash ci/check-architecture.sh`
    - `bash ci/check-enterprise-policy.sh`
    - `bash ci/check-orchestrator-layer.sh`
    - `python3 scripts/changed_files_coverage.py --diff-base origin/harness-engineering-orchestrator --jacoco erp-domain/target/site/jacoco/jacoco.xml`
    - `bash scripts/guard_orchestrator_correlation_contract.sh`
    - `bash scripts/guard_flyway_v2_migration_ownership.sh`
    - `VERIFY_LOCAL_SKIP_TESTS=true bash scripts/verify_local.sh`
    - `cd erp-domain && mvn -B -ntp -Dtest='DataInitializerSecurityTest,JwtPropertiesSecurityTest,DataInitializerTest,OrchestratorControllerIT,CommandDispatcherTest,IntegrationCoordinatorTest,CorrelationIdentifierSanitizerTest,OrchestratorIdempotencyServiceTest,TS_RuntimeOrchestratorCorrelationCoverageTest,EventPublisherServiceTest,TS_OrchestratorExactlyOnceOutboxTest,TS_RuntimeEventPublisherExecutableCoverageTest,AuthTenantAuthorityIT,RoleServiceRbacTenantIsolationTest' test`
  - Result summary:
    - architecture, enterprise-policy, and orchestrator-layer checks passed on the integrated head.
    - changed-files coverage passed (`line_ratio=0.9694`, `branch_ratio=0.9466`).
    - ticket-critical integrated test pack passed (`Tests run: 192, Failures: 0, Errors: 0, Skipped: 0`) with Testcontainers-backed `AuthTenantAuthorityIT` validated via Colima Docker socket.
  - Artifacts/links:
    - `tickets/TKT-ERP-STAGE-113/reports/reviews/MERGE-SPECIALIST-RECOVERY-HANDOFF-RERUN-2.md`
    - `tickets/TKT-ERP-STAGE-113/reports/reviews/QA-RECOVERY-HANDOFF-REPORT.md`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/AuthTenantAuthorityIT.java`
    - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeOrchestratorCorrelationCoverageTest.java`
