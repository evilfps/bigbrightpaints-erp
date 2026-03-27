# R2 Checkpoint

## Scope
- Feature: `ERP-22 supplier ledger hard cut`
- Branch: `mdanas7869292/erp-22-hard-cut-supplier-ledger-truth-onto-one-canonical-settlement`
- Review candidate: remove supplier-row cached balance truth and enforce one canonical supplier-money public path (`settlement`) with fail-closed idempotency behavior.
- Why this is R2: this packet changes accounting/purchasing money-truth semantics plus a `migration_v2` schema drop. A wrong cut can break payable reconciliation, settlement workflows, or run incompatible runtime/schema combinations.

## Risk Trigger
- Triggered by high-risk paths:
  - `erp-domain/src/main/resources/db/migration_v2/V170__supplier_outstanding_balance_hard_cut.sql`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
- Contract surfaces affected:
  - `POST /api/v1/accounting/settlements/suppliers`
  - `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle`
  - supplier list/detail balance read-model output (`SupplierResponse.balance`)
- Failure mode if wrong:
  - supplier payable reconciliation drift between control account and ledger
  - AP settlement route regressions or replay mismatch behavior
  - runtime expecting removed `suppliers.outstanding_balance` column

## Approval Authority
- Mode: human
- Approver: `human accounting platform reviewer`
- Canary owner: `ERP-22 packet owner`
- Approval status: `pending required CI checks plus human review`
- Basis: packet mutates accounting truth path and migration_v2 schema; policy and test green are required before merge.

## Escalation Decision
- Human escalation required: yes
- Reason: financial-contract and schema-cut packet with potential cross-module impact if runtime/schema rollout order is wrong.

## Rollback Owner
- Owner: `ERP-22 packet owner`
- Rollback method: revert this packet before merge; if migration already executed on a tenant database, restore from pre-`V170` snapshot/PITR instead of introducing fallback codepaths.
- Rollback trigger:
  - settlement or supplier-aging/statement flows regress
  - payable reconciliation shows unexplained supplier variance
  - deployment hits missing-column/runtime mismatch around `suppliers.outstanding_balance`

## Expiry
- Valid until: `2026-04-03`
- Re-evaluate if: scope expands beyond ERP-22 supplier ledger hard cut, or any additional migration steps are added to this packet.

## Verification Evidence
- Commands run:
  - `colima status`
  - `cd erp-domain && mvn -DskipTests test-compile`
  - `cd erp-domain && DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest=AccountingControllerIdempotencyHeaderParityTest,AccountingControllerJournalEndpointsTest,TS_RuntimeAccountingReplayConflictExecutableCoverageTest,TS_P2PPurchaseSettlementBoundaryTest,ReconciliationServiceTest,ProcureToPayE2ETest test`
  - `cd erp-domain && DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest=OpenApiSnapshotIT -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true test`
  - `bash scripts/guard_openapi_contract_drift.sh`
  - `bash scripts/guard_accounting_portal_scope_contract.sh`
  - `bash scripts/guard_workflow_canonical_paths.sh`
  - `ENTERPRISE_DIFF_BASE=f559927f8fccddad1bd3c78e606da59023dfb0fe bash ci/check-enterprise-policy.sh`
- Result summary:
  - removed persisted supplier cached balance truth and added `V170` drop-column migration
  - preserved optional supplier `balance` only as ledger-derived read-model output
  - removed legacy public `/api/v1/accounting/suppliers/payments` route; settlement is canonical public supplier money flow
  - removed stale supplier-money fallback/idempotency glue and aligned tests/docs/openapi to the hard-cut surface
  - targeted ERP-22 suite and local policy/contract guards passed before PR push
- Artifacts/links:
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-22-supplier-ledger-hard-cut`
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/162`
  - Linear issue: `ERP-22`
