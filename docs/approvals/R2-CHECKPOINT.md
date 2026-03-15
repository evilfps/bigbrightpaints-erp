# R2 Checkpoint

## Scope
- Feature: `o2c-dispatch-canonicalization`
- Branch: `codex/pr111-followups`
- High-risk paths touched: `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/**`
- Why this is R2: this follow-up changes the merged orchestrator dispatch-canonicalization contract in a high-risk area by altering prod readiness behavior, CI guard enforcement, and fulfillment canonical-path guidance, so enterprise policy requires same-diff approval evidence for the corrected runtime contract.

## Risk Trigger
- Triggered by post-merge regressions left behind after dispatch canonicalization: stale readiness membership for deleted `dispatchMapping`, stale correlation guard assertions for the removed orchestrator dispatch workflow, and fulfillment guidance that pointed order callers at the packaging-slip-first dispatch endpoint.
- Contract change: prod readiness now includes only live health contributors, the correlation guard validates only surviving orchestrator flows, and dispatch-like fulfillment rejections now point to the order-capable sales dispatch confirm route.
- Canonical posting rule remains unchanged: `SalesCoreEngine.confirmDispatch -> AccountingFacade` is the sole commercial-to-accounting trigger for dispatch truth.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization mission orchestration
- Basis: follow-up remediation that closes post-merge regressions without restoring any removed dispatch posting path, adding new privileges, or widening tenant scope.

## Escalation Decision
- Human escalation required: no
- Reason: the follow-up preserves the canonical dispatch design and only corrects runtime/configuration guidance and guard behavior; it does not introduce new privileges, tenant boundary changes, or destructive migration risk.

## Rollback Owner
- Owner: Factory-droid integration worker
- Rollback method: revert follow-up commits `cb688e6f`, `1ba4eba3`, and `4ddf5956`, rerun `cd erp-domain && mvn -B -ntp -Dtest=CR_ProductionMonitoringContractTest test`, rerun `bash scripts/guard_orchestrator_correlation_contract.sh`, rerun `bash scripts/guard_workflow_canonical_paths.sh`, rerun `cd erp-domain && mvn -B -ntp -Dtest=IntegrationCoordinatorTest test`, rerun `cd erp-domain && mvn -B -ntp -Dtest=OrchestratorControllerIT test`, and rerun `bash ci/check-enterprise-policy.sh` before re-review.

## Expiry
- Valid until: 2026-03-28
- Re-evaluate if: additional high-risk orchestrator files are added to this follow-up, readiness health membership changes again, canonical fulfillment routing changes again, or any validator disproves the fail-closed/canonical-only dispatch contract.

## Verification Evidence
- Commands run: `cd erp-domain && mvn -B -ntp -Dtest=CR_ProductionMonitoringContractTest test` (`4` tests, `0` failures/errors), `bash scripts/guard_orchestrator_correlation_contract.sh` (`OK`), `bash scripts/guard_workflow_canonical_paths.sh` (`OK`), `cd erp-domain && mvn -B -ntp -Dtest=IntegrationCoordinatorTest test` (`25` tests, `0` failures/errors), `cd erp-domain && mvn -B -ntp -Dtest=OrchestratorControllerIT test` (`24` tests, `0` failures/errors with Colima-backed Testcontainers), `bash ci/check-enterprise-policy.sh` (`pending rerun after this same-diff R2 update`).
- Result summary: the follow-up removes the stale prod readiness member for the deleted dispatch indicator, aligns the correlation guard with the removed orchestrator dispatch workflow, preserves fail-closed batch dispatch behavior, and redirects fulfillment callers to the order-capable `/api/v1/sales/dispatch/confirm` path while keeping canonical dispatch posting exclusively `SalesCoreEngine.confirmDispatch -> AccountingFacade`.
- Artifacts/links: `docs/approvals/R2-CHECKPOINT.md`, `erp-domain/src/main/resources/application-prod.yml`, `scripts/guard_orchestrator_correlation_contract.sh`, `scripts/guard_workflow_canonical_paths.sh`, `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_ProductionMonitoringContractTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java`.
