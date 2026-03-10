# R2 Checkpoint

## Scope
- Feature: `recovery-review.corrections-control-migration-closure`
- Follow-up packet: `recovery-followup.corrections-comment-closure-after-green-rerun`
- Branch: `recovery/05-corrections-control`
- High-risk paths touched: accounting controller/core services, sales return service, purchase return service, `db/migration_v2/V161__manual_journal_attachments_and_closed_period_exceptions.sql`, and the focused accounting/control regression suites; the latest follow-up narrows to `AccountingCoreEngineCore` supplier-settlement replay ordering plus `AccountingServiceTest`.
- Why this is R2: the recovery packet closes PR #99 by hardening correction provenance, stabilizing header-level settlement replay on one canonical key path, failing closed on unsupported legacy return state, and carrying the non-destructive corrections/control schema change with explicit governance evidence. The latest follow-up keeps the same high-risk accounting scope while tightening the concurrent duplicate supplier-settlement path so omitted-reference retries reserve one canonical settlement reference instead of failing with `CONCURRENCY_CONFLICT`.

## Risk Trigger
- Triggered by edits under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/`, and `erp-domain/src/main/resources/db/migration_v2/`.
- Contract surfaces affected: linked sales returns, linked purchase returns, correction journal provenance, header-level settlement idempotency, supplier lifecycle fail-closed ordering, correction preview endpoints, and accounting period-close blocker checks.
- Main risks being controlled: silent mutation of posted source documents, unlinked correction journals that allow close drift, unsupported marker-less legacy returns being retried through hidden compatibility branches, settlement retries that miss prior header-level allocations, mutable supplier lifecycle blocking legitimate replays, and schema drift around manual-journal attachments plus closed-period exception rows.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization mission orchestration
- Basis: compatibility-preserving accounting remediation within the active mission scope; the packet adds one non-destructive Flyway v2 migration (`V161`) for manual-journal attachment references and closed-period posting exceptions, without privilege expansion or tenant-boundary widening.

## Escalation Decision
- Human escalation required: no
- Reason: the packet tightens existing accounting controls and linkage invariants, keeps one canonical corrections/control replay path with explicit recovery guidance for old local data, and introduces only additive/non-destructive persistence changes.

## Rollback Owner
- Owner: corrections-and-control feature worker
- Rollback method: revert the recovery fix commit, leave `V161` unapplied if rollback happens pre-deploy, or deploy the previous app build and manually drop the additive schema objects from `V161` in the same maintenance window if rollback happens after apply; rerun the targeted correction suites and `MIGRATION_SET=v2 mvn -Pgate-fast -Djacoco.skip=true test` before merge.

## Expiry
- Valid until: 2026-03-13
- Re-evaluate if: additional accounting reversal types, tenant-crossing journal flows, or further migration-backed linkage/idempotency fields are added.

## Verification Evidence
- Commands run: `cd /home/realnigga/Desktop/Mission-control && bash ci/check-enterprise-policy.sh`; `cd /home/realnigga/Desktop/Mission-control && bash ci/check-codex-review-guidelines.sh`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='AccountingServiceTest,AccountingCoreEngineCoreTest,ReferenceNumberServiceTest' test`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
- Result summary: the recovery packet keeps only canonical `CRN-` sales return references on the live path, relinks correction journals back to posted source entries, reuses stable header-settlement idempotency keys for dealer and supplier retries, lets supplier settlement replay win before later lifecycle suspension, fails closed on unsupported marker-less legacy return movements with documented recovery steps, and now reserves supplier-settlement idempotency with a deterministic placeholder-first mapping so concurrent duplicate submissions without `referenceNumber` replay to the same canonical settlement reference instead of failing with `CONCURRENCY_CONFLICT`.
- Artifacts/links: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingCoreEngineCore.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchaseReturnService.java`, `erp-domain/src/main/resources/db/migration_v2/V161__manual_journal_attachments_and_closed_period_exceptions.sql`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnServiceTest.java`

## Additional Scope: `recovery-followup.corrections-control-comment-recheck-and-closure`
- Packet target: `recovery/05-corrections-control` rechecked on the latest stacked head.
- High-risk paths touched: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingPeriodServiceCore.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodServicePolicyTest.java`, and this checkpoint.
- Why this is R2: the packet changes month-end control behavior in accounting so upgraded tenants can close periods with legitimate legacy `CRN-` / `PRN-` return journals that predate persisted correction-linkage metadata, while preserving the stricter current correction-linkage contract for newly created journals.

### Additional Risk Trigger
- Triggered by the newly surfaced PR #99 review thread on historical return-journal period-close compatibility.
- Contract surfaces affected: correction-linkage gap detection during period close, legacy return-journal compatibility, and focused policy regression coverage.
- Main risks being controlled: false period-close blockers on upgraded tenants with historical automated return journals, and accidental widening that would let partially populated modern correction metadata evade the close-control contract.

### Additional Approval / Escalation
- Mode: orchestrator.
- Human escalation required: no.
- Basis: the fix is compatibility-preserving within the approved corrections/control scope, adds no migration behavior, and does not widen tenant privileges or posting authority.

### Additional Rollback Owner
- Owner: corrections/control recovery worker.
- Rollback method: revert the feature commit, then rerun `cd erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='AccountingPeriodServicePolicyTest,AccountingServiceTest,SalesReturnServiceTest,PurchaseReturnServiceTest' test`, `bash ci/check-enterprise-policy.sh`, and `bash ci/check-codex-review-guidelines.sh` before re-review.

### Additional Expiry
- Valid until: 2026-03-14.
- Re-evaluate if: later packets introduce a migration/backfill for legacy correction linkage, or if new return-journal creation paths stop persisting full correction metadata.

### Additional Verification Evidence
- Commands run: `cd erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest=AccountingPeriodServicePolicyTest test`; `cd erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='AccountingPeriodServicePolicyTest,AccountingServiceTest,SalesReturnServiceTest,PurchaseReturnServiceTest' test`; `bash ci/check-enterprise-policy.sh`; `bash ci/check-codex-review-guidelines.sh`; `gh pr checks 99 --repo anasibnanwar-XYE/bigbrightpaints-erp || true`.
- Result summary: the new policy regression proves prefix-only historical sales and purchase return journals with no persisted correction metadata no longer count as period-close correction-linkage gaps when they are clearly legacy automated return journals, while existing strict checks still fail partially populated modern correction metadata; enterprise policy and codex review gates remain green; and PR #99 checks were re-read on the latest green head.
- Artifacts/links: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingPeriodServiceCore.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodServicePolicyTest.java`.
- Migration guidance note: no `db/migration_v2` files changed in this packet, so no migration-runbook update was required.
