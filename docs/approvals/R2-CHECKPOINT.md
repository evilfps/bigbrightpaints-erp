# R2 Checkpoint

Last reviewed: 2026-04-06

## Scope
- Feature: `m3-fix-packaging-slip-backfill-v2`
- Branch: current accounting cleanup branch
- Review candidate:
  - move the packaging-slip invoice-link backfill off the inactive legacy migration tree and onto the active Flyway v2 track as `erp-domain/src/main/resources/db/migration_v2/V177__backfill_packaging_slip_invoice_links.sql`
  - delete the stale legacy-track V167 packaging-slip backfill copy
  - add a truth-suite contract proving the legacy file is gone and the active migration still contains the canonical backfill SQL
- Why this is R2: the packet changes `erp-domain/src/main/resources/db/migration_v2`, which is a high-risk enterprise-policy path. It affects upgrade-path data repair for dispatch/invoice linkage after the packaging-slip hard cut.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/resources/db/migration_v2/V177__backfill_packaging_slip_invoice_links.sql`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/o2c/TS_PackagingSlipInvoiceLinkV2MigrationContractTest.java`
- Contract surfaces affected:
  - persisted `packaging_slips.invoice_id` backfill for upgraded tenants running Flyway v2
  - dispatch/invoice/read-model linkage proofs that now rely on the active migration track instead of the retired legacy track
  - Flyway version-history safety, because active v2 version `V167` is already occupied by ERP-37 and must not be mutated
- Failure mode if wrong:
  - upgraded tenants can retain null `packaging_slips.invoice_id` values even though current dispatch/invoice truth exists
  - dispatch and invoice read models can drift for historical rows after the hard cut
  - mutating active `V167` history instead of issuing a new v2 migration would risk Flyway checksum validation failures on existing databases

## Approval Authority
- Mode: orchestrator
- Approver: Droid mission orchestrator
- Canary owner: Droid mission orchestrator
- Approval status: approved for branch-local remediation
- Basis: this is a compatibility-preserving upgrade-path backfill with no privilege widening, tenant-boundary change, or destructive schema rewrite. The risk is bounded by targeted v2 migration proofs plus the branch gate.

## Escalation Decision
- Human escalation required: no
- Reason: the packet keeps runtime behavior on the canonical hard-cut path and repairs historical data on the active migration lane without widening access or introducing irreversible destructive DDL.

## Rollback Owner
- Owner: Droid mission orchestrator
- Rollback method:
  - before merge: revert the local migration/test/doc packet together
  - after merge: revert the packet only alongside a pre-`V177` snapshot/PITR restore, or keep the backfill-compatible runtime live
- Rollback trigger:
  - Flyway v2 validation or migration boot fails after `V177`
  - historical dispatch/invoice linkage proofs regress under `MIGRATION_SET=v2`
  - packaging-slip invoice links drift again for upgraded data

## Expiry
- Valid until: 2026-04-20
- Re-evaluate if: the packet starts changing active tenant-control/auth/accounting runtime code or rewrites any already-active Flyway version on the v2 track.

## Test Waiver
- Not applicable — migration, docs, and test evidence changed, and targeted validators were run.

## Verification Evidence
- Commands run:
  - `ROOT=$(git -C "/home/realnigga/Desktop/Mission-control" rev-parse --show-toplevel) && cd "$ROOT" && bash scripts/gate_fast.sh`
  - `cd "/home/realnigga/Desktop/Mission-control/erp-domain" && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='CR_PackingRouteHardCutIT,OrderFulfillmentE2ETest,InvoiceServiceTest' test`
  - `find "/home/realnigga/Desktop/Mission-control/erp-domain/src/main/resources" -name '*backfill_packaging_slip_invoice_links.sql'`
  - `cd "/home/realnigga/Desktop/Mission-control/erp-domain" && MIGRATION_SET=v2 mvn -q -DspotlessFiles='src/test/java/com/bigbrightpaints/erp/truthsuite/o2c/TS_PackagingSlipInvoiceLinkV2MigrationContractTest.java' spotless:check`
  - `cd "/home/realnigga/Desktop/Mission-control/erp-domain" && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='TS_PackagingSlipInvoiceLinkV2MigrationContractTest,CR_PackingRouteHardCutIT,OrderFulfillmentE2ETest,InvoiceServiceTest' test`
  - `cd "/home/realnigga/Desktop/Mission-control" && bash ci/check-codex-review-guidelines.sh`
  - `cd "/home/realnigga/Desktop/Mission-control" && bash ci/check-enterprise-policy.sh`
  - `cd "/home/realnigga/Desktop/Mission-control" && bash scripts/gate_fast.sh`
- Result summary:
  - the only remaining packaging-slip invoice-link backfill file resolves under `erp-domain/src/main/resources/db/migration_v2`, and the legacy-track copy is gone
  - the new truth-suite migration contract passes, the targeted dispatch/invoice proof pack passes under Flyway v2 with 67 migrations applied, and the review/policy guards pass after the runbook + R2 updates
  - the packet intentionally uses new v2 version `V177` because active version `V167` is already in use on the main branch; this preserves Flyway history/checksum safety while moving the backfill onto the active track
- Artifacts/links:
  - repo checkout: current repository worktree
  - active migration: `erp-domain/src/main/resources/db/migration_v2/V177__backfill_packaging_slip_invoice_links.sql`
  - truth-suite proof: `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/o2c/TS_PackagingSlipInvoiceLinkV2MigrationContractTest.java`
