# R2 Checkpoint (Active Approval Record)

Last reviewed: 2026-02-15
Owner: Security & Governance Agent
Status: template-initialized

Update this file in every high-risk change set.

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
