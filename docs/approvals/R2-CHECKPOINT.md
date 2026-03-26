# R2 Checkpoint

## Scope
- Feature: `restore-factory-owned-dispatch-confirmation`
- Branch: `hotfix/restore-factory-dispatch-ownership`
- Review candidate: restore `POST /api/v1/dispatch/confirm` as the sole public dispatch-confirm write route, remove the public sales dispatch confirm route, return `dispatch.confirm` ownership to factory/admin operational flow, and refresh the dependent orchestrator, audit, OpenAPI, endpoint-inventory, and order-to-cash evidence surfaces so accounting stays downstream of factory-confirmed dispatch truth.
- Why this is R2: the packet changes `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java` and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/domain/SystemRole.java` while correcting a live ownership regression on a public fulfillment boundary. A wrong change here would publish contradictory authority rules, break downstream accounting linkage, or produce incorrect dispatch audit provenance.

## Risk Trigger
- Triggered by changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/` and `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/`, plus the dispatch controller surface that is the public physical-shipment truth boundary.
- Contract surfaces affected: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/SalesController.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/PortalRoleActionMatrix.java`, `openapi.json`, `docs/endpoint-inventory.md`, `erp-domain/docs/endpoint_inventory.tsv`, and the dispatch/orchestrator review notes under `docs/code-review/**`.
- Failure mode if wrong: sales could remain or reappear as a second public dispatch confirmer, factory users could lose the ability to record physical dispatch truth, audit logs could attribute dispatch confirmation to the wrong surface or actor, and orchestrator guidance could keep routing callers to a non-canonical path.

## Approval Authority
- Mode: orchestrator
- Approver: `dispatch ownership hotfix orchestrator`
- Canary owner: `dispatch ownership hotfix orchestrator`
- Approval status: `pending green remote validators and PR review`
- Basis: this is a corrective hard-cut that removes the wrong public ownership path and restores the intended operational authority without adding fallback routes, migration shims, or broader privilege expansion.

## Escalation Decision
- Human escalation required: no
- Reason: the packet restores the intended single canonical path and does not introduce data migrations, schema changes, or widened destructive capabilities. The risk is operational correctness at the dispatch/accounting boundary, which is covered by focused regression and audit proofs in this same change set.

## Rollback Owner
- Owner: `dispatch ownership hotfix orchestrator`
- Rollback method: revert this hotfix commit if remote validation reveals a downstream dependency on the wrong sales-owned route; do not reintroduce the retired sales dispatch confirm path as a temporary bridge.
- Rollback trigger:
  - runtime or truthsuite evidence shows `/api/v1/dispatch/confirm` no longer functions as the sole public dispatch-confirm surface
  - audit logs stop attributing dispatch confirmation to the factory route and factory actor
  - orchestrator/order-to-cash flows fail because downstream accounting markers are no longer reconciled after factory confirmation

## Expiry
- Valid until: `2026-04-02`
- Re-evaluate if: the packet scope expands beyond dispatch ownership correction, if any additional public dispatch write path is proposed, or if accounting ownership is changed from downstream reconciliation to something broader.

## Verification Evidence
- Commands run:
  - `unset GH_TOKEN GITHUB_TOKEN; git status --short --branch`
  - `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 DOCKER_API_VERSION=1.53; mvn -B -ntp -Dtest=CoreFallbackExceptionHandlerTest,PortalRoleActionMatrixTest,SalesControllerIT test`
  - `export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 DOCKER_API_VERSION=1.53; mvn -B -ntp -Dtest=DispatchControllerTest,PortalRoleActionMatrixTest,SystemRoleTest,RbacSynchronizationConfigTest,RoleServiceTest,SalesControllerIdempotencyHeaderTest,SalesControllerIT,AuthTenantAuthorityIT,DispatchOperationalBoundaryIT,TS_O2CDispatchProvenanceAndRetiredRouteBoundaryTest,ErpInvariantsSuiteIT,OrderFulfillmentE2ETest,SalesReturnCreditNoteE2EIT,CreditDebitNoteIT,BusinessLogicRegressionTest,OrchestratorControllerIT,CommandDispatcherTest,IntegrationCoordinatorTest,CoreFallbackExceptionHandlerTest,CriticalPathSmokeTest,TS_O2COrchestratorDispatchRemovalRegressionTest,TS_OrchestratorExactlyOnceOutboxTest,TS_RuntimeOrchestratorExecutableCoverageTest,OpenApiSnapshotIT,FullCycleE2ETest test`
  - `bash scripts/guard_workflow_canonical_paths.sh`
  - `git diff --check`
- Result summary:
  - focused controller/security validation passed after the hotfix-specific access-denied guidance and retired-route assertions were aligned to the restored factory canonical path.
  - the broad cross-module pack passed with `240` tests run, `0` failures, `0` errors, and `2` skipped, covering dispatch operational audit provenance, order-to-cash, returns/credit notes, invariants, orchestrator regression, truthsuite, and OpenAPI snapshot integrity.
  - the canonical workflow guard passed, and endpoint inventory/OpenAPI artifacts were refreshed so the retired sales dispatch confirm route is no longer advertised as a live public write surface.
- Artifacts/links:
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/hotfix-restore-factory-dispatch-ownership`
  - Commit: `070189ae690020e63b235bff60d6fcaf4d5d90ab`
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/155`
