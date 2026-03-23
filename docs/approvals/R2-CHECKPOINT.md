# R2 Checkpoint

## Scope
- Feature: `ERP-32 durable credit workflow consolidation`
- PR: `#134`
- PR branch: `feature/erp-32-credit-workflow-consolidation`
- Review candidate: durable dealer credit-limit workflow split from temporary dispatch overrides on PR `#134`
- Why this is R2: this packet changes tenant-scoped module-gated runtime surfaces, admin approval routing, and dealer/accounting outward credit truth for a permanent-credit workflow that can mutate `Dealer.creditLimit`.

## Risk Trigger
- Triggered by changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/` plus dealer/admin runtime behavior updates that reshape permanent credit-limit approvals and dealer-facing credit truth.
- Contract surfaces affected: `POST /api/v1/dealer-portal/credit-limit-requests`, `GET|POST /api/v1/credit/limit-requests`, `POST /api/v1/credit/limit-requests/{id}/approve`, `POST /api/v1/credit/limit-requests/{id}/reject`, admin approval queue summaries, dealer portal dashboard credit exposure, and sales-facing dealer credit summaries.
- Failure mode if wrong: durable credit requests can disappear behind stale routes, admin approvals can target the wrong workflow semantics, dealer portal aging can drift away from ledger-backed accounting truth, or temporary overrides can be mistaken for permanent credit-limit changes.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Canary owner: `Anas ibn Anwar`
- Approval status: `pending green CI and explicit merge approval`
- Basis: focused service/controller/RBAC proof, integrated ERP-32 regression proof, OpenAPI refresh, accounting-portal scope contract proof, and clean diff hygiene on PR `#134`.

## Escalation Decision
- Human escalation required: yes
- Reason: this packet changes durable dealer credit-limit approvals and tenant-scoped outward read surfaces, so merge remains gated on explicit human approval after CI settles on PR `#134`.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: revert commit `38c63edc`, redeploy the prior backend build, regenerate OpenAPI from the reverted head, and rerun the focused dealer/admin credit workflow proof before reopening credit-limit operations.
- Rollback trigger:
  - durable credit-limit requests fail to create/list/approve/reject on the new canonical endpoints
  - admin approval queue labels permanent credit changes as dispatch overrides or targets the wrong endpoint
  - dealer portal or sales credit exposure drifts away from ledger-backed aging/balance truth
  - approved temporary overrides start mutating long-term `Dealer.creditLimit`

## Expiry
- Valid until: `2026-03-30`
- Re-evaluate if: scope grows beyond ERP-32 durable-vs-temporary credit workflow boundaries, the PR head changes beyond commit `38c63edc`, or CI reruns against a materially different candidate than PR `#134`.

## Verification Evidence
- Compile gate:
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation/erp-domain" && mvn -q -DskipTests compile`
  - result: `BUILD SUCCESS`
- Test compile:
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation/erp-domain" && mvn -q -DskipTests test-compile`
  - result: `BUILD SUCCESS`
- Focused durable/read-surface proof:
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation/erp-domain" && mvn -q -Dtest=CreditLimitRequestServiceTest,DealerPortalControllerExportAuditTest,DealerPortalServiceTest,DealerServiceTest,SalesControllerIdempotencyHeaderTest,TS_RuntimeDealerPortalControllerExportCoverageTest,TS_RuntimeOrchestratorExecutableCoverageTest test`
  - result: `BUILD SUCCESS`
- Focused RBAC/approval proof:
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation/erp-domain" && mvn -q -Dtest=PortalRoleActionMatrixTest,DealerPortalReadOnlySecurityIT,AdminApprovalRbacIT,AdminSettingsControllerApprovalsContractTest test`
  - result: `BUILD SUCCESS`
- Integrated ERP-32 regression proof:
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation/erp-domain" && mvn -q -Dtest=CreditLimitRequestServiceTest,CreditLimitRequestDecisionRequestTest,DealerPortalControllerExportAuditTest,DealerPortalServiceTest,DealerServiceTest,SalesControllerIdempotencyHeaderTest,TS_RuntimeDealerPortalControllerExportCoverageTest,TS_RuntimeOrchestratorExecutableCoverageTest,SalesControllerIT,PortalRoleActionMatrixTest,DealerPortalReadOnlySecurityIT,AdminApprovalRbacIT,AdminSettingsControllerApprovalsContractTest,OpenApiSnapshotIT -Derp.openapi.snapshot.verify=true test`
  - result: `BUILD SUCCESS`
- Contract and hygiene guards:
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation" && bash scripts/guard_openapi_contract_drift.sh`
  - result: `[guard_openapi_contract_drift] OK`
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation" && bash scripts/guard_accounting_portal_scope_contract.sh`
  - result: `[guard_accounting_portal_scope_contract] OK`
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation" && git diff --check`
  - result: clean
- Artifacts/links:
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/134`
  - Commit: `38c63edc`
  - OpenAPI snapshot: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-32-credit-workflow-consolidation/openapi.json`
