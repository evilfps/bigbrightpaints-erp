# R2 Checkpoint

Last reviewed: 2026-04-07

## Scope
- Feature: `m7-fix-sales-order-confirm-inventory-reservation-and-contract-refresh`
- Branch: current sales-order remediation branch
- Review candidate:
  - keep Flyway v2 migration `erp-domain/src/main/resources/db/migration_v2/V179__sales_order_lifecycle_payment_terms_finished_good.sql` as the canonical schema source for `sales_orders.payment_terms` and `sales_order_items.finished_good_id`
  - enforce draft-lifecycle confirmation reservation semantics in `SalesCoreEngine.confirmOrder()` so confirm reserves inventory and fails closed when reservation is incomplete
  - refresh public contract surfaces (`openapi.json`, canonical sales docs, endpoint inventory metadata) so published DTO/status semantics match runtime behavior
- Why this is R2: the packet relies on and validates an active `migration_v2` schema addition and updates runtime behavior that depends on those columns.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/resources/db/migration_v2/V179__sales_order_lifecycle_payment_terms_finished_good.sql` (active migration lane scope)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesCoreEngine.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/SalesController.java`
  - `openapi.json`
- Contract surfaces affected:
  - sales order create payload/response fields (`paymentTerms`, `finishedGoodId`)
  - order confirmation reservation behavior and cancellation release path for draft-lifecycle orders
  - published OpenAPI response semantics for `POST /api/v1/sales/orders` and timeline alias fields
- Failure mode if wrong:
  - draft lifecycle orders can confirm without owning stock reservations
  - cancellation may not release meaningful reservations for draft-lifecycle orders
  - generated clients/frontend packets can drift from runtime if OpenAPI/docs remain stale

## Approval Authority
- Mode: orchestrator
- Approver: Droid mission orchestrator
- Canary owner: Droid mission orchestrator
- Approval status: approved for compatibility-preserving remediation
- Basis: the migration scope is additive/compatibility-preserving and runtime changes tighten correctness without widening privileges or crossing tenant boundaries.

## Escalation Decision
- Human escalation required: no
- Reason: no privilege widening, tenant-boundary expansion, or destructive DDL is introduced; remediation is bounded by regression and contract guards.

## Rollback Owner
- Owner: Droid mission orchestrator
- Rollback method:
  - before merge: revert the reservation/contract/doc packet together
  - after merge: revert packet plus restore from backup/PITR if data backfill behavior must be undone
- Rollback trigger:
  - sales confirm/cancel lifecycle regression on draft-lifecycle orders
  - openapi drift guard or gate-fast regression after merge

## Expiry
- Valid until: 2026-04-21
- Re-evaluate if: the packet expands into auth/RBAC/tenant-boundary changes or introduces destructive migration rewrites.

## Test Waiver
- Not applicable — runtime, contract, and governance surfaces changed and validator evidence is required.

## Verification Evidence
- Commands run:
  - `cd "/home/realnigga/Desktop/Mission-control/erp-domain" && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='SalesServiceTest,SalesControllerIT' test`
  - `ROOT="/home/realnigga/Desktop/Mission-control" && cd "$ROOT/erp-domain" && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest=OpenApiSnapshotIT -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true test`
  - `cd "/home/realnigga/Desktop/Mission-control" && bash scripts/guard_openapi_contract_drift.sh`
  - `cd "/home/realnigga/Desktop/Mission-control" && bash scripts/gate_fast.sh`
  - `cd "/home/realnigga/Desktop/Mission-control" && bash ci/check-codex-review-guidelines.sh`
  - `cd "/home/realnigga/Desktop/Mission-control" && bash ci/check-enterprise-policy.sh`
- Result summary:
  - draft-lifecycle confirm now reserves stock and fails closed when reservation-backed slip state is missing; lifecycle tests cover reserve-on-confirm plus release-on-cancel behavior
  - OpenAPI snapshot and canonical docs (`docs/endpoint-inventory.md`, `docs/frontend-portals/sales/api-contracts.md`, and `docs/frontend-portals/sales/frontend-engineer-handoff.md`) reflect `paymentTerms`, `finishedGoodId`, timeline aliases, and create-order HTTP 200 and 201 semantics
  - governance checkpoint now records migration-v2 risk handling and verification proof for V179 scope

---

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
