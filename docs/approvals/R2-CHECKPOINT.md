# R2 Checkpoint

## Scope
- Feature: `recovery-review.corrections-control-migration-closure`
- Branch: `recovery/05-corrections-control`
- High-risk paths touched: accounting controller/core services, sales return service, purchase return service, `db/migration_v2/V161__manual_journal_attachments_and_closed_period_exceptions.sql`, and the focused accounting/control regression suites.
- Why this is R2: the recovery packet closes PR #99 by hardening correction provenance, stabilizing header-level settlement replay on one canonical key path, failing closed on unsupported legacy return state, and carrying the non-destructive corrections/control schema change with explicit governance evidence.

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
- Commands run: `cd /home/realnigga/Desktop/Mission-control && bash ci/check-enterprise-policy.sh`; `cd /home/realnigga/Desktop/Mission-control && bash ci/check-codex-review-guidelines.sh`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='SalesReturnServiceTest,AccountingPeriodServicePolicyTest,AccountingServiceTest,CR_DealerReceiptSettlementAuditTrailTest' test`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
- Result summary: the recovery packet now keeps only canonical `CRN-` sales return references on the live path, relinks correction journals back to posted source entries, reuses stable header-settlement idempotency keys for dealer and supplier retries, lets supplier settlement replay win before later lifecycle suspension, fails closed on unsupported marker-less legacy return movements with documented recovery steps, and passes the targeted accounting/control suites plus the fast gate.
- Artifacts/links: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingCoreEngineCore.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchaseReturnService.java`, `erp-domain/src/main/resources/db/migration_v2/V161__manual_journal_attachments_and_closed_period_exceptions.sql`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnServiceTest.java`
